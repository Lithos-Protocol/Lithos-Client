package lfsm

import lfsm.contracts.RollupContracts
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, NetworkType, Parameters}
import org.ergoplatform.sdk.{ErgoId, JavaHelpers}
import work.lithos.mutations.{Contract, InputUTXO}

import java.math.{BigDecimal, BigInteger, RoundingMode}
import scala.util.Try

/**
 * Helpers for Lithos Finite State Machine
 */
object LFSMHelpers {
  // Rollup Params
  // Target max used in contracts, 2^256 - 1
  final val TARGET_MAX_LITHOS = BigInt("115792089237316195423570985008687907853269984665640564039457584007913129639935")

  final val HOLDING_PERIOD = 360L // 360 Blocks, or 12 hours
  final val EVAL_PERIOD    = 360L
  // TODO: Change to 60 before mainnet
  final val NISP_WINDOW    = 60 // 2 hours on mainnet (less on testnet but its ok)
  final val NISP_COEFFICIENT = 10000 // Coefficient which separates normal shares from super-shares, used in evaluation
  // NISP size envelope, mirrored in Holding_Logic and injected into FP_InvalidFormat.
  // share = [N: 4][header][txProofSize: 2][numLevels: 1][txProof][levels: 33n][yCoord: 32]
  // NISP  = [score: 8][10 shares], header 220 bytes (221 once height passes 2^21).
  // MIN = 8 + 10*(4 + 220 + 3 + 502 + 33 + 32). MAX = 8 + 10*(4 + 221 + 3 + 2174 + 528 + 32) + 1.
  final val NISP_MAX         = 29629 // Max size of NISP in bytes
  final val NISP_MIN         = 7948
  final val TX_PROOF_MIN       = 502
  // Worst honest txProof is 2074. The extra 100 bytes are the budget for a larger rollup contract,
  // which is config-replaceable; every byte of one lands here and is multiplied by ten into
  // NISP_MAX. Trimming this shrinks the largest rollup contract that can ever be deployed.
  final val TX_PROOF_MAX       = 2174
  final val TX_SIZE_MIN        = 450
  final val COLLAT_BOX_MIN     = 50
  final val NUM_LVLS_MAX      = 16

  // Refundable per-NISP bond, mirrored in Holding_Logic, Evaluation and Payout.
  // A submission posts max(MIN_ENTRY_BOND, score / BOND_DIVISOR) alongside its NISP, gets it back at
  // payout, and forfeits it to the prover if a fraud proof removes the entry. The floor is what
  // prices dictionary-cache spam; the proportional term is what stops a large claimed score from
  // being cheap to post. The floor also has to clear Ergo's 1e6 min box value, since a slash pays it
  // out as a box of its own.
  final val MIN_ENTRY_BOND     = 2000000L // 0.002 ERG
  final val BOND_DIVISOR       = 25L
  // score units per nanoERG of proportional bond

  final val COLLAT_MAX_FEE   = Parameters.MinFee * 100

  // FP_Control Params
  final val FP_TOKEN_MAINNET        = ErgoId.create("5a3f8a958178fc6e3b37aeea8fb94d8e6d33a7e4d2c7e70aa7db4e13c08a9903")
  final val FP_TOKEN_TESTNET        = ErgoId.create("a6d4fa307b654dcf31ce07e2462c1be5ca7c5dcc35c1363a0eff62d0b3b9ed37")
  final val FP_CONTROL_TESTNET      = Address.create("ShDJAh75M4bDZbCowYGqtmHi4iiBMqWJcbQRYLaxx8tZZHtj23c7qEcEvUiXYvSdnjdWE6R328rSazggEzz7UWRqXGZWc6L28bo96jMNK8NZs1bQBHAxkb9rLFW8Gf3HFQRPUm26CX8LZeqF1iJvftCYHTp2KC2LisbheejGeoXkv")

  // How long a rollup stays challengeable. MinerData_Logic spaces commitment changes by NISP_WINDOW
  // plus this, so the commitment governing any live rollup is still in one of its two slots.
  final val ROLLUP_LIFETIME = HOLDING_PERIOD + EVAL_PERIOD

  // MinerDictionary Params
  // A MinerData box is deliberately collectible for storage rent. That is safe because a registration
  // expires DATA_LIFETIME after it is made while the box cannot be collected until STORAGE_PERIOD
  // after it is created, so a collected credential names an entry no fraud proof will honour.
  final val MIN_DATA_BOX_AMNT = 1000000L // 0.001 ERG
  // How long a registration lasts, in the 365-day, 720-blocks-per-day units Ergo uses for its own
  // storage period. A miner registers again rather than renewing.
  final val DATA_LIFETIME     = 919800L // 3.5 years at 2-minute blocks
  final val STORAGE_PERIOD    = 1051200L // Ergo's own
  // The gap between a registration expiring and its box becoming collectible. An entry may only be
  // evicted this long after expiry, so eviction can never retire a registration a live rollup was
  // judged against, and a data box's creation height may be backdated at most this far.
  final val EVICT_DELAY       = STORAGE_PERIOD - DATA_LIFETIME // 131400, about six months
  // How far below the full lifetime a registration's supplied expiry may sit. The expiry is written
  // into the dictionary tree by the builder, so this band is how long a registration may wait in the
  // mempool before it has to be rebuilt.
  final val REGISTER_SLACK    = 720L // one day at 2-minute blocks
  final val MD_TOKEN_MAINNET = ErgoId.create("7f9609b232d3e2f0638d60a03a26831bf80155ed1e87a1b914e2623dfbd05518")
  final val MD_TOKEN_TESTNET = ErgoId.create("2aee4d20d743e19042b68b99045e7c482d3580fe2797c39de4235989f788021b")
  final val MD_GENESIS_HEIGHT = 526394

  // Genesis Tx: 152145747776ee9e41b61fcf153ecc7dd0818044e2956277e40f8c9ff9386e59
  // UTXO id of initial MD box
  final val MD_GENESIS_ID = "5c58ef843bda73373ff093e06f7bc48db713ccb0dd0cf3f17eb7e9bfabe409cd"

  // Lithos token & emission parameters
  final val INIT_MINT = Parameters.OneErg * 1000000000 // 1 billion LIT
  final val LIT_ID = ErgoId.create("7b728ca02a23085f1f7093e949535938c55307ab1b61e848008201c5109bd18b")
  // CONFIRMED MAINNET ID
  final val LIT_ID_MAINNET = ErgoId.create("c1980d829988229516430a47a5eca376060b6ce859616db0936e78ab25cb6de7")
  final val EMISSION_NFT = ErgoId.create("4169fcef923f8cb791f238103b900792427b79148a6ef709134d5c196212ae88")
  // TODO: Change before launch
  // Emission Config NFT
  final val EMCONFIG_NFT = ErgoId.create("fd8752a46a68340ff33805db57d0ff4d8bd1680c533c4313dc4e9cb71b260725")
  // Ids and amount of proposition tokens on Emission contract
  final val COLLAT_TOKEN = ErgoId.create("09e991239e5544a6996216440d42e6ec636aea718c8c7cbc2e54f90e832f2e1e")
  final val QUEUE_TOKEN  = ErgoId.create("0c3b6465e865278e80fba1fc903d4d390daf5d87c097936c181cd8d7b753f4dd")
  final val PROP_TOKEN_AMNT = Long.MaxValue

  final val PERMIT_FLOOR = 2000L * Parameters.OneErg
  final val PERMIT_CEIL  = 40000L * Parameters.OneErg
  final val PERMIT_SLOPE = 20 * Parameters.OneErg
  final val PERMIT_PARAMS = Array(PERMIT_FLOOR, PERMIT_CEIL, PERMIT_SLOPE)
  final val FOUNDER_1 = Contract.fromAddress(Address.create("3WwcyDX8iQjPR6H2VSGDp22SaJ3c8bo1RWWvHhZy19JXJzrxWFbu"))
  final val FOUNDER_2 = Contract.fromAddress(Address.create("3WyZJc1pWf8P2o6AT2Ewq7yb5ThBHdk8SLSNyUyoGbSBXXpY3w8F"))
  final val FOUNDER_3 = Contract.fromAddress(Address.create("3Wx1MUteyRXC15mor65vt7VTPvJLZvnJ2dzd4sczi49wvuS78vE1"))


  /**
   * Parse diff string and return its tau value
   * @param diffValue String such as "4.0G", "2.411T". Supports up to Petahash difficulty
   * @return Tau value of this difficulty
   */
  def parseDiffValueForStratum(diffValue: String): Try[BigInteger] = {
    Try {
      val pow = diffValue.last
      val num = diffValue.substring(0, diffValue.length-1).toDouble

      val powOfTen = pow match {
        case 'K' => 3
        case 'M' => 6
        case 'G' => 9
        case 'T' => 12
        case 'P' => 15
        case _ => throw new NumberFormatException("Failed to parse Power of Ten in difficulty value")
      }
      getTauFromDiffForStratum(num, powOfTen)
    }
  }

  def formatTau(tau: BigInt): String = {
    val diff = convertTauOrScore(tau).toLong
    def bestPowerOfTen(num: Long) = {
      num match {
        case p if p >= 1e15 =>
          "P" -> num.toDouble / 1e15
        case t if t >= 1e12 =>
          "T" -> num.toDouble / 1e12
        case g if g >= 1e9 =>
          "G" -> num.toDouble / 1e9
        case m if m >= 1e6 =>
          "M" -> num.toDouble / 1e6
        case k if k >= 1e3 =>
          "K" -> num.toDouble / 1e3
        case _ =>
          "" -> num.toDouble
      }
    }
    val formatInfo = bestPowerOfTen(diff)
    val decimal = formatInfo._2
    f"$decimal%.2f" + formatInfo._1
  }

  /**
   * Get Tau from difficulty, represented as (diff E powOfTen)
   * @param diff Difficulty represented as double
   * @param powOfTen Pow of Ten to use for difficulty
   * @return Tau value as BigInteger for this difficulty
   */
  def getTauFromDiffForStratum(diff: Double, powOfTen: Int): BigInteger = {
    val diffValue = BigDecimal.valueOf(diff).scaleByPowerOfTen(powOfTen)
    val targetMax = new BigDecimal(TARGET_MAX_LITHOS.bigInteger)
    val result = targetMax.divide(diffValue, 2, RoundingMode.DOWN)
    result.toBigInteger
  }

  /**
   * Converts between Tau value and Share Score value using TARGET_MAX_LITHOS
   * @param dividend Tau or Score value
   * @return If dividend was Tau, returns Score. If dividend was Score, returns Tau
   */
  def convertTauOrScore(dividend: BigInt): BigInt = {
    TARGET_MAX_LITHOS / dividend
  }

  /**
   * Creates value of UTXO based on miner's score, total score, and total value in payout box
   * @param score Miner's score, parsed from first 8 bytes of submitted NISP
   * @param totalScore Sum of all scores, from R6 of Payout Box. NOTE: Despite being a BigInt, this is a score value
   * @param totalValue Total value of mining rewards, from R7 of Payout Box
   * @return ERG value of miner's output box
   */
  def paymentFromScore(score: Long, totalScore: BigInt, totalValue: Long): Long = {
    ((BigInt(totalValue) * BigInt(score)) / totalScore).toLong
  }

  def scoreFromPayment(reward: Long, totalScore: BigInt, totalValue: Long): Long = {
    ((totalScore * BigInt(reward)) / BigInt(totalValue)).toLong
  }

  def getFPToken(ctx: BlockchainContext): ErgoId = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET => FP_TOKEN_MAINNET
      case NetworkType.TESTNET => FP_TOKEN_TESTNET
    }
  }

  def getFPToken(networkType: NetworkType): ErgoId = {
    networkType match {
      case NetworkType.MAINNET => FP_TOKEN_MAINNET
      case NetworkType.TESTNET => FP_TOKEN_TESTNET
    }
  }

  def getMDToken(networkType: NetworkType): ErgoId = {
    networkType match {
      case NetworkType.MAINNET => MD_TOKEN_MAINNET
      case NetworkType.TESTNET => MD_TOKEN_TESTNET
    }
  }

  def getMDToken(client: ErgoClient): ErgoId = {
    client.execute{
      ctx =>
        ctx.getNetworkType match {
          case NetworkType.MAINNET => MD_TOKEN_MAINNET
          case NetworkType.TESTNET => MD_TOKEN_TESTNET
        }
    }
  }
  // TODO: Change for mainnet
  def getFPControlBox(ctx: BlockchainContext): InputUTXO = {

    val fpControlBoxes = JavaHelpers.toIndexedSeq(ctx.getUnspentBoxesFor(FP_CONTROL_TESTNET, 0, 100)).map(InputUTXO(_))
    val optFPControl = fpControlBoxes.find(i => i.tokens.exists(_.id == getFPToken(ctx)))
    optFPControl match {
      case Some(fpControl) => fpControl
      case None => throw new RuntimeException("Could not find fpControl UTXO")
    }
  }

  def getLocalDataBox(ctx: BlockchainContext, dataId: ErgoId, dataContract: Contract): Try[InputUTXO] = {
    Try {
      val allDataBoxes = JavaHelpers.toIndexedSeq(ctx.getUnspentBoxesFor(dataContract.address(ctx), 0, 100)).map(InputUTXO(_))
      allDataBoxes.find(i => i.tokens.exists(_.id == dataId)).get
    }
  }
}
