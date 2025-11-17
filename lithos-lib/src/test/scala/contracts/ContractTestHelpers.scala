package contracts

import com.github.benmanes.caffeine.cache.Caffeine
import lfsm.LFSMHelpers
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoId, NetworkType, Parameters, RestApiErgoClient, SecretString}
import scorex.utils.Longs
import work.lithos.mutations.{Contract, UTXO}

object ContractTestHelpers {
  val networkType: NetworkType = NetworkType.MAINNET
  val nodePort: String = if (networkType == NetworkType.MAINNET) ":9053/" else ":9052/"
  val dummyId: ErgoId = ErgoId.create("20fa2bf23962cdf51b07722d6237c0c7b8a44f78856c0f7ec308dc1ef1a92a51")
  val client = RestApiErgoClient.create(
    "http://213.239.193.208" + nodePort,
    networkType,
    "",
    RestApiErgoClient.getDefaultExplorerUrl(networkType))

  val localClient = RestApiErgoClient.create(
    "http://127.0.0.1" + ":9052/",
    NetworkType.TESTNET,
    "",
    RestApiErgoClient.getDefaultExplorerUrl(networkType))

  def getProver(ctx: BlockchainContext) = {
     ctx.newProverBuilder().withMnemonic(SecretString.create("Put Secret Key Here :)"),
      SecretString.empty()).withEip3Secret(0).build()
  }

  def getProverContract(ctx: BlockchainContext) = {
    Contract.fromAddress(getProver(ctx).getAddress)
  }

  final val SIXTY_ERG: Long = 60 * Parameters.OneErg
  final val ONE_ERG: Long   = Parameters.OneErg

  final val DUMMY_UTXO = UTXO(Contract.SIGMA_TRUE, SIXTY_ERG + ONE_ERG)

  /**
   * Get miners, with local prover being the first, and 3 random addresses after
   */
  def getMiners(ctx: BlockchainContext, numToGet: Int = 4) = Seq(
    Contract.fromAddress(getProver(ctx).getAddress),
    Contract.fromAddress(Address.create("9fzRcctiWfzoJyqGtPWqoXPuxSmFw6zpnjtsQ1B6jSN514XqH4q")),
    Contract.fromAddress(Address.create("9euJma7w75m5VThHmTeJAPY9YWxhKHGSe8aHgF1rBJH9156pjoZ")),
    Contract.fromAddress(Address.create("9g4Kek6iWspXPAURU3zxT4RGoKvFdvqgxgkANisNFbvDwK1KoxW"))
  ).slice(0, numToGet)

  final val CHANGE_ADDRESS = Address.create("3WxKZGZBdGsfBMhajuVHkqRtqS5NEDPUA23hmECHBMygZChBiPpA")

  final val DIFF_VALUES  = Seq("4.0G", "10.2M", "12.1G", "5.322T")
  final val TAU_VALUES   = DIFF_VALUES.map(x => BigInt(LFSMHelpers.parseDiffValueForStratum(x).get))
  final val SCORE_VALUES = TAU_VALUES.map(x => LFSMHelpers.convertTauOrScore(x).toLong)
  final val TOTAL_SCORE  = SCORE_VALUES.sum
  /**
   * If we are not testing NISP evaluation here, we can simply append Coll(0.toByte)
   * to share score to represent the NISP
   */
  def insertValuesNoNISP(ctx: BlockchainContext): Array[(Array[Byte], Array[Byte])] = {
    getMiners(ctx).zip(SCORE_VALUES).map(
      x => x._1.hashedPropBytes -> (Longs.toByteArray(x._2) ++ Array(0.toByte))
    ).toArray
  }

}
