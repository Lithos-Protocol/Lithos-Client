package support

import lfsm.{CollateralParams, LFSMHelpers}
import node.model.{IndexedBox, NodeAsset, NodeBox, NodeRegisters}
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import transactions.ProtocolContracts
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * Emission, queue and collateral boxes as the NODE reports them.
 *
 * The counterpart to [[LDNodeFixtures]], for the same reason: the contract suites build `UTXO`s and
 * hand them to a signature check, while anything testing the collateral-market API needs a
 * `NodeBox` the mocked index can return, registers hex-encoded and the id matching what
 * `MutationConversions` recomputes.
 *
 * Built against the production `ProtocolContracts` and the real `LFSMHelpers` ids, because that is
 * what discovery filters on — `emissionTip` compares the ergoTree against the guard this build
 * compiles, so a box under a stand-in script is never found.
 */
object CollateralNodeFixtures {

  private val defaultTxId = "cd" * 32

  private def hexRegisters(values: Seq[ErgoValue[_]]): NodeRegisters =
    NodeRegisters(values.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap)

  private def bytes(v: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaByteType)

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  private def lenderSetValue(entries: Seq[Array[Byte]]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(entries.map(e => Colls.fromArray(e)).toArray),
      org.ergoplatform.appkit.ErgoType.collType(scalaByteType))

  /**
   * The node box a UTXO becomes.
   *
   * Round-tripped through `toInput` so the box id is the one the conversion recomputes rather than
   * one chosen here — `toInputUTXO` derives it from contents, transaction id and index.
   */
  private def nodeBox(ctx: BlockchainContext, utxo: UTXO, index: Int, txId: String): NodeBox = {
    val input = utxo.toInput(ctx, ErgoId.create(txId), index.toShort)
    NodeBox(
      boxId = input.id.toString,
      transactionId = txId,
      value = utxo.value,
      index = index,
      creationHeight = utxo.creationHeight.getOrElse(input.input.getCreationHeight),
      ergoTree = utxo.contract.ergoTreeHex,
      assets = utxo.tokens.map(t => NodeAsset(t.id.toString, t.amount)),
      additionalRegisters = hexRegisters(utxo.registers))
  }

  def indexed(box: NodeBox, height: Int = 500, globalIndex: Long = 1L): IndexedBox =
    IndexedBox(box = box, address = "collateral", inclusionHeight = height, globalIndex = globalIndex)

  /** The hashed prop bytes the active set is keyed by, for an address. */
  def entryOf(address: Address): Array[Byte] = ProtocolContracts.lenderEntry(address)

  /** R4 activation counter, R5 active set, R6 head, R7 tail. Tokens: NFT, queue, collateral, LIT. */
  def emissionBox(ctx: BlockchainContext,
                  currentBlock: Int = 10,
                  lenderSet: Seq[Array[Byte]] = Seq.empty,
                  head: Long = 0L,
                  tail: Long = 0L,
                  queueTokens: Long = LFSMHelpers.PROP_TOKEN_AMNT,
                  collatTokens: Long = LFSMHelpers.PROP_TOKEN_AMNT,
                  lit: Long = 825000000L * 1000000000L,
                  index: Int = 0,
                  txId: String = defaultTxId): NodeBox =
    nodeBox(ctx, UTXO(
      ProtocolContracts(ctx).guard,
      Parameters.OneErg / 100,
      Seq(Token(LFSMHelpers.EMISSION_NFT, 1L),
        Token(LFSMHelpers.QUEUE_TOKEN, queueTokens),
        Token(LFSMHelpers.COLLAT_TOKEN, collatTokens)) ++
        (if (lit > 0) Seq(Token(LFSMHelpers.LIT_ID, lit)) else Seq.empty[Token]),
      Seq(ErgoValue.of(currentBlock), lenderSetValue(lenderSet),
        ErgoValue.of(head), ErgoValue.of(tail))), index, txId)

  /**
   * R4 permit params, R5 enforcer hash, R6 extension hash, R7 rollup hash.
   *
   * The script is a stand-in: `configBox` discovery filters on the NFT and a register count, never
   * on the tree, and the emission config contract is not one `ProtocolContracts` compiles.
   */
  def configBox(ctx: BlockchainContext,
                params: Array[Long] = LFSMHelpers.PERMIT_PARAMS,
                index: Int = 0,
                txId: String = "ce" * 32): NodeBox = {
    val c = ProtocolContracts(ctx)
    nodeBox(ctx, UTXO(
      Contract.SIGMA_TRUE,
      Parameters.MinFee,
      Seq(Token(LFSMHelpers.EMCONFIG_NFT, 1L)),
      Seq(longs(params),
        bytes(c.enforcer.hashedValueBytes),
        bytes(Contract.SIGMA_TRUE.hashedValueBytes),
        bytes(Contract.SIGMA_TRUE.hashedPropBytes))), index, txId)
  }

  /** R4 dust budget, R5 lenderPk, R6 extension flag, R7 queue position. */
  def queueBox(ctx: BlockchainContext,
               lender: Address,
               position: Long,
               permit: Long,
               index: Int = 0,
               txId: String = "c1" * 32): NodeBox =
    nodeBox(ctx, UTXO(
      ProtocolContracts(ctx).gate,
      CollateralParams.PRINCIPAL_FLOOR,
      Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L)) ++
        (if (permit > 0) Seq(Token(LFSMHelpers.LIT_ID, permit)) else Seq.empty[Token]),
      Seq(ErgoValue.of(CollateralParams.DUST_BUDGET),
        ErgoValue.of(Contract.fromAddress(lender).sigmaBoolean.get),
        ErgoValue.of(CollateralParams.EXTENSION_FLAG),
        ErgoValue.of(position))), index, txId)

  /**
   * A live collateral box: five registers, the collateral token, and NOT at the gate — which is
   * exactly how `liveBoxes` tells it apart from a proof of spend carrying the same token.
   */
  def collateralBox(ctx: BlockchainContext,
                    lender: Address,
                    carriedLit: Long,
                    createdAt: Int = 400,
                    index: Int = 0,
                    txId: String = "c2" * 32,
                    feeValue: Long = CollateralParams.DUST_BUDGET,
                    value: Long = CollateralParams.PRINCIPAL_FLOOR): NodeBox =
    nodeBox(ctx, UTXO(
      ProtocolContracts(ctx).collateral,
      value,
      Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)) ++
        (if (carriedLit > 0) Seq(Token(LFSMHelpers.LIT_ID, carriedLit)) else Seq.empty[Token]),
      Seq(ErgoValue.of(feeValue),
        ErgoValue.of(Contract.fromAddress(lender).sigmaBoolean.get),
        ErgoValue.pairOf(ErgoValue.of(0L),
          ErgoValue.of(Colls.fromArray(Array.empty[(sigma.Coll[Byte], Long)]),
            org.ergoplatform.appkit.ErgoType.pairType(
              org.ergoplatform.appkit.ErgoType.collType(scalaByteType), scalaLongType))),
        ErgoValue.of(CollateralParams.EXTENSION_FLAG),
        bytes(Contract.SIGMA_TRUE.hashedPropBytes))
    ).setCreationHeight(createdAt), index, txId)

  /**
   * A proof of spend: the collateral token, at the gate, ONE register naming the retired identity.
   *
   * This is the box that keeps a lender key out of `freeLenderKeys` after its collateral box has
   * been consumed, and the reason a key can be spoken for with no box of ours behind it.
   */
  def proofOfSpendBox(ctx: BlockchainContext,
                      retiring: Array[Byte],
                      createdAt: Int = 480,
                      index: Int = 0,
                      txId: String = "c3" * 32): NodeBox =
    nodeBox(ctx, UTXO(
      ProtocolContracts(ctx).gate,
      Parameters.MinFee,
      Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)),
      Seq(bytes(retiring))).setCreationHeight(createdAt), index, txId)
}
