package lithosdex

import lithosdex.contracts.{LDContracts, LDT2TContracts}
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoValue, Parameters, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * Builds a new LithosDex pool, ERG/token or token/token, in three transactions that may be chained:
 *
 *  1. [[mintNft]] the pool NFT into a box of the creator's.
 *  2. [[mintNft]] the vault NFT the same way.
 *  3. [[n2tGenesis]] or [[t2tGenesis]]: spends both NFT boxes, pool NFT first, so the provision token takes
 *     that box's id and its whole supply is minted straight into the pool box, beside the vault box.
 *
 * Every output is the shape [[LDGenesis]] or [[LDT2TGenesis]] calls sound, which their specs check. The seed
 * reserves are locked for good: genesis supply has no provision, so nobody can redeem it. Nothing here signs.
 */
object LDGenesisBuilder {

  /**
   * @param tokenY    the pool's token
   * @param reserveX  nanoERG the pool starts with, at least [[LDHelpers.MIN_RENT]]
   * @param reserveY  units of `tokenY` the pool starts with
   * @param feeParams [dexFee, ergFee, tokenFee] over [[LDHelpers.FEE_DENOM]]
   */
  final case class N2TSeed(tokenY: ErgoId, reserveX: Long, reserveY: Long,
                           feeParams: Seq[Long] = LDHelpers.GENESIS_FEE_PARAMS.toSeq)

  /**
   * @param reserveX units of `tokenX` the pool starts with; the vault takes one more as its seed entry
   * @param reserveY the same for `tokenY`
   */
  final case class T2TSeed(tokenX: ErgoId, tokenY: ErgoId, reserveX: Long, reserveY: Long,
                           feeParams: Seq[Long] = LDHelpers.GENESIS_FEE_PARAMS.toSeq)

  /** ERG each NFT box holds until genesis spends it. */
  final val NFT_BOX_VALUE: Long = Parameters.MinFee

  // ─── boxes ────────────────────────────────────────────────────────────────

  /** One unit of `tokenId` at `owner`, with its EIP-4 name, description and zero decimals. */
  def nftBox(owner: Contract, tokenId: ErgoId, name: String, description: String): UTXO =
    UTXO(owner, NFT_BOX_VALUE, Seq(Token(tokenId, 1L)),
      Seq(bytes(name.getBytes(UTF_8)), bytes(description.getBytes(UTF_8)), bytes("0".getBytes(UTF_8))))

  /** R4 to R8 of a pool at genesis: the whole issued supply, its fees, nothing pending, zero accumulators. */
  def poolRegisters(feeParams: Seq[Long]): Seq[ErgoValue[_]] = Seq(
    ErgoValue.of(LDHelpers.LOCKED_LP - LDHelpers.GENESIS_SUPPLY),
    longs(feeParams), longs(Seq(0L, 0L)), bigZero, bigZero)

  def n2tPool(contracts: LDContracts, seed: N2TSeed): UTXO =
    UTXO(contracts.liquidityPool, seed.reserveX,
      Seq(Token(contracts.poolNFT, 1L), Token(seed.tokenY, seed.reserveY),
        Token(contracts.provToken, LDHelpers.PROV_TOKEN_SUPPLY)),
      poolRegisters(seed.feeParams))

  /** An ERG/token vault holds only its NFT until a flush brings token fees. */
  def n2tVault(contracts: LDContracts): UTXO =
    UTXO(contracts.feeVault, LDHelpers.VAULT_MIN, Seq(Token(contracts.vaultNFT, 1L)), Seq(bigZero, bigZero))

  /** A token/token pool's ERG is storage only, sized to survive two collections. */
  def t2tPool(contracts: LDT2TContracts, seed: T2TSeed): UTXO =
    UTXO(contracts.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
      Seq(Token(contracts.poolNFT, 1L), Token(seed.tokenX, seed.reserveX), Token(seed.tokenY, seed.reserveY),
        Token(contracts.provToken, LDHelpers.PROV_TOKEN_SUPPLY)),
      poolRegisters(seed.feeParams))

  /** A token/token vault starts with one unit of each token, so a payout can never empty an entry. */
  def t2tVault(contracts: LDT2TContracts, seed: T2TSeed): UTXO =
    UTXO(contracts.feeVault, LDHelpers.T2T_VAULT_MIN_VALUE,
      Seq(Token(contracts.vaultNFT, 1L), Token(seed.tokenX, LDHelpers.T2T_VAULT_SEED),
        Token(seed.tokenY, LDHelpers.T2T_VAULT_SEED)),
      Seq(bigZero, bigZero))

  // ─── transactions ────────────────────────────────────────────────────────

  /** Mints an NFT whose id is `funding.head`'s box id into a box at `owner`. */
  def mintNft(ctx: BlockchainContext, funding: Seq[InputUTXO], owner: Contract, name: String,
              description: String, change: Address): UnsignedTransaction = {
    require(funding.nonEmpty, "minting needs at least one input")
    TxBuilder(ctx).setInputs(funding: _*)
      .setOutputs(nftBox(owner, funding.head.id, name, description))
      .buildTx(Parameters.MinFee, change)
  }

  /** The ERG/token genesis, and the deployment it creates. */
  def n2tGenesis(ctx: BlockchainContext, poolNft: InputUTXO, vaultNft: InputUTXO, funding: Seq[InputUTXO],
                 seed: N2TSeed, change: Address): (UnsignedTransaction, LDContracts) = {
    require(seed.reserveX >= LDHelpers.MIN_RENT, s"reserveX must be at least ${LDHelpers.MIN_RENT} nanoERG")
    require(seed.reserveY > 0L, "reserveY must be positive")
    require(GenesisReads.feesBounded(seed.feeParams), s"fee numerators out of bounds: ${seed.feeParams}")
    val contracts = LDContracts(nftOf(poolNft, "pool"), nftOf(vaultNft, "vault"), poolNft.id, ctx.getNetworkType)
    require(Set(contracts.poolNFT, contracts.vaultNFT, seed.tokenY).size == 3,
      "the pool's token must differ from both NFTs")
    val tx = TxBuilder(ctx).setInputs(poolNft +: vaultNft +: funding: _*)
      .setOutputs(n2tPool(contracts, seed), n2tVault(contracts))
      .buildTx(Parameters.MinFee, change)
    (tx, contracts)
  }

  /** The token/token genesis, and the deployment it creates. */
  def t2tGenesis(ctx: BlockchainContext, poolNft: InputUTXO, vaultNft: InputUTXO, funding: Seq[InputUTXO],
                 seed: T2TSeed, change: Address): (UnsignedTransaction, LDT2TContracts) = {
    require(seed.reserveX > 0L && seed.reserveY > 0L, "both reserves must be positive")
    require(GenesisReads.feesBounded(seed.feeParams), s"fee numerators out of bounds: ${seed.feeParams}")
    val contracts = LDT2TContracts(nftOf(poolNft, "pool"), nftOf(vaultNft, "vault"), poolNft.id)
    require(Set(contracts.poolNFT, contracts.vaultNFT, seed.tokenX, seed.tokenY).size == 4,
      "the pool's two tokens must differ from each other and from both NFTs")
    val tx = TxBuilder(ctx).setInputs(poolNft +: vaultNft +: funding: _*)
      .setOutputs(t2tPool(contracts, seed), t2tVault(contracts, seed))
      .buildTx(Parameters.MinFee, change)
    (tx, contracts)
  }

  /** The single unit an NFT box from [[mintNft]] holds. */
  private def nftOf(box: InputUTXO, which: String): ErgoId = {
    require(box.tokens.size == 1 && box.tokens.head.amount == 1L, s"the $which NFT box must hold exactly one unit of one token")
    box.tokens.head.id
  }

  private def bytes(b: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(b), scalaByteType)
  private def longs(v: Seq[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v.toArray), scalaLongType)
  private def bigZero: ErgoValue[_] = ErgoValue.of(java.math.BigInteger.ZERO)
}
