package support

import lfsm.states.{AuthenticatedDictionaryView, MinerTree, NISPTree, PlasmaDictionary}
import lfsm.LFSMPhase
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import state.messages.SyncMessages.SyncCursor
import state.messages.{BlockTx, InputSpendingProof, TxInput, TxOutput}
import state.synchronization.{CommittedSyncState, SyncProtocolContext}
import work.lithos.mutations.Token

object ReducerFixtures {
  val HoldingTree = "holding-tree"
  val EvaluationTree = "evaluation-tree"
  val PayoutTree = "payout-tree"
  val CollateralToken: ErgoId = ErgoId.create(SyncFixtures.id(900001))
  val MinerDictionaryToken: ErgoId = ErgoId.create(SyncFixtures.id(900002))

  def protocol(rollupStartHeight: Int = 100): SyncProtocolContext =
    SyncProtocolContext(
      networkType = NetworkType.TESTNET,
      rollupStartHeight = rollupStartHeight,
      localMinerHash = Array.fill[Byte](32)(99),
      holdingErgoTree = HoldingTree,
      evaluationErgoTree = EvaluationTree,
      payoutErgoTree = PayoutTree,
      minerDictionaryToken = MinerDictionaryToken,
      collateralToken = CollateralToken)

  def emptyState(height: Int = 99,
                 blockId: String = SyncFixtures.id(99),
                 version: Long = 7L): CommittedSyncState =
    CommittedSyncState(
      cursor = SyncCursor(height, blockId, SyncFixtures.id(height - 1)),
      version = version,
      rollups = Map.empty,
      routes = Map.empty,
      minerTree = MinerTree.initialState,
      dataBoxToken = None)

  def stateWithRollup(height: Int,
                      blockId: String,
                      rollupId: String,
                      utxoId: String,
                      dictionary: AuthenticatedDictionaryView,
                      version: Long = 7L): CommittedSyncState = {
    val tree = NISPTree(
      dictionary = dictionary,
      numMiners = 0,
      totalScore = BigInt(0),
      currentPeriod = Some(height.toLong),
      totalReward = 0L,
      startHeight = height,
      hasMiner = false,
      phase = LFSMPhase.HOLDING,
      blockId = rollupId,
      utxoId = utxoId)
    CommittedSyncState(
      cursor = SyncCursor(height, blockId, SyncFixtures.id(height - 1)),
      version = version,
      rollups = Map(rollupId -> tree),
      routes = Map(utxoId -> rollupId),
      minerTree = MinerTree.initialState,
      dataBoxToken = None)
  }

  /** Adds an independent rollup for fault-isolation assertions. */
  def addRollup(state: CommittedSyncState,
                rollupId: String,
                utxoId: String,
                dictionary: AuthenticatedDictionaryView): CommittedSyncState = {
    val tree = NISPTree(
      dictionary = dictionary,
      numMiners = 0,
      totalScore = BigInt(0),
      currentPeriod = Some(state.cursor.height.toLong),
      totalReward = 0L,
      startHeight = state.cursor.height,
      hasMiner = false,
      phase = LFSMPhase.HOLDING,
      blockId = rollupId,
      utxoId = utxoId)
    state.copy(rollups = state.rollups + (rollupId -> tree), routes = state.routes + (utxoId -> rollupId))
  }

  /** Builds the dictionary-add transaction shape shared by block reduction and bootstrap replay. */
  def minerAdd(tree: MinerTree,
               miner: work.lithos.mutations.Contract,
               mdToken: ErgoId,
               outputId: String,
               dataTokenId: String,
               height: Int): BlockTx = {
    val dictionary = tree.dictionary.copy()
    val value = ErgoId.create(dataTokenId).getBytes
    val insertion = dictionary.insert(miner.hashedPropBytes -> value)
    val pair = ErgoValue.pairOf(collBytes(miner.hashedPropBytes), collBytes(value)).toHex
    val proof = InputSpendingProof("", Map(
      "0" -> ErgoValue.of(MinerTree.ADD_MINER_OP).toHex,
      "1" -> ErgoValue.of(miner.sigmaBoolean.get).toHex,
      "2" -> pair,
      "3" -> insertion.proof.ergoValue.toHex))
    val txId = SyncFixtures.id(30000 + height)
    val dataOutput = TxOutput(dataTokenId, 1000000L, "miner-data", Seq(
      ErgoValue.of(0).toHex, collBytes(miner.hashedPropBytes).toHex),
      Seq(Token(ErgoId.create(dataTokenId), 1L)), txId, height, 1)
    BlockTx(txId, Seq(TxInput(tree.utxoId, Some(proof))), Seq.empty,
      Seq(dictionaryOutput(outputId, txId, height, dictionary, mdToken), dataOutput))
  }

  /** A dictionary removal: the operation rides input 0, the miner's own proofs ride input 1. */
  def minerRemove(tree: MinerTree,
                  miner: work.lithos.mutations.Contract,
                  mdToken: ErgoId,
                  outputId: String,
                  height: Int): BlockTx = {
    val dictionary = tree.dictionary.copy()
    val lookup = dictionary.lookUp(miner.hashedPropBytes)
    val deletion = dictionary.delete(miner.hashedPropBytes)
    val operation = InputSpendingProof("", Map("0" -> ErgoValue.of(MinerTree.REMOVE_MINER_OP).toHex))
    val removal = InputSpendingProof("", Map(
      "1" -> ErgoValue.of(miner.sigmaBoolean.get).toHex,
      "2" -> lookup.proof.ergoValue.toHex,
      "3" -> deletion.proof.ergoValue.toHex))
    val txId = SyncFixtures.id(40000 + height)
    BlockTx(txId,
      Seq(TxInput(tree.utxoId, Some(operation)), TxInput(SyncFixtures.id(50000 + height), Some(removal))),
      Seq.empty, Seq(dictionaryOutput(outputId, txId, height, dictionary, mdToken)))
  }

  def dictionaryOutput(id: String,
                       txId: String,
                       height: Int,
                       dictionary: AuthenticatedDictionaryView,
                       mdToken: ErgoId): TxOutput =
    TxOutput(id, 1000000L, "miner-dictionary", Seq(dictionary.ergoValue.toHex),
      Seq(Token(mdToken, 1L)), txId, height, 0)

  def collBytes(value: Array[Byte]): ErgoValue[sigma.Coll[Byte]] =
    ErgoValue.of(Colls.fromArray(value), scalaByteType)

  def rollupRegisters(dictionary: AuthenticatedDictionaryView,
                      numMiners: Int,
                      totalScore: BigInt,
                      periodOrReward: Long): Seq[String] =
    Seq(
      dictionary.ergoValue.toHex,
      ErgoValue.of(numMiners).toHex,
      ErgoValue.of(totalScore.bigInteger).toHex,
      ErgoValue.of(periodOrReward).toHex)

  def holdingOutput(id: String,
                    txId: String,
                    height: Int,
                    dictionary: AuthenticatedDictionaryView,
                    numMiners: Int = 0,
                    totalScore: BigInt = BigInt(0),
                    period: Long = 100L): TxOutput =
    TxOutput(
      id = id,
      value = 1000000L,
      ergoTree = HoldingTree,
      registers = rollupRegisters(dictionary, numMiners, totalScore, period),
      assets = Seq.empty,
      txId = txId,
      creationHeight = height,
      index = 0)

  def genesisTx(number: Int,
                inputId: String,
                outputId: String,
                height: Int): BlockTx = {
    val txId = SyncFixtures.id(100000 + number)
    BlockTx(
      id = txId,
      inputs = Seq(TxInput(inputId, None)),
      dataInputs = Seq.empty,
      outputs = Seq(holdingOutput(outputId, txId, height, PlasmaDictionary.empty(), period = height.toLong)))
  }

  def resolvedInput(id: String,
                    token: Option[ErgoId],
                    height: Int): TxOutput =
    TxOutput(
      id = id,
      value = 1000000L,
      ergoTree = "collateral-input",
      registers = Seq.empty,
      assets = token.toSeq.map(Token(_, 1L)),
      txId = SyncFixtures.id(200000 + height),
      creationHeight = height - 1,
      index = 0)

  def submissionTx(number: Int,
                   inputId: String,
                   outputId: String,
                   height: Int,
                   key: Array[Byte],
                   value: Array[Byte],
                   proofHex: String,
                   outputDictionary: AuthenticatedDictionaryView,
                   numMiners: Int,
                   totalScore: BigInt,
                   period: Long): BlockTx = {
    val txId = SyncFixtures.id(300000 + number)
    val pairHex = ErgoValue.pairOf(byteValue(key), byteValue(value)).toHex
    val spendingProof = InputSpendingProof("", Map("1" -> pairHex, "2" -> proofHex))
    BlockTx(
      id = txId,
      inputs = Seq(TxInput(inputId, Some(spendingProof))),
      dataInputs = Seq.empty,
      outputs = Seq(holdingOutput(outputId, txId, height, outputDictionary,
        numMiners, totalScore, period)))
  }

  def proofHex(bytes: Array[Byte]): String = byteValue(bytes).toHex

  private def byteValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)
}
