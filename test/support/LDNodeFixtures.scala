package support

import lithosdex.LDHelpers
import node.model.{IndexedBox, NodeAsset, NodeBox, NodeRegisters}
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import transactions.dex.DexContracts
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * LithosDex boxes as the NODE reports them, rather than as a transaction builder holds them.
 *
 * The contract suites build `UTXO`s and hand them straight to a signature check. Anything testing
 * `LDBoxes` or the API has the opposite problem: it needs a `NodeBox` the mocked index can return,
 * with the registers hex-encoded and the ids matching what the round trip through
 * `MutationConversions` will recompute.
 *
 * Built against the production `DexContracts` and the real `LDHelpers` ids, because that is what
 * discovery filters on — a fixture under the contract suites' own placeholder ids would never be
 * found at all.
 */
object LDNodeFixtures {

  private val txId = "ab" * 32

  private def hexRegisters(values: Seq[ErgoValue[_]]): NodeRegisters =
    NodeRegisters(values.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap)

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  private def bigInt(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  private def bytes(v: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaByteType)

  /**
   * The node box a UTXO becomes.
   *
   * Round-tripped through `toInput` so the box id is the one the conversion will recompute rather
   * than one chosen here — `toInputUTXO` derives it from contents, transaction id and index, so a
   * made-up id does not survive.
   */
  private def nodeBox(ctx: BlockchainContext, utxo: UTXO, index: Int): NodeBox = {
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
    IndexedBox(box = box, address = "ld", inclusionHeight = height, globalIndex = globalIndex)

  /** R4 liquidity, R5 feeParams, R6 pending, R7 accX, R8 accY. Tokens: NFT, tokenY, provision. */
  def poolBox(ctx: BlockchainContext,
              reservesX: Long,
              reservesY: Long,
              pendingX: Long = 0L,
              pendingY: Long = 0L,
              supply: Long = LDHelpers.GENESIS_SUPPLY,
              provTokens: Long = 1000000L,
              accX: BigInt = BigInt(0),
              accY: BigInt = BigInt(0),
              feeParams: Array[Long] = LDHelpers.GENESIS_FEE_PARAMS,
              index: Int = 0): NodeBox = {
    val n = ctx.getNetworkType
    nodeBox(ctx, UTXO(
      DexContracts(ctx).liquidityPool,
      reservesX + pendingX,
      Seq(
        Token(LDHelpers.getPoolNFT(n), 1L),
        Token(LDHelpers.getTokenY(n), reservesY + pendingY),
        Token(LDHelpers.getProvToken(n), provTokens)),
      Seq(
        ErgoValue.of(LDHelpers.LOCKED_LP - supply),
        longs(feeParams),
        longs(Array(pendingX, pendingY)),
        bigInt(accX),
        bigInt(accY))), index)
  }

  /** R4 accX, R5 accY. Tokens: vault NFT, and tokenY once a flush has carried any. */
  def vaultBox(ctx: BlockchainContext,
               balanceX: Long,
               balanceY: Long = 0L,
               accX: BigInt = BigInt(0),
               accY: BigInt = BigInt(0),
               index: Int = 1): NodeBox = {
    val n = ctx.getNetworkType
    nodeBox(ctx, UTXO(
      DexContracts(ctx).feeVault,
      balanceX,
      Seq(Token(LDHelpers.getVaultNFT(n), 1L)) ++
        (if (balanceY > 0) Seq(Token(LDHelpers.getTokenY(n), balanceY)) else Seq.empty[Token]),
      Seq(bigInt(accX), bigInt(accY))), index)
  }

  /** R4 entryX, R5 entryY, R6 ownerNFT, R7 shares. One provision token, under the guard. */
  def provisionBox(ctx: BlockchainContext,
                   shares: Long,
                   ownerNFT: ErgoId,
                   entryX: BigInt = BigInt(0),
                   entryY: BigInt = BigInt(0),
                   value: Long = LDHelpers.PROVISION_MIN,
                   contract: Contract = null,
                   index: Int = 2): NodeBox =
    nodeBox(ctx, UTXO(
      if (contract == null) DexContracts(ctx).provisionGuard else contract,
      value,
      Seq(Token(LDHelpers.getProvToken(ctx.getNetworkType), 1L)),
      Seq(bigInt(entryX), bigInt(entryY), bytes(ownerNFT.getBytes), ErgoValue.of(shares))), index)
}
