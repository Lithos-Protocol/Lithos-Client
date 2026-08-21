package mining

import configs.CandidateConfig
import lfsm.{EmissionSchedule, LFSMHelpers}
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, CSigmaProp, ProveDlog}
import sigma.serialization.GroupElementSerializer
import sigma.{Coll, Colls, SigmaProp}
import stratum.{CollateralData, CollateralNotFoundException, InvalidSigmaBooleanException}
import transactions.ProtocolContracts
import transactions.ProtocolContracts.{hex, lenderEntry}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

/**
 * The genesis transaction, and the collateral boxes it is built from.
 *
 * The only transaction the mining path builds itself, because it is the only one that must exist
 * before a candidate can be requested: it makes the block a Lithos block and its lender decides the
 * coinbase key. Everything else is built in `transactions` and offered as a `CandidateTx`.
 *
 * Nothing here fetches protocol state — the collateral box arrives pre-loaded from
 * [[CandidateBuilder]] and contracts are compiled once by [[transactions.ProtocolContracts]].
 */
class CandidateTxBuilder(prover: NodeWallet, nodeApi: NodeApi, config: CandidateConfig) {

  private val logger: Logger = LoggerFactory.getLogger("CandidateTxBuilder")

  /**
   * Every live collateral box, which after bootstrap is the whole active set (at most 100).
   *
   * Confirmed only: a collateral box can only be spent inside a block, since its coinbase must pay
   * the box's lender, so the mempool never holds a competing spend. Boxes are identified by the
   * collateral token rather than by address, because anyone may fund a box at the collateral
   * address but only the emission box mints that token.
   */
  def loadCollateral(ctx: BlockchainContext): Seq[InputUTXO] = {
    val c = ProtocolContracts(ctx)
    // Hoisted out of the filters below. `Contract.ergoTreeHex` is a def that re-serialises the tree
    // and hex-encodes it on every call, and these run once per box across the whole active set.
    val gateTree = c.gate.ergoTreeHex
    val collateralTree = c.collateral.ergoTreeHex
    val boxes = nodeApi
      .unspentBoxesByTokenId(LFSMHelpers.COLLAT_TOKEN.toString, Paging.all(config.collateralPoolSize),
        SortDirection.Asc, MempoolOptions.ConfirmedOnly)
      .getOrElse(Seq.empty[IndexedBox])
      .filter(b => carriesOne(b, LFSMHelpers.COLLAT_TOKEN) &&
        b.box.additionalRegisters.ordered.size >= 5 &&
        b.ergoTree != gateTree)

    // A box under a different collateral script is unspendable here — its gate hash, LIT id or
    // holding hash are not the ones this client builds against. Only the emission box mints the
    // collateral token and it mints into one script, so this can only mean the compiled contract
    // has drifted from the deployed one, and every Lithos block is being missed until it is fixed.
    val usable = boxes.filter(_.ergoTree == collateralTree)
    if (usable.size < boxes.size)
      logger.error(s"${boxes.size - usable.size} of ${boxes.size} collateral boxes are NOT under the " +
        s"collateral script this build compiles (${collateralTree.take(16)}...) and were " +
        "skipped. This should be impossible: check the injected constants against the deployment")

    usable.map(_.toInputUTXO(ctx))
  }

  /**
   * The block's genesis transaction: one input, no fee output, no extra inputs.
   *
   * Every byte is serialised into the collateral proof carried by every super-share, so it is kept
   * small, and every output is funded from the collateral box's own R4 fee channel rather than the
   * miner's wallet. Only valid in a block whose coinbase pays this box's lender, which is what the
   * returned [[CollateralData.pk]] is for.
   *
   * @param blockHeight `chainHeight + 1`. Pinned into the holding box's R7 and the proof-of-spend
   *                    box's creation height, both compared against `HEIGHT` by the collateral
   *                    contract, so a package built for the wrong height is rejected outright.
   */
  def buildGenesis(ctx: BlockchainContext, collat: InputUTXO, blockHeight: Int): CollateralData = {
    val c = ProtocolContracts(ctx)

    val feeValue = collat.parseReg[Long](0)
    val lenderProp = collat.registers(1).getValue.asInstanceOf[SigmaProp]
    val lenderBoolean = lenderProp match {
      case CSigmaProp(sigmaTree) => sigmaTree
      case _ => throw new InvalidSigmaBooleanException("Found invalid SigmaBoolean on collateral utxo")
    }
    val lenderAddress = Address.fromErgoTree(ErgoTree.fromSigmaBoolean(lenderBoolean), ctx.getNetworkType)
    val pkString = lenderBoolean match {
      case ProveDlog(value) => Hex.toHexString(GroupElementSerializer.toBytes(value))
      case _ => throw new InvalidSigmaBooleanException(
        "Collateral utxo R5 is not a proveDlog: its coinbase cannot be requested")
    }

    val rewards = collat.registers(2).getValue.asInstanceOf[(Long, Coll[(Coll[Byte], Long)])]
    val litBlockReward = rewards._1
    val founderSplit = rewards._2.toArray.map(p => (p._1.toArray, p._2)).toSeq
    val rollupHash = collat.parseReg[Coll[Byte]](4).toArray

    // LIT identity comes off the box rather than off a constant, so a box minted under a different
    // LIT id is carried faithfully instead of being rewritten into an id it never held.
    val litId = collat.tokens.lift(1).map(_.id).getOrElse(LFSMHelpers.LIT_ID)
    val heldLIT = collat.tokens.lift(1).map(_.amount).getOrElse(0L)
    val privRewards = founderSplit.map(_._2).sum
    val permitChange = heldLIT - (litBlockReward + privRewards)
    val finderLIT = litBlockReward / EmissionSchedule.FINDER_DIVISOR
    val poolLIT = litBlockReward - finderLIT

    // The collateral contract compares this against the holding box's hashed prop bytes, so a
    // mismatch is a guaranteed rejection. Fail here instead, and the builder draws another box.
    if (!rollupHash.sameElements(c.holding.hashedPropBytes))
      throw new CollateralNotFoundException(s"collateral box ${collat.id} names rollup holding " +
        s"contract ${hex(rollupHash)}, which this build does not produce")

    // The holding box takes whatever these did not, so they are sized first. Founder boxes and the
    // permit return carry a contract-imposed 1e6 floor; the other two only clear the consensus
    // per-byte minimum. Their total must stay under `feeValue`, pinned by the enforcer at 0.005 ERG.
    val founderBoxes = founderSplit.map { case (hash, amount) =>
      val recipient = Seq(LFSMHelpers.FOUNDER_1, LFSMHelpers.FOUNDER_2, LFSMHelpers.FOUNDER_3)
        .find(_.hashedPropBytes.sameElements(hash))
        .getOrElse(throw new CollateralNotFoundException(
          s"collateral box ${collat.id} names an unknown founder ${hex(hash)}"))
      UTXO(recipient, 1000000L, Seq(Token(litId, amount)))
    }
    val permitBox =
      if (permitChange > 0) Seq(UTXO(Contract.fromAddress(lenderAddress), 1000000L,
        Seq(Token(litId, permitChange))))
      else Seq.empty[UTXO]
    val finderBox =
      if (finderLIT > 0) Seq(UTXO(prover.contract, 100000L, Seq(Token(litId, finderLIT))))
      else Seq.empty[UTXO]
    val proofOfSpend = UTXO(c.gate, 150000L, Seq(Token(collat.tokens.head.id, 1L)),
      Seq(bytesValue(lenderEntry(lenderProp))))
      .setCreationHeight(blockHeight)

    val trailing = founderBoxes ++ permitBox ++ finderBox ++ Seq(proofOfSpend)
    val holdingValue = collat.value - trailing.map(_.value).sum

    // `holdingBox.value >= SELF.value - feeValue` is what the collateral contract checks, so
    // overspending the fee channel is another guaranteed rejection.
    if (collat.value - holdingValue > feeValue)
      throw new CollateralNotFoundException(s"genesis outputs take ${collat.value - holdingValue} " +
        s"nanoERG of a $feeValue fee channel on collateral box ${collat.id}")

    val holdingBox = UTXO(c.holding, holdingValue,
      if (poolLIT > 0) Seq(Token(litId, poolLIT)) else Seq.empty[Token],
      Seq(
        emptyTreeValue,                                   // R4 empty NISP tree
        ErgoValue.of(0),                                  // R5 miner count
        ErgoValue.of(BigInt(0).bigInteger),               // R6 total score
        ErgoValue.of(blockHeight.toLong),                 // R7 period start — must equal HEIGHT
        // R8 the POOL's key, not the lender's — the lender is repaid by the coinbase, while R8 has
        // to be the identity this rollup's NISPs will be submitted under.
        bytesValue(prover.contract.hashedPropBytes)))

    val preHeader = ctx.createPreHeader()
      .height(blockHeight)
      .minerPk(lenderAddress.getPublicKeyGE)
      .build()

    // Change must be exactly zero: with no fee output, TxBuilder has nothing to fold a
    // below-minimum change into.
    val uTx = TxBuilder(ctx)
      .setInputs(collat)
      .setOutputs((holdingBox +: trailing): _*)
      .setPreHeader(preHeader)
      .buildTx(0, lenderAddress)

    val sTx = prover.sign(uTx)
    val utxBytes = uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign

    CollateralData(sTx.getId.replace("\"", ""), sTx.toJson(false, false), pkString, utxBytes,
      collat.bytes, collat.id.toString, lenderAddress.toString)
  }

  private def carriesOne(b: IndexedBox, tokenId: ErgoId): Boolean =
    b.assets.headOption.exists(a => a.tokenId == tokenId.toString && a.amount == 1L)

  private def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  private def emptyTreeValue: ErgoValue[_] =
    PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default).ergoValue
}
