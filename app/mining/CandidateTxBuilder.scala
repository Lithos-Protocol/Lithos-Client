package mining

import configs.CandidateConfig
import lfsm.states.RollupInfoState
import lfsm.{EmissionSchedule, LFSMHelpers, RollupProtocol}
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

import scala.util.{Failure, Success, Try}

/**
 * A live collateral box and the two facts that decide whether it is drawn next.
 *
 * The age comes from the indexer's confirmed inclusion height, never from the box's declared
 * creation height, which its builder chooses and could set to anything.
 */
final case class CollateralCandidate(input: InputUTXO, inclusionHeight: Int, finderFee: Long) {
  def id: String = input.id.toString

  def age(blockHeight: Int): Int = blockHeight - inclusionHeight
}

object CandidateTxBuilder {

  /**
   * Boxes carrying the collateral token that a single load will read: `MAX_ACTIVE` live boxes, one
   * unrecycled retirement proof behind each, and the emission singleton.
   *
   * Counted in boxes rather than pages, so the batch size decides how many calls the scan costs and
   * never which boxes it reaches.
   */
  private[mining] final val MaxCollateralCarriers: Int = 2 * EmissionSchedule.MAX_ACTIVE + 1

  /**
   * Confirmed age at which a box stops competing on its bid and starts competing on age alone.
   * The active set is bounded, so draining the overdue cohort oldest-first turns it over rather
   * than starving it, and a lender who bids nothing still gets mined eventually.
   */
  final val OverdueAge: Int = 100

  /**
   * The boxes that tie for best, for the caller to draw from. Overdue boxes win outright, oldest
   * first, so no bid can hold a stale box out of the set indefinitely; among the rest the highest
   * bid wins and age breaks the tie.
   */
  def bestCandidates(candidates: Seq[CollateralCandidate], blockHeight: Int): Seq[CollateralCandidate] = {
    val overdue = candidates.filter(_.age(blockHeight) >= OverdueAge)
    if (overdue.nonEmpty) {
      val oldest = overdue.map(_.inclusionHeight).min
      val sameAge = overdue.filter(_.inclusionHeight == oldest)
      val topBid = sameAge.map(_.finderFee).max
      sameAge.filter(_.finderFee == topBid)
    } else if (candidates.isEmpty) Seq.empty[CollateralCandidate]
    else {
      val topBid = candidates.map(_.finderFee).max
      val bidding = candidates.filter(_.finderFee == topBid)
      val oldest = bidding.map(_.inclusionHeight).min
      bidding.filter(_.inclusionHeight == oldest)
    }
  }
}

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
  def loadCollateral(ctx: BlockchainContext): Seq[CollateralCandidate] = {
    val c = ProtocolContracts(ctx)
    // Hoisted out of the filters below. `Contract.ergoTreeHex` is a def that re-serialises the tree
    // and hex-encodes it on every call, and these run once per box across the whole active set.
    val gateTree = c.gate.ergoTreeHex
    val collateralTree = c.collateral.ergoTreeHex
    // Collateral-shaped but not necessarily under this build's script. Proof-of-spend boxes carry
    // the token too, and the gate-tree test is what keeps them out of the count below.
    val boxes = collateralCarriers(collateralTree)
      .filter(b => collateralShaped(b) && b.ergoTree != gateTree)

    // A box under a different collateral script is unspendable here — its gate hash, LIT id or
    // holding hash are not the ones this client builds against. Only the emission box mints the
    // collateral token and it mints into one script, so this can only mean the compiled contract
    // has drifted from the deployed one, and every Lithos block is being missed until it is fixed.
    val usable = boxes.filter(usableCollateral(_, collateralTree))
    if (usable.size < boxes.size)
      logger.error(s"${boxes.size - usable.size} of ${boxes.size} collateral boxes are NOT under the " +
        s"collateral script this build compiles (${collateralTree.take(16)}...) and were " +
        "skipped. This should be impossible: check the injected constants against the deployment")

    // A box whose fee channel does not price is unusable rather than fatal: it is one lender's box,
    // and refusing to rank it leaves collateral mining running on every other box in the set.
    usable.flatMap { box =>
      Try {
        val input = box.toInputUTXO(ctx)
        CollateralCandidate(input, box.inclusionHeight, RollupProtocol.finderFee(input.parseReg[Long](0)))
      } match {
        case Success(candidate) => Some(candidate)
        case Failure(ex) =>
          logger.warn(s"Skipping collateral box ${box.boxId}: its fee channel does not price a " +
            s"finder fee (${ex.getMessage})")
          None
      }
    }
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
    // A lender bidding for priority posts five times their offer: four go to the pool through the
    // holding box, and this one is the finder's, taken here rather than left to change so it lands
    // at this miner's own key instead of the lender's. Below the floor there is no box that can
    // legally carry it, so it stays with the pool.
    val finderFee = RollupProtocol.finderFee(feeValue)
    val finderValue =
      if (finderLIT > 0) FinderBoxValue + finderFee
      else if (finderFee >= FinderBoxValue) finderFee
      else 0L
    val finderBox =
      if (finderValue > 0) Seq(UTXO(prover.contract, finderValue,
        if (finderLIT > 0) Seq(Token(litId, finderLIT)) else Seq.empty[Token]))
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
      Seq(Token(collat.id, 1L)) ++
        (if (poolLIT > 0) Seq(Token(litId, poolLIT)) else Seq.empty[Token]),
      Seq(
        emptyTreeValue,                                   // R4 empty NISP tree
        ErgoValue.of(0),                                  // R5 miner count
        ErgoValue.of(BigInt(0).bigInteger),               // R6 total score
        // R7 period start, the rollup's own block, and an empty bond ledger. The collateral
        // contract pins all three against HEIGHT, so a package built for another height is refused.
        RollupInfoState.holding(blockHeight.toLong, blockHeight.toLong, 0L).ergoValue,
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

  /**
   * Every box carrying the collateral token, paged to exhaustion.
   *
   * Stops early once the protocol's own cap of live boxes has been seen, since nothing after that can
   * outrank what is already held.
   */
  private def collateralCarriers(collateralTree: String): Seq[IndexedBox] = {
    val batch = config.collateralPoolSize
    val carriers = Seq.newBuilder[IndexedBox]
    var paging = Paging.all(batch)
    var live = 0
    var seen = 0
    var exhausted = false

    while (!exhausted && live < EmissionSchedule.MAX_ACTIVE &&
      seen < CandidateTxBuilder.MaxCollateralCarriers) {
      val page = nodeApi.unspentBoxesByTokenId(LFSMHelpers.COLLAT_TOKEN.toString, paging,
        SortDirection.Asc, MempoolOptions.ConfirmedOnly) match {
        case Success(boxes) => boxes
        // Ranking an incomplete prefix silently picks a different lender's box. Failing the load
        // leaves the last complete set in place and the next refresh tries again.
        case Failure(ex) => throw new CollateralNotFoundException(
          s"collateral token page at offset ${paging.offset} failed, so the active set cannot be " +
            s"ranked: ${ex.getMessage}")
      }
      carriers ++= page
      live += page.count(usableCollateral(_, collateralTree))
      seen += page.size
      exhausted = page.size < batch
      paging = paging.next
    }

    // Reported rather than refused. More carriers than the protocol can produce means emission has
    // stopped recycling retirement proofs, and refusing here would stop collateral mining outright.
    if (!exhausted && live < EmissionSchedule.MAX_ACTIVE)
      logger.error(s"Stopped scanning collateral carriers at $seen boxes with only $live live " +
        "found; the ranking below is over an incomplete active set")

    carriers.result()
  }

  /** One collateral token and the five registers a live box carries; says nothing about the script. */
  private def collateralShaped(b: IndexedBox): Boolean =
    carriesOne(b, LFSMHelpers.COLLAT_TOKEN) && b.box.additionalRegisters.ordered.size >= 5

  /** The one test the ranked set is filtered by, so the scan's early exit counts what it will keep. */
  private def usableCollateral(b: IndexedBox, collateralTree: String): Boolean =
    b.ergoTree == collateralTree && collateralShaped(b)

  /** What the finder's LIT box has always been worth, and the floor a bare fee box has to clear. */
  private val FinderBoxValue: Long = 100000L

  private def carriesOne(b: IndexedBox, tokenId: ErgoId): Boolean =
    b.assets.headOption.exists(a => a.tokenId == tokenId.toString && a.amount == 1L)

  private def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  private def emptyTreeValue: ErgoValue[_] =
    PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default).ergoValue
}
