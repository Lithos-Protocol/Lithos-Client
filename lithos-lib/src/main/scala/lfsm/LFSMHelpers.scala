package lfsm

import lfsm.contracts.RollupContracts
import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, Paging, SortDirection}
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.{Contract, InputUTXO}

import java.math.{BigDecimal, BigInteger, RoundingMode}
import scala.util.{Failure, Success, Try}

/**
 * Helpers for Lithos Finite State Machine
 */
object LFSMHelpers {
  // Rollup Params
  // Target max used in contracts, 2^256 - 1
  final val TARGET_MAX_LITHOS = BigInt("115792089237316195423570985008687907853269984665640564039457584007913129639935")

  final val HOLDING_PERIOD = 360L // 360 Blocks, or 12 hours
  final val EVAL_PERIOD    = 360L

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






  // Lithos token & emission parameters
  final val INIT_MINT = Parameters.OneErg * 1000000000 // 1 billion LIT

  /**
   * Every protocol singleton is declared per network, because these are compiled into contracts.
   *
   * A single shared value means re-minting one network moves the other network's contract hashes,
   * and those hashes are what `FP_CONTROL`, the emission box and its config are minted against. The
   * mainnet entries below are placeholder copies of testnet except `LIT_ID_MAINNET`, which is real.
   * Read them through the accessors, never directly.
   */
  final val LIT_ID_TESTNET = ErgoId.create("7b728ca02a23085f1f7093e949535938c55307ab1b61e848008201c5109bd18b")
  // CONFIRMED MAINNET ID
  final val LIT_ID_MAINNET = ErgoId.create("c1980d829988229516430a47a5eca376060b6ce859616db0936e78ab25cb6de7")


  // =========== Testnet token params ===========
  final val EMISSION_NFT_TESTNET = ErgoId.create("eaae7b3a355c4453c99ced3d3f4d4e07b132e513dbbf1825c7ec933990152f8f")
  final val EMCONFIG_NFT_TESTNET = ErgoId.create("09cd15f985aaf2dbb9e151424c6f80f033c28257036851297a28bdb076e517eb")
  final val QUEUE_TOKEN_TESTNET = ErgoId.create("98f3efe2d2e024d553b235b3102ab28dafa5e39db75e1776ff8c565aa19d4fe0")
  final val COLLAT_TOKEN_TESTNET = ErgoId.create("5fd5639a29673b0b24ba1ae3a8e7cc4c5183879c6f6dec88c245e867c5984507")
  final val FP_TOKEN_TESTNET = ErgoId.create("e3107e4e42799637ea53429a3fa4e455323f1e7e3164a632a79950bdd9920b81")
  final val MD_TOKEN_TESTNET = ErgoId.create("b153c23c94ee1eb083b1903de38435c0de8b000cf94d920087fc3f1b0140116d")
  final val VOTE_TOKEN_TESTNET = ErgoId.create("6dacfaee8b82163f99cbbfd9dca67917b7206e9e55d4e3bca99dce3daf9e9170")

  final val MD_GENESIS_ID_TESTNET = "40cbcea6f8d2d48ad3f9a3407483c5e9efed7b970e8696fdff640d795ce53e76"
  final val MD_GENESIS_HEIGHT_TESTNET = 583094


  // ===========  Mainnet token params ===========
  final val EMISSION_NFT_MAINNET = ErgoId.create("1ddb641b785e9ac9baf455f64932295e48cbbf4aadc9645f74065d58d09cdc47")
  final val EMCONFIG_NFT_MAINNET = ErgoId.create("34a44f069c15ff92038803c6c21194f2c9a194f3a610c40ee1ef2773bdfc5c35")
  final val QUEUE_TOKEN_MAINNET = ErgoId.create("3508c83c692de0ac9dabe1ed6a57a36abe5fed40fba5e497967a848ee423bc08")
  final val COLLAT_TOKEN_MAINNET = ErgoId.create("a8a790e784e93ac0e68649181ae3d251e84fb5c741624100e7e945ae1e82dc98")
  final val FP_TOKEN_MAINNET = ErgoId.create("0001bb75d59f66c3d563576c51bfaa8ea316ee23b4d52f3f139a4f00df19fe62")
  final val MD_TOKEN_MAINNET = ErgoId.create("ff9b0d78e6705b3d55936b22b668761f484524a99fa1a7c6e9f7cd75119a1bbd")
  final val VOTE_TOKEN_MAINNET = ErgoId.create("519b9fdeaa91cb5b5b8cca649599534748ee724f07952b2b2f2ba04c399b81b4")

  final val MD_GENESIS_ID_MAINNET = "bab16af4899caf2c4edbf0da1d7de87dac12835a6126df91f6fa99e31952ec5d"
  final val MD_GENESIS_HEIGHT_MAINNET = 1888247


  final val PROP_TOKEN_AMNT = Long.MaxValue

  final val PERMIT_FLOOR = 2000L * Parameters.OneErg
  final val PERMIT_CEIL  = 40000L * Parameters.OneErg
  final val PERMIT_SLOPE = 20 * Parameters.OneErg
  final val PERMIT_PARAMS = Array(PERMIT_FLOOR, PERMIT_CEIL, PERMIT_SLOPE)
  final val FOUNDER_1 = Contract.fromAddress(Address.create("9hXpB6dye4gTZhV6ZBakoy934dRFW93qHjBTYVnBHCfvQiXuURr"))
  final val FOUNDER_2 = Contract.fromAddress(Address.create("9hBEAVZ9MHLf7mwVrvP3nqptdqYVdYGu1byPH8XFzC7KDuzrb8W"))
  final val FOUNDER_3 = Contract.fromAddress(Address.create("9i6Pq3M5VG95BUTAP1AVroizTBqc21K2Mx5Kh2jGeAADpBEskQd"))

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

  // Every getter below answers from `Deployment`: the override installed for this network when there
  // is one, the constants above otherwise. Contracts compile these ids in, so a getter that bypassed
  // the override would compile one contract against a devnet deployment and its neighbour against
  // the public one, and the pair would never validate together.

  def getFPToken(ctx: BlockchainContext): ErgoId = getFPToken(ctx.getNetworkType)

  def getFPToken(networkType: NetworkType): ErgoId = Deployment.ids(networkType).fpToken

  def getMDToken(networkType: NetworkType): ErgoId = Deployment.ids(networkType).mdToken

  def getLitId(networkType: NetworkType): ErgoId = Deployment.ids(networkType).lit

  def getEmissionNft(networkType: NetworkType): ErgoId = Deployment.ids(networkType).emissionNft

  def getEmConfigNft(networkType: NetworkType): ErgoId = Deployment.ids(networkType).emConfigNft

  def getVoteToken(networkType: NetworkType): ErgoId = Deployment.ids(networkType).voteToken

  def getMDGenesisId(networkType: NetworkType): String = Deployment.ids(networkType).mdGenesisId

  /** The inclusion height of the genesis box, not its creation height. */
  def getMDGenesisHeight(networkType: NetworkType): Int = Deployment.ids(networkType).mdGenesisHeight

  def getCollatToken(networkType: NetworkType): ErgoId = Deployment.ids(networkType).collatToken

  def getQueueToken(networkType: NetworkType): ErgoId = Deployment.ids(networkType).queueToken

  /** Where the FP_Control box lives: `FP_CONTROL_TESTNET`, the compiled mainnet script, or the override's. */
  def getFPControlAddress(networkType: NetworkType): Address = Deployment.ids(networkType).fpControlAddress

  def getMDToken(client: ErgoClient): ErgoId = client.execute(ctx => getMDToken(ctx.getNetworkType))

  /**
   * The FP_Control box, found by the token every evaluation checks at its first data input. Confirmed
   * only, since a data input has to be in the UTXO set already.
   */
  def getFPControlBox(ctx: BlockchainContext, nodeApi: NodeApi): InputUTXO =
    unspentByToken(ctx, nodeApi, getFPToken(ctx), MempoolOptions.ConfirmedOnly).get
      .getOrElse(throw new RuntimeException("Could not find fpControl UTXO"))

  /**
   * This miner's data box, found by its credential token and required to sit under `dataContract`.
   * `mempool` decides whether an unconfirmed successor is returned in place of the confirmed box.
   */
  def getLocalDataBox(ctx: BlockchainContext, nodeApi: NodeApi, dataId: ErgoId, dataContract: Contract,
                      mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Try[InputUTXO] =
    unspentByToken(ctx, nodeApi, dataId, mempool, Some(dataContract.ergoTreeHex)).flatMap {
      case Some(box) => Success(box)
      case None => Failure(new NoSuchElementException(s"No unspent data box carries token $dataId"))
    }

  /**
   * The unspent box whose first token is `tokenId`, read from the node's index, optionally required to
   * sit under `tree`. The box is rebuilt from the node's JSON, so its rebuilt id is checked against the
   * node's: a box spent or read under the wrong id makes the whole transaction invalid.
   */
  private def unspentByToken(ctx: BlockchainContext, nodeApi: NodeApi, tokenId: ErgoId,
                             mempool: MempoolOptions, tree: Option[String] = None): Try[Option[InputUTXO]] =
    nodeApi.unspentBoxesByTokenId(tokenId.toString, Paging(0, 8), SortDirection.Desc, mempool).map { boxes =>
      boxes.find(b => b.assets.headOption.exists(_.tokenId == tokenId.toString) && tree.forall(_ == b.ergoTree))
        .map { b =>
          val input = b.toInputUTXO(ctx)
          require(input.id.toString == b.boxId, s"Box ${b.boxId} rebuilt as ${input.id}; its registers did not round-trip")
          input
        }
    }
}
