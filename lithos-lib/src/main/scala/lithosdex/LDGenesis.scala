package lithosdex

import lithosdex.GenesisReads._
import lithosdex.contracts.LDContracts
import org.ergoplatform.appkit.NetworkType
import work.lithos.mutations.InputUTXO

/**
 * Checks the genesis of an ERG/token pool, the counterpart of [[LDT2TGenesis]].
 *
 * Every ERG/token deployment runs the same pinned contracts with its own ids, and those contracts take the
 * same facts about genesis on trust: provision tokens minted outside the pool forge provisions that redeem
 * its reserves, and a second pool or vault NFT lets a box posing as either take orders or fees.
 *
 * Unlike the token/token check, the pool and vault need not be created together: the live ERG:LIT pool's
 * vault came seven blocks after it. Each box is judged as created instead, so the caller passes the first
 * box at the pool script holding the provision token, and the first box at the vault script holding the
 * vault NFT, with what the node's token index reports was minted of the pool's three own tokens. Provisions
 * the pool's genesis created for its creator are passed too, with what was minted of each one's owner NFT.
 */
object LDGenesis {

  /**
   * Emission amounts the node reports for the deployment's own tokens.
   *
   * @param owners each genesis provision's owner NFT id, to what was minted of it
   */
  case class Emissions(poolNFT: Long, vaultNFT: Long, provToken: Long, owners: Map[String, Long] = Map.empty)

  /**
   * @param contracts the deployment the boxes name, when their layout was readable at all
   * @param problems  anything that makes the pool unsafe to use; empty means the genesis is sound
   * @param warnings  anything risky that is not exploitable
   */
  case class Report(contracts: Option[LDContracts], problems: Seq[String], warnings: Seq[String]) {
    def sound: Boolean = problems.isEmpty
  }

  /** @param provisions every output of the pool's genesis transaction other than it that holds the provision token */
  def check(networkType: NetworkType, pool: InputUTXO, vault: InputUTXO, emissions: Emissions,
            provisions: Seq[InputUTXO] = Seq.empty): Report = {
    // The vault holds only its NFT until the first flush brings token fees into it
    if (pool.tokens.size != 3 || vault.tokens.size != 1)
      return Report(None, Seq(s"pool holds ${pool.tokens.size} tokens and vault ${vault.tokens.size}; " +
        "expected 3 and 1"), Seq.empty[String])

    val poolNFT   = pool.tokens.head
    val tokenY    = pool.tokens(1)
    val provToken = pool.tokens(2)
    val vaultNFT  = vault.tokens.head
    val contracts = LDContracts(poolNFT.id, vaultNFT.id, provToken.id, networkType)

    val D = LDHelpers.FEE_DENOM
    val liquidity = longAt(pool, 0)
    val feeParams = longsAt(pool, 1)

    val problems = Seq(
      sameScript(pool.contract, contracts.liquidityPool) ->
        "pool is not at LD_LiquidityPool for its own ids",
      sameScript(vault.contract, contracts.feeVault) ->
        "vault is not at LD_FeeVault for its own ids",
      (poolNFT.amount == 1L && emissions.poolNFT == 1L) ->
        s"pool NFT minted ${emissions.poolNFT}, pool holds ${poolNFT.amount}; both must be 1",
      (vaultNFT.amount == 1L && emissions.vaultNFT == 1L) ->
        s"vault NFT minted ${emissions.vaultNFT}, vault holds ${vaultNFT.amount}; both must be 1",
      (provToken.amount + held(provisions, provToken.id) == emissions.provToken) ->
        (s"provision token minted ${emissions.provToken}, pool holds ${provToken.amount} and genesis provisions " +
          s"${held(provisions, provToken.id)}; together they must hold all of it"),
      (Set(poolNFT.id, vaultNFT.id, provToken.id, tokenY.id).size == 4) ->
        "the pool's token, its NFTs and its provision token are not four distinct tokens",
      liquidity.exists(l => l >= 0L && l <= LDHelpers.LOCKED_LP - LDHelpers.MIN_SUPPLY) ->
        s"R4 must be a Long leaving a supply of at least ${LDHelpers.MIN_SUPPLY}, found ${liquidity.getOrElse("none")}",
      feeParams.exists(feesBounded) ->
        s"R5 must be [dexFee in (0, $D], ergFee and tokenFee in [0, $D)], found ${feeParams.map(_.mkString("[", ", ", "]")).getOrElse("none")}",
      longsAt(pool, 2).contains(Seq(0L, 0L)) ->
        "R6 must be [0, 0]: nothing pending at genesis",
      (bigIntAt(pool, 3).contains(BigInt(0)) && bigIntAt(pool, 4).contains(BigInt(0))) ->
        "pool accumulators R7 and R8 must both be BigInt 0",
      (bigIntAt(vault, 0).contains(BigInt(0)) && bigIntAt(vault, 1).contains(BigInt(0))) ->
        "vault accumulators R4 and R5 must both be BigInt 0",
      (pool.registers.size == 5 && vault.registers.size == 2) ->
        s"pool must carry exactly R4 to R8 and vault R4 and R5, found ${pool.registers.size} and ${vault.registers.size}"
    ).collect { case (false, problem) => problem } ++
      provisionProblems(pool, provisions, contracts.provisionGuard, provToken.id, emissions.owners,
        Set(poolNFT.id, vaultNFT.id, provToken.id, tokenY.id), liquidity.map(LDHelpers.LOCKED_LP - _))

    val warnings = Seq(
      (pool.value >= LDHelpers.MIN_RENT) ->
        s"pool holds ${pool.value} nanoERG, under the ${LDHelpers.MIN_RENT} every spend must leave in it",
      (vault.value >= LDHelpers.VAULT_MIN) ->
        s"vault holds ${vault.value} nanoERG, under the ${LDHelpers.VAULT_MIN} every claim keeps back"
    ).collect { case (false, warning) => warning } ++ provisionWarnings(provisions)

    Report(Some(contracts), problems, warnings)
  }
}
