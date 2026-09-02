package support

import lfsm.states.{AuthenticatedDictionaryView, MinerDictionary, PlasmaDictionary, Rollup, RollupInfoState}
import lfsm.{LFSMPhase, RollupProtocol}
import scorex.utils.Longs
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
  val CollateralTree = "collateral-tree"
  val CollateralToken: ErgoId = ErgoId.create(SyncFixtures.id(900001))
  val MinerDictionaryToken: ErgoId = ErgoId.create(SyncFixtures.id(900002))

  /** The collateral box every fixture rollup is created from, so its NFT id is this. */
  val DefaultOrigin: String = SyncFixtures.id(800000)

  /** What a fixture rollup box holds before any submission adds a bond to it. */
  val RollupValue: Long = 1000000L

  def protocol(rollupStartHeight: Int = 100): SyncProtocolContext =
    SyncProtocolContext(
      networkType = NetworkType.TESTNET,
      rollupStartHeight = rollupStartHeight,
      localMinerHash = Array.fill[Byte](32)(99),
      holdingErgoTree = HoldingTree,
      evaluationErgoTree = EvaluationTree,
      payoutErgoTree = PayoutTree,
      collateralErgoTree = CollateralTree,
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
      rollupOrigins = Map.empty,
      minerTree = MinerDictionary.initialState,
      dataBoxToken = None)

  def stateWithRollup(height: Int,
                      blockId: String,
                      rollupId: String,
                      utxoId: String,
                      dictionary: AuthenticatedDictionaryView,
                      version: Long = 7L): CommittedSyncState = {
    val tree = Rollup(
      dictionary = dictionary,
      numMiners = 0,
      totalScore = BigInt(0),
      state = RollupInfoState.holding(height.toLong, height.toLong, 0L),
      value = RollupValue,
      startHeight = height,
      hasMiner = false,
      blockId = rollupId,
      utxoId = utxoId)
    CommittedSyncState(
      cursor = SyncCursor(height, blockId, SyncFixtures.id(height - 1)),
      version = version,
      rollups = Map(rollupId -> tree),
      routes = Map(utxoId -> rollupId),
      rollupOrigins = Map(rollupId -> DefaultOrigin),
      minerTree = MinerDictionary.initialState,
      dataBoxToken = None)
  }

  /** Adds an independent rollup for fault-isolation assertions. */
  def addRollup(state: CommittedSyncState,
                rollupId: String,
                utxoId: String,
                dictionary: AuthenticatedDictionaryView): CommittedSyncState = {
    val tree = Rollup(
      dictionary = dictionary,
      numMiners = 0,
      totalScore = BigInt(0),
      state = RollupInfoState.holding(state.cursor.height.toLong, state.cursor.height.toLong, 0L),
      value = RollupValue,
      startHeight = state.cursor.height,
      hasMiner = false,
      blockId = rollupId,
      utxoId = utxoId)
    state.copy(rollups = state.rollups + (rollupId -> tree),
      routes = state.routes + (utxoId -> rollupId),
      rollupOrigins = state.rollupOrigins +
        (rollupId -> SyncFixtures.id(800000 + state.rollupOrigins.size)))
  }

  /**
   * The dictionary-add shape shared by block reduction and bootstrap replay. The entry is the
   * credential the transaction mints followed by the expiry the builder supplied, and that expiry
   * rides in its own context variable because the contract bounds it rather than deriving it.
   */
  def minerAdd(tree: MinerDictionary,
               miner: work.lithos.mutations.Contract,
               mdToken: ErgoId,
               outputId: String,
               dataTokenId: String,
               height: Int): BlockTx = {
    val dictionary = tree.dictionary.copy()
    val validUntil = height.toLong + lfsm.LFSMHelpers.DATA_LIFETIME
    val value = ErgoId.create(dataTokenId).getBytes ++ scorex.utils.Longs.toByteArray(validUntil)
    val insertion = dictionary.insert(miner.hashedPropBytes -> value)
    val proof = InputSpendingProof(Map(
      "0" -> ErgoValue.of(MinerDictionary.ADD_MINER_OP).toHex,
      "1" -> collBytes(miner.valueBytes).toHex,
      "2" -> ErgoValue.of(4242L).toHex,
      "3" -> ErgoValue.of(height + lfsm.LFSMHelpers.NISP_WINDOW.toInt + 50).toHex,
      "4" -> insertion.proof.ergoValue.toHex,
      "8" -> ErgoValue.of(validUntil).toHex))
    val txId = SyncFixtures.id(30000 + height)
    val dataOutput = TxOutput(dataTokenId, 1000000L, "miner-data", Seq(
      ErgoValue.of(0).toHex, collBytes(miner.hashedPropBytes).toHex),
      Seq(Token(ErgoId.create(dataTokenId), 1L)), txId, height, 1)
    BlockTx(txId, Seq(TxInput(tree.utxoId, Some(proof))), Seq.empty,
      Seq(dictionaryOutput(outputId, txId, height, dictionary, mdToken), dataOutput))
  }

  /**
   * A dictionary removal. The identity and both proofs ride the dictionary's own input, and the
   * miner's data box is co-spent at input 1 without carrying anything the reducer reads.
   */
  def minerRemove(tree: MinerDictionary,
                  miner: work.lithos.mutations.Contract,
                  mdToken: ErgoId,
                  outputId: String,
                  height: Int): BlockTx =
    minerDrop(tree, miner, mdToken, outputId, height, MinerDictionary.REMOVE_MINER_OP,
      Seq(TxInput(SyncFixtures.id(50000 + height), None)))

  /** An eviction: the same tree transition, with no data box spent at all. */
  def minerEvict(tree: MinerDictionary,
                 miner: work.lithos.mutations.Contract,
                 mdToken: ErgoId,
                 outputId: String,
                 height: Int): BlockTx =
    minerDrop(tree, miner, mdToken, outputId, height, MinerDictionary.EVICT_MINER_OP, Seq.empty)

  private def minerDrop(tree: MinerDictionary,
                        miner: work.lithos.mutations.Contract,
                        mdToken: ErgoId,
                        outputId: String,
                        height: Int,
                        op: Byte,
                        extraInputs: Seq[TxInput]): BlockTx = {
    val dictionary = tree.dictionary.copy()
    val lookup = dictionary.lookUp(miner.hashedPropBytes)
    val deletion = dictionary.delete(miner.hashedPropBytes)
    val operation = InputSpendingProof(Map(
      "0" -> ErgoValue.of(op).toHex,
      "5" -> collBytes(miner.hashedPropBytes).toHex,
      "6" -> lookup.proof.ergoValue.toHex,
      "7" -> deletion.proof.ergoValue.toHex))
    val txId = SyncFixtures.id(40000 + height)
    BlockTx(txId,
      TxInput(tree.utxoId, Some(operation)) +: extraInputs,
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
                      state: RollupInfoState): Seq[String] =
    Seq(
      dictionary.ergoValue.toHex,
      ErgoValue.of(numMiners).toHex,
      ErgoValue.of(totalScore.bigInteger).toHex,
      state.ergoValue.toHex)

  /** The rollup NFT every successor carries, minted from the collateral box the rollup came from. */
  def rollupTokens(nft: String): Seq[Token] = Seq(Token(ErgoId.create(nft), 1L))

  def holdingOutput(id: String,
                    txId: String,
                    height: Int,
                    dictionary: AuthenticatedDictionaryView,
                    numMiners: Int = 0,
                    totalScore: BigInt = BigInt(0),
                    period: Long = 100L,
                    nispBlock: Long = -1L,
                    bond: Long = 0L,
                    value: Long = RollupValue,
                    nft: String = DefaultOrigin): TxOutput =
    TxOutput(
      id = id,
      value = value,
      ergoTree = HoldingTree,
      registers = rollupRegisters(dictionary, numMiners, totalScore,
        RollupInfoState.holding(period, if (nispBlock < 0L) period else nispBlock, bond)),
      assets = rollupTokens(nft),
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
      outputs = Seq(holdingOutput(outputId, txId, height, PlasmaDictionary.empty(),
        period = height.toLong, nft = inputId)))
  }

  /** The refundable bond a NISP whose first eight bytes carry this score has to post. */
  def bondFor(nisp: Array[Byte]): Long =
    RollupProtocol.bondForScore(Longs.fromByteArray(nisp.slice(0, 8)))

  /** A top-up: the value rises inside the rollup's own block and nothing else moves. */
  def topUpTx(number: Int,
              inputId: String,
              outputId: String,
              height: Int,
              dictionary: AuthenticatedDictionaryView,
              numMiners: Int,
              totalScore: BigInt,
              period: Long,
              added: Long,
              inputValue: Long = RollupValue,
              bond: Long = 0L,
              nispBlock: Long = -1L,
              nft: String = DefaultOrigin): BlockTx = {
    val txId = SyncFixtures.id(340000 + number)
    BlockTx(txId, Seq(TxInput(inputId, Some(InputSpendingProof(Map.empty)))), Seq.empty,
      Seq(holdingOutput(outputId, txId, height, dictionary, numMiners, totalScore, period,
        nispBlock, bond, inputValue + added, nft)))
  }

  /** Defaults to the collateral script, which authentication now requires alongside the token. */
  def resolvedInput(id: String,
                    token: Option[ErgoId],
                    height: Int,
                    ergoTree: String = CollateralTree): TxOutput =
    TxOutput(
      id = id,
      value = 1000000L,
      ergoTree = ergoTree,
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
                   period: Long,
                   inputValue: Long = RollupValue,
                   inputBond: Long = 0L,
                   nispBlock: Long = -1L,
                   nft: String = DefaultOrigin): BlockTx = {
    val txId = SyncFixtures.id(300000 + number)
    val pairHex = ErgoValue.pairOf(byteValue(key), byteValue(value)).toHex
    val spendingProof = InputSpendingProof(Map("1" -> pairHex, "2" -> proofHex))
    // A submission raises the box and the ledger by the same bond, so the fixture prices it from
    // the NISP it carries rather than taking it as another parameter that could disagree.
    val bond = scala.util.Try(bondFor(value)).getOrElse(0L)
    BlockTx(
      id = txId,
      inputs = Seq(TxInput(inputId, Some(spendingProof))),
      dataInputs = Seq.empty,
      outputs = Seq(holdingOutput(outputId, txId, height, outputDictionary,
        numMiners, totalScore, period, nispBlock, inputBond + bond, inputValue + bond, nft)))
  }

  def proofHex(bytes: Array[Byte]): String = byteValue(bytes).toHex

  /** Phase output at an arbitrary rollup script, so transform fixtures share one register layout. */
  def phaseOutput(id: String,
                  txId: String,
                  height: Int,
                  ergoTree: String,
                  dictionary: AuthenticatedDictionaryView,
                  numMiners: Int,
                  totalScore: BigInt,
                  state: RollupInfoState,
                  value: Long = RollupValue,
                  nft: String = DefaultOrigin,
                  lit: Seq[Token] = Seq.empty): TxOutput =
    TxOutput(id, value, ergoTree,
      rollupRegisters(dictionary, numMiners, totalScore, state),
      rollupTokens(nft) ++ lit, txId, height, 0)

  /** Holding to Evaluation: the tree, its counters, the value and the bonds are all conserved. */
  def holdingToEvaluationTx(number: Int,
                            inputId: String,
                            outputId: String,
                            height: Int,
                            dictionary: AuthenticatedDictionaryView,
                            numMiners: Int,
                            totalScore: BigInt,
                            period: Long,
                            nispBlock: Long = -1L,
                            bond: Long = 0L,
                            value: Long = RollupValue,
                            nft: String = DefaultOrigin): BlockTx = {
    val txId = SyncFixtures.id(310000 + number)
    val state = RollupInfoState.evaluation(period, if (nispBlock < 0L) period else nispBlock, bond)
    BlockTx(txId, Seq(TxInput(inputId, Some(InputSpendingProof(Map.empty)))), Seq.empty,
      Seq(phaseOutput(outputId, txId, height, EvaluationTree, dictionary, numMiners, totalScore,
        state, value, nft)))
  }

  /** Evaluation to Payout: R7 heights become the ERG and LIT a miner share is taken from. */
  def evaluationToPayoutTx(number: Int,
                           inputId: String,
                           outputId: String,
                           height: Int,
                           dictionary: AuthenticatedDictionaryView,
                           numMiners: Int,
                           totalScore: BigInt,
                           totalReward: Long,
                           totalLit: Long = 0L,
                           bond: Long = 0L,
                           value: Long = RollupValue,
                           nft: String = DefaultOrigin,
                           lit: Seq[Token] = Seq.empty): BlockTx = {
    val txId = SyncFixtures.id(320000 + number)
    val state = RollupInfoState.payout(totalReward, totalLit, bond)
    BlockTx(txId, Seq(TxInput(inputId, Some(InputSpendingProof(Map.empty)))), Seq.empty,
      Seq(phaseOutput(outputId, txId, height, PayoutTree, dictionary, numMiners, totalScore,
        state, value, nft, lit)))
  }

  /**
   * A fraud proof spending the Evaluation box.
   *
   * The reducer reads its context variables from input 1, which is where every live fraud proof
   * contract puts them with `getVarFromInput(1, ...)`.
   */
  def fraudProofTx(number: Int,
                   evaluationInputId: String,
                   proofInputId: String,
                   outputId: String,
                   height: Int,
                   miner: Array[Byte],
                   lookupProofHex: String,
                   deleteProofHex: String,
                   outputDictionary: AuthenticatedDictionaryView,
                   numMiners: Int,
                   totalScore: BigInt,
                   period: Long,
                   slashed: Long,
                   nispBlock: Long = -1L,
                   inputValue: Long = RollupValue,
                   inputBond: Long = 0L,
                   proverTree: String = "prover-script",
                   nft: String = DefaultOrigin): BlockTx = {
    val txId = SyncFixtures.id(330000 + number)
    val proof = InputSpendingProof(Map(
      "0" -> byteValue(miner).toHex,
      "1" -> lookupProofHex,
      "2" -> deleteProofHex))
    val state = RollupInfoState.evaluation(period, if (nispBlock < 0L) period else nispBlock,
      inputBond - slashed)
    // The forfeited bond leaves the box for the prover own script at output 1.
    val reward = TxOutput(SyncFixtures.id(350000 + number), slashed, proverTree, Seq.empty,
      Seq.empty, txId, height, 1)
    BlockTx(
      id = txId,
      inputs = Seq(
        TxInput(evaluationInputId, Some(InputSpendingProof(Map.empty))),
        TxInput(proofInputId, Some(proof))),
      dataInputs = Seq.empty,
      outputs = Seq(phaseOutput(outputId, txId, height, EvaluationTree, outputDictionary,
        numMiners, totalScore, state, inputValue - slashed, nft), reward))
  }

  private def byteValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)
}
