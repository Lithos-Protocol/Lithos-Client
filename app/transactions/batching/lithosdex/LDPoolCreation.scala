package transactions.batching.lithosdex

import lithosdex.LDGenesisBuilder.{GENESIS_PROVISION_SHARES, N2TSeed, T2TSeed}
import lithosdex._
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.{Eip27Adjustment, InputUTXO, Token}

import scala.collection.JavaConverters._

/**
 * Creating a LithosDex pool from this wallet, ERG/token or token/token, as one chain of four transactions:
 *
 * {{{
 *   POOL_NFT   IN  wallet...                     OUT pool NFT, change, fee
 *   VAULT_NFT  IN  change                        OUT vault NFT, change, fee
 *   OWNER_NFT  IN  change                        OUT owner NFT, change, fee
 *   GENESIS    IN  pool NFT, vault NFT, change   OUT pool, vault, provision, [change], fee
 * }}}
 *
 * Genesis puts all of the creator's liquidity in the pool at their own price, and creates their provision for
 * the whole genesis supply but MIN_SUPPLY, which no provision may ever represent and so is locked for good.
 * The owner NFT stays in this wallet. Each transaction is signed before the next is built on its outputs.
 * Nothing here reads the node or broadcasts.
 */
object LDPoolCreation {

  /** Network fee of each transaction in the chain. */
  final val TX_FEE: Long = Parameters.MinFee

  /**
   * One creation, before any box exists.
   *
   * @param tokenX  None for an ERG/token pool
   * @param amountX the pool's reserve of X at genesis; nanoERG for an ERG/token pool
   */
  final case class Plan(tokenX: Option[ErgoId], tokenY: ErgoId, amountX: Long, amountY: Long, feeParams: Seq[Long]) {
    require(amountX > 0 && amountY > 0, "both amounts must be positive")
    require(tokenX.nonEmpty || amountX >= LDHelpers.MIN_RENT, s"an ERG reserve must be at least ${LDHelpers.MIN_RENT}")
    require(LDGenesisBuilder.feesBounded(feeParams), s"fee numerators out of bounds: $feeParams")

    def n2t: Boolean = tokenX.isEmpty

    /** nanoERG in the pool box: the ERG reserve, or a token/token pool's storage. */
    def poolValue: Long = if (n2t) amountX else LDHelpers.T2T_POOL_MIN_VALUE

    def vaultValue: Long = if (n2t) LDHelpers.VAULT_MIN else LDHelpers.T2T_VAULT_MIN_VALUE

    def networkFee: Long = 4 * TX_FEE

    /** nanoERG that leaves the wallet: the pool, the vault, the provision box and the fees. */
    def ergRequired: Long =
      Math.addExact(Math.addExact(poolValue, vaultValue), LDHelpers.PROVISION_MIN + networkFee)

    /** What the wallet is asked for: that, the owner NFT's box, and room for genesis's change. */
    def fundingValue: Long = Math.addExact(ergRequired, LDGenesisBuilder.NFT_BOX_VALUE + Parameters.MinFee)

    /** Tokens that leave the wallet. A token/token vault takes one more of each. */
    def tokens: Seq[Token] = tokenX match {
      case None => Seq(Token(tokenY, amountY))
      case Some(x) => Seq(Token(x, Math.addExact(amountX, LDHelpers.T2T_VAULT_SEED)),
        Token(tokenY, Math.addExact(amountY, LDHelpers.T2T_VAULT_SEED)))
    }

    /** nanoERG nobody can take back: the vault, and a token/token pool's storage. */
    def lockedErg: Long = if (n2t) vaultValue else poolValue + vaultValue

    /** Tokens nobody can take back beyond the locked supply's share: a token/token vault's one of each. */
    def lockedTokens: Seq[Token] = tokenX match {
      case None => Seq.empty
      case Some(x) => Seq(Token(x, LDHelpers.T2T_VAULT_SEED), Token(tokenY, LDHelpers.T2T_VAULT_SEED))
    }
  }

  /**
   * One transaction of the chain.
   *
   * @param chained the wallet boxes it spends that an earlier transaction creates; none for the first
   */
  final case class Link(step: String, tx: SignedTransaction, chained: Seq[InputUTXO])

  /** @param provision the creator's provision box genesis creates */
  final case class Chain(links: Seq[Link], poolNft: ErgoId, vaultNft: ErgoId, provToken: ErgoId, ownerNft: ErgoId,
                         provision: InputUTXO) {

    /** Every box one transaction creates for a later one, each to be held for the chain until it is spent. */
    def chained: Seq[InputUTXO] = links.flatMap(_.chained)
  }

  final val PoolNftStep = "POOL_NFT"
  final val VaultNftStep = "VAULT_NFT"
  final val OwnerNftStep = "OWNER_NFT"
  final val GenesisStep = "GENESIS"

  /**
   * Builds and signs the whole chain from `funding`, which must cover [[Plan.fundingValue]] and
   * [[Plan.tokens]]. The genesis is checked by the same checker the pool index uses.
   */
  def chain(ctx: BlockchainContext, wallet: NodeWallet, plan: Plan, funding: Seq[InputUTXO]): Chain = {
    val pair = plan.tokenX.fold("ERG")(_.toString) + "/" + plan.tokenY
    def sign(tx: UnsignedTransaction): SignedTransaction = {
      Eip27Adjustment.validate(tx, ctx.getNetworkType)
      wallet.sign(tx)
    }

    val poolMint = sign(LDGenesisBuilder.mintNft(ctx, funding, wallet.contract, "LD-POOL",
      s"LithosDex pool NFT, $pair", wallet.p2pk))
    val poolNftBox = output(poolMint, 0)
    val afterPool = changeOf(wallet, poolMint, planned = 1)

    val vaultMint = sign(LDGenesisBuilder.mintNft(ctx, Seq(afterPool), wallet.contract, "LD-VAULT",
      s"LithosDex vault NFT, $pair", wallet.p2pk))
    val vaultNftBox = output(vaultMint, 0)
    val afterVault = changeOf(wallet, vaultMint, planned = 1)

    // The owner NFT takes the id of its mint's first input, and the provision token that of the pool NFT box
    val (poolNft, ownerNft, provToken) = (poolNftBox.tokens.head.id, afterVault.id, poolNftBox.id)
    val ownerMint = sign(LDGenesisBuilder.mintNft(ctx, Seq(afterVault), wallet.contract,
      LithosDexTransactions.provisionNftName(ownerNft), ownerDescription(ctx, plan, poolNft, provToken, ownerNft),
      wallet.p2pk))
    val afterOwner = changeOf(wallet, ownerMint, planned = 1)

    val owners = Map(ownerNft.toString -> 1L)
    val (genesis, problems) = plan.tokenX match {
      case None =>
        val (tx, _) = LDGenesisBuilder.n2tGenesis(ctx, poolNftBox, vaultNftBox, Seq(afterOwner),
          N2TSeed(plan.tokenY, plan.amountX, plan.amountY, plan.feeParams), wallet.p2pk, Some(ownerNft))
        val signed = sign(tx)
        (signed, LDGenesis.check(ctx.getNetworkType, output(signed, 0), output(signed, 1),
          LDGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY, owners), Seq(output(signed, 2))).problems)
      case Some(x) =>
        val (tx, _) = LDGenesisBuilder.t2tGenesis(ctx, poolNftBox, vaultNftBox, Seq(afterOwner),
          T2TSeed(x, plan.tokenY, plan.amountX, plan.amountY, plan.feeParams), wallet.p2pk, Some(ownerNft))
        val signed = sign(tx)
        (signed, LDT2TGenesis.check(output(signed, 0), output(signed, 1),
          LDT2TGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY, owners), Seq(output(signed, 2))).problems)
    }
    require(problems.isEmpty, s"the genesis built is not sound: ${problems.mkString("; ")}")

    Chain(Seq(
      Link(PoolNftStep, poolMint, Seq.empty),
      Link(VaultNftStep, vaultMint, Seq(afterPool)),
      Link(OwnerNftStep, ownerMint, Seq(afterVault)),
      Link(GenesisStep, genesis, Seq(poolNftBox, vaultNftBox, afterOwner))),
      poolNft, vaultNftBox.tokens.head.id, provToken, ownerNft, output(genesis, 2))
  }

  /** What a deposit's owner NFT says of its provision, so a wallet shows both the same way. */
  private def ownerDescription(ctx: BlockchainContext, plan: Plan, poolNft: ErgoId, provToken: ErgoId,
                               ownerNft: ErgoId): String = plan.tokenX match {
    case None => LithosDexTransactions.provisionNftDescription(ctx,
      LDLiquidityPool(plan.amountX, plan.amountY, 0L, 0L, LDHelpers.GENESIS_SUPPLY, plan.feeParams.toArray, 0, 0,
        poolNft, plan.tokenY, provToken, LDHelpers.PROV_TOKEN_SUPPLY - 1L),
      ownerNft, 0, 0, GENESIS_PROVISION_SHARES, plan.amountX, plan.amountY)
    case Some(_) => s"LithosDex provision of $GENESIS_PROVISION_SHARES shares in pool $poolNft"
  }

  private def output(tx: SignedTransaction, index: Int): InputUTXO = InputUTXO(tx.getOutputsToSpend.get(index))

  /** The one wallet box after `planned` outputs, which carries everything the rest of the chain spends. */
  private def changeOf(wallet: NodeWallet, tx: SignedTransaction, planned: Int): InputUTXO = {
    val change = tx.getOutputsToSpend.asScala.toVector.drop(planned).map(InputUTXO(_))
      .filter(_.contract.ergoTreeHex == wallet.contract.ergoTreeHex)
    require(change.size == 1, s"expected one change box after $planned outputs, found ${change.size}")
    change.head
  }
}
