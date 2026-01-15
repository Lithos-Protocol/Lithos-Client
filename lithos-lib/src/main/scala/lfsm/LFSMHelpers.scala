package lfsm

import lfsm.contracts.RollupContracts
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoId, JavaHelpers, NetworkType, Parameters}
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

  final val HOLDING_PERIOD = 10L // 360 Blocks, or 12 hours
  final val EVAL_PERIOD    = 10L
  final val NISP_WINDOW    = 160L // 160 on testnet is 2 hours (45 sec blocktime), equivalent on mainnet is 60 (2 min blocktime)
  final val NISP_COEFFICIENT = 10000 // Coefficient which separates normal shares from super-shares, used in evaluation
  final val NISP_MAX         = 26000 // Max size of NISP in bytes
  final val NISP_MIN         = 7108
  final val TX_PROOF_MIN       = 502
  final val TX_PROOF_MAX       = 2000
  final val TX_SIZE_MIN        = 450
  final val COLLAT_BOX_MIN     = 50
  final val NUM_LVLS_MAX      = 16

  final val COLLAT_MAX_FEE   = Parameters.MinFee * 100

  // FP_Control Params
  final val FP_TOKEN_MAINNET        = ErgoId.create("5a3f8a958178fc6e3b37aeea8fb94d8e6d33a7e4d2c7e70aa7db4e13c08a9903")
  final val FP_TOKEN_TESTNET        = ErgoId.create("a6d4fa307b654dcf31ce07e2462c1be5ca7c5dcc35c1363a0eff62d0b3b9ed37")
  final val FP_CONTROL_TESTNET      = Address.create("ShDJAh75M4bDZbCowYGqtmHi4iiBMqWJcbQRYLaxx8tZZHtj23c7qEcEvUiXYvSdnjdWE6R328rSazggEzz7UWRqXGZWc6L28bo96jMNK8NZs1bQBHAxkb9rLFW8Gf3HFQRPUm26CX8LZeqF1iJvftCYHTp2KC2LisbheejGeoXkv")

  // MinerDictionary Params
  final val MIN_DATA_BOX_AMNT = 1000000L
  final val MD_TOKEN_MAINNET = ErgoId.create("7f9609b232d3e2f0638d60a03a26831bf80155ed1e87a1b914e2623dfbd05518")
  final val MD_TOKEN_TESTNET = ErgoId.create("7f9609b232d3e2f0638d60a03a26831bf80155ed1e87a1b914e2623dfbd05518")
  final val MD_GENESIS_HEIGHT = 111755

  // Genesis Tx: 3469b46b3f04a9a6a93e6eb9ef507e0c9dafe12117e3bfd2c0cba486de27a5f9
  // UTXO id of initial MD box
  final val MD_GENESIS_ID = "cb9b72be6a63471ef20f138494e58558fccc8e931901109e6838b6c9860f59cc"


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
   *
   * NOTE: This is different from Tau and difficulty, as we are using different target maximum values
   * in Stratum vs in contracts. We should treat the Tau value as the absolute truth to avoid confusion.
   * Additionally, once we have a Tau value, we should never attempt to convert it back to a difficulty.
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
