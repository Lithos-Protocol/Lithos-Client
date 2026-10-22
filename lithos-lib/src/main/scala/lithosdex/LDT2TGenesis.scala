package lithosdex

import lithosdex.contracts.LDT2TContracts
import sigma.Coll
import sigma.data.CBigInt
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * Checks the genesis of a user-created token/token pool.
 *
 * The contracts take several facts about genesis on trust, because none can be checked on chain. A pool
 * that breaks one can be drained: provision tokens minted outside the pool forge provisions that redeem
 * its reserves, and a second pool NFT lets a box posing as the pool fill orders at any price. This says
 * whether a deployment's genesis holds every one of them.
 *
 * It needs the pool as created, which is the box the provision token was minted into, the vault box
 * created in the same transaction, and what the node's token index reports was minted of the pool's own
 * three tokens. Every id and contract is derived from those two boxes.
 */
object LDT2TGenesis {

  /** Emission amounts the node reports for the deployment's own tokens. */
  case class Emissions(poolNFT: Long, vaultNFT: Long, provToken: Long)

  /**
   * @param contracts the deployment the boxes name, when their layout was readable at all
   * @param problems  anything that makes the pool unsafe to use; empty means the genesis is sound
   * @param warnings  anything risky that is not exploitable, such as too little ERG for storage rent
   */
  case class Report(contracts: Option[LDT2TContracts], problems: Seq[String], warnings: Seq[String]) {
    def sound: Boolean = problems.isEmpty
  }

  /**
   * @param pool      the pool box as created: the box the provision token was minted into
   * @param vault     the vault box created in the same transaction
   * @param emissions what the node reports was minted of the pool NFT, vault NFT and provision token
   */
  def check(pool: InputUTXO, vault: InputUTXO, emissions: Emissions): Report = {
    // Every id and the contracts themselves are read from the token layout, so nothing else can be
    // judged without it
    if (pool.tokens.size != 4 || vault.tokens.size != 3)
      return Report(None, Seq(s"pool holds ${pool.tokens.size} tokens and vault ${vault.tokens.size}; " +
        "expected 4 and 3"), Seq.empty[String])

    val poolNFT   = pool.tokens.head
    val tokenX    = pool.tokens(1)
    val tokenY    = pool.tokens(2)
    val provToken = pool.tokens(3)
    val vaultNFT  = vault.tokens.head
    val contracts = LDT2TContracts(poolNFT.id, vaultNFT.id, provToken.id)

    val D = LDHelpers.FEE_DENOM
    val liquidity = longAt(pool, 0)
    val feeParams = longsAt(pool, 1)

    val problems = Seq(
      sameScript(pool.contract, contracts.liquidityPool) ->
        "pool is not at LD_T2T_LiquidityPool for its own ids",
      sameScript(vault.contract, contracts.feeVault) ->
        "vault is not at LD_T2T_FeeVault for its own ids",
      (pool.input.getTransactionId == vault.input.getTransactionId) ->
        "pool and vault were not created in the same transaction",
      (poolNFT.amount == 1L && emissions.poolNFT == 1L) ->
        s"pool NFT minted ${emissions.poolNFT}, pool holds ${poolNFT.amount}; both must be 1",
      (vaultNFT.amount == 1L && emissions.vaultNFT == 1L) ->
        s"vault NFT minted ${emissions.vaultNFT}, vault holds ${vaultNFT.amount}; both must be 1",
      (provToken.amount == emissions.provToken) ->
        s"provision token minted ${emissions.provToken}, pool holds ${provToken.amount}; the pool must hold all of it",
      (Set(poolNFT.id, vaultNFT.id, provToken.id, tokenX.id, tokenY.id).size == 5) ->
        "the pool's two tokens, its NFTs and its provision token are not five distinct tokens",
      (vault.tokens(1).id == tokenX.id && vault.tokens(2).id == tokenY.id) ->
        "vault does not hold token X and token Y at indices 1 and 2",
      liquidity.exists(l => l >= 0L && l <= LDHelpers.LOCKED_LP - LDHelpers.MIN_SUPPLY) ->
        s"R4 must be a Long leaving a supply of at least ${LDHelpers.MIN_SUPPLY}, found ${liquidity.getOrElse("none")}",
      feeParams.exists(f => f.size == 3 && f(0) > 0 && f(0) <= D && f.tail.forall(s => s >= 0 && s < D)) ->
        s"R5 must be [dexFee in (0, $D], xFee and yFee in [0, $D)], found ${feeParams.map(_.mkString("[", ", ", "]")).getOrElse("none")}",
      longsAt(pool, 2).contains(Seq(0L, 0L)) ->
        "R6 must be [0, 0]: nothing pending at genesis",
      (bigIntAt(pool, 3).contains(BigInt(0)) && bigIntAt(pool, 4).contains(BigInt(0))) ->
        "pool accumulators R7 and R8 must both be BigInt 0",
      (bigIntAt(vault, 0).contains(BigInt(0)) && bigIntAt(vault, 1).contains(BigInt(0))) ->
        "vault accumulators R4 and R5 must both be BigInt 0",
      (pool.registers.size == 5 && vault.registers.size == 2) ->
        s"pool must carry exactly R4 to R8 and vault R4 and R5, found ${pool.registers.size} and ${vault.registers.size}"
    ).collect { case (false, problem) => problem }

    val warnings = Seq(
      (pool.value >= LDHelpers.T2T_POOL_MIN_VALUE) ->
        s"pool holds ${pool.value} nanoERG, under the ${LDHelpers.T2T_POOL_MIN_VALUE} that survives a storage-fee collection",
      (vault.value >= LDHelpers.T2T_VAULT_MIN_VALUE) ->
        s"vault holds ${vault.value} nanoERG, under the ${LDHelpers.T2T_VAULT_MIN_VALUE} that survives a storage-fee collection"
    ).collect { case (false, warning) => warning }

    Report(Some(contracts), problems, warnings)
  }

  private def sameScript(a: Contract, b: Contract): Boolean = a.propBytes.sameElements(b.propBytes)

  // Registers are read by matching their value's type, so a register of the wrong type reads as absent
  private def longAt(box: InputUTXO, i: Int): Option[Long] =
    box.registers.lift(i).map(_.getValue).collect { case v: java.lang.Long => v.longValue }

  private def longsAt(box: InputUTXO, i: Int): Option[Seq[Long]] =
    box.registers.lift(i).map(_.getValue).collect { case c: Coll[_] => c.toArray.toSeq }.flatMap { items =>
      val longs = items.collect { case v: java.lang.Long => v.longValue }
      if (longs.size == items.size) Some(longs) else None
    }

  private def bigIntAt(box: InputUTXO, i: Int): Option[BigInt] =
    box.registers.lift(i).map(_.getValue).collect { case v: CBigInt => BigInt(v.wrappedValue) }
}
