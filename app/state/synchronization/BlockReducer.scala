package state.synchronization

import lfsm.states.{AuthenticatedDictionary, AuthenticatedDictionaryView, MinerDictionary, Rollup, PlasmaDictionary}
import lfsm.{LFSMHelpers, LFSMPhase}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import scorex.utils.Longs
import sigma.data.CBigInt
import sigma.{AvlTree, Coll, SigmaProp}
import state.messages.SyncMessages.SyncCursor
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, TxOutput}
import utils.Helpers
import work.lithos.mutations.Contract

import scala.util.control.NonFatal

/** Classifies a transaction by its relationship to a rollup genesis. */
sealed trait GenesisShape
object GenesisShape {
  case object Authentic extends GenesisShape
  /** Output 0 is holding-shaped, but input 0 cannot be authenticated. */
  final case class Unauthenticated(reason: String) extends GenesisShape
  /** Input 0 spends collateral into an unrecognized holding script. */
  case object UnknownScript extends GenesisShape
  case object Unrelated extends GenesisShape
}

/** Immutable protocol constants used by synchronization and snapshot identity checks. */
final case class SyncProtocolContext(networkType: NetworkType,
                                     rollupStartHeight: Int,
                                     localMinerHash: Array[Byte],
                                     holdingErgoTree: String,
                                     evaluationErgoTree: String,
                                     payoutErgoTree: String,
                                     collateralErgoTree: String,
                                     minerDictionaryToken: ErgoId,
                                     minerDictionaryGenesisId: String = LFSMHelpers.MD_GENESIS_ID,
                                     collateralToken: ErgoId = LFSMHelpers.COLLAT_TOKEN)

object SyncProtocolContext {
  def apply(networkType: NetworkType, rollupStartHeight: Int, localMinerHash: Array[Byte]): SyncProtocolContext = {
    val contracts = networkType match {
      case NetworkType.MAINNET =>
        (Helpers.holdingMainnet, Helpers.evalMainnet, Helpers.payoutMainnet, Helpers.collateralMainnet)
      case NetworkType.TESTNET =>
        (Helpers.holdingTestnet, Helpers.evalTestnet, Helpers.payoutTestnet, Helpers.collateralTestnet)
    }
    SyncProtocolContext(networkType, rollupStartHeight, localMinerHash.clone(),
      contracts._1.ergoTreeHex, contracts._2.ergoTreeHex, contracts._3.ergoTreeHex,
      contracts._4.ergoTreeHex, LFSMHelpers.getMDToken(networkType))
  }
}

sealed trait SyncApplyError {
  def message: String
}

/** Classifies reducer failures by the smallest state scope that must be discarded. */
sealed trait BlockFault {
  def error: SyncApplyError
}

object BlockFault {
  /** Drops one rollup while allowing the rest of the block to commit. */
  final case class Rollup(rollupId: String, error: SyncApplyError) extends BlockFault

  /** Restores the dictionary base state while allowing the block to commit. */
  final case class MinerDictionary(error: SyncApplyError) extends BlockFault
}

object SyncApplyError {
  final case class NonContiguousBlock(expectedHeight: Int, actualHeight: Int) extends SyncApplyError {
    override val message: String = s"Expected block height $expectedHeight but received $actualHeight"
  }

  final case class ParentMismatch(expectedParent: String, actualParent: String) extends SyncApplyError {
    override val message: String = s"Expected parent $expectedParent but received $actualParent"
  }

  final case class MalformedTransaction(txId: String, detail: String) extends SyncApplyError {
    override val message: String = s"Malformed recognized transaction $txId: $detail"
  }

  final case class InvalidProof(txId: String, operation: String) extends SyncApplyError {
    override val message: String = s"Invalid $operation proof in transaction $txId"
  }

  final case class StateInvariant(txId: String, detail: String) extends SyncApplyError {
    override val message: String = s"State invariant failed for transaction $txId: $detail"
  }

  final case class InternalFailure(blockId: String, detail: String) extends SyncApplyError {
    override val message: String = s"Unexpected reducer failure for block $blockId: $detail"
  }
}

final case class BlockTransition(state: CommittedSyncState,
                                 relevantEvents: Int,
                                 stagedDictionaryCopies: Int,
                                 quarantined: Seq[QuarantineFault] = Seq.empty,
                                 minerDictionaryFault: Option[String] = None,
                                 unrecognizedGenesis: Seq[String] = Seq.empty,
                                 unauthenticatedGenesis: Seq[(String, String)] = Seq.empty,
                                 dictionaryTransforms: Vector[DictionaryTransform] = Vector.empty,
                                 /** The chain moved the dictionary while its state was faulted. */
                                 minerDictionaryMoved: Boolean = false)

/**
 *  Applies a block deterministically to private dictionary copies without mutating the base state.
 *  A block's transactions are reduced into transforms, which are then applied to a copy of the current
 *  state sequentially until block application is complete and a transition result is outputted.
 *  Failures are separated and quarantined to prevent unnecessary downtime on unrelated dictionaries,
 *  and to ensure certain client capabilities remain resilient under different failure modes.
 *  */
object BlockReducer {
  import SyncApplyError._

  /** Digest of an empty dictionary, which the collateral contract pins on every genesis holding box. */
  private val EmptyDictionaryDigest: Array[Byte] = PlasmaDictionary.empty().digest

  def applyBlock(base: CommittedSyncState,
                 block: BlockInfo,
                 protocol: SyncProtocolContext): Either[SyncApplyError, BlockTransition] = {
    val expectedHeight = base.cursor.height + 1
    if (block.height != expectedHeight)
      Left(NonContiguousBlock(expectedHeight, block.height))
    else if (base.cursor.blockId.nonEmpty && block.parentId != base.cursor.blockId)
      Left(ParentMismatch(base.cursor.blockId, block.parentId))
    else {
      val staged = new StagedState(base, protocol)
      // Isolated rollup and dictionary faults do not stop the remaining transactions.
      block.txs.foldLeft[Either[SyncApplyError, Unit]](Right(())) {
        case (Right(_), tx) =>
          staged.applyTransaction(block, tx) match {
            case Right(())                           => Right(())
            case Left(BlockFault.Rollup(id, error))  => Right(staged.quarantine(id, error))
            case Left(BlockFault.MinerDictionary(e)) => Right(staged.failMinerDictionary(e))
          }
        case (left, _) => left
      }.map { _ =>
        val next = staged.result.copy(
          cursor = SyncCursor(block.height, block.id, block.parentId),
          version = base.version + 1L)
        // Report only faults introduced by this block.
        BlockTransition(next, staged.relevantEvents, staged.dictionaryCopies, staged.quarantined,
          if (base.minerDictionaryFault.isEmpty) staged.minerDictionaryFault else None,
          staged.unrecognizedGenesis, staged.unauthenticatedGenesis, staged.dictionaryTransforms,
          staged.minerDictionaryMoved)
      }
    }
  }

  /** Applies one dictionary transform without advancing a block cursor. */
  def applyMinerDictionaryTransform(base: CommittedSyncState,
                                    height: Int,
                                    tx: BlockTx,
                                    protocol: SyncProtocolContext): Either[SyncApplyError, CommittedSyncState] = {
    val replay = new MinerDictionaryReplay(base, protocol)
    replay.apply(height, tx).map(_ => replay.current)
  }

  /**
   * Whether re-reading the same transaction could give a different answer.
   * A shape or state the chain reports cannot change; a decode or an unexpected throw can.
   */
  def isRetryable(error: SyncApplyError): Boolean = error match {
    case _: MalformedTransaction => true
    case _: InternalFailure => true
    case _ => false
  }

  /**
   * Rebuilds one rollup from its genesis by replaying its own spend chain.
   * Isolated from committed state, so a failed rebuild publishes nothing.
   */
  final class RollupReplay(protocol: SyncProtocolContext) {
    private var state = CommittedSyncState(SyncCursor(0, "", ""), 0L, Map.empty, Map.empty, Map.empty,
      MinerDictionary.initialState, None)

    /** Applies one transaction, reporting whether it changed rollup state. */
    def apply(block: BlockInfo, tx: BlockTx): Either[SyncApplyError, Boolean] = {
      val staged = new StagedState(state, protocol)
      staged.applyTransaction(block, tx).left.map(_.error).map { _ =>
        state = staged.result
        staged.relevantEvents > 0
      }
    }

    def rollup(rollupId: String): Option[Rollup] = state.rollups.get(rollupId)
  }

  /**
   * Replays dictionary transforms in order through one staged copy.
   *
   * The block path copies per transform because a failed block must leave its base untouched.
   * Call `frozen` to take a state that later transforms cannot change.
   */
  final class MinerDictionaryReplay(base: CommittedSyncState, protocol: SyncProtocolContext) {
    private val staged = new StagedState(base, protocol)

    def apply(height: Int, tx: BlockTx): Either[SyncApplyError, Unit] = {
      val block = BlockInfo(id = "", height = height, txs = Seq(tx), parentId = "")
      if (base.minerDictionaryFault.nonEmpty)
        // Otherwise the dictionary branch is skipped and the base returns unchanged, as success.
        Left(StateInvariant(tx.id,
          s"dictionary state is already faulted: ${base.minerDictionaryFault.get}"))
      else if (!staged.recognizesMinerDictionary(tx))
        Left(StateInvariant(tx.id, "transaction does not carry the Miner Dictionary singleton"))
      else staged.applyTransaction(block, tx).left.map(_.error)
    }

    /** The state so far. Its dictionary is still the one this replay mutates. */
    def current: CommittedSyncState = staged.result

    /** The state so far, detached, so continuing the replay cannot change it. */
    def frozen: CommittedSyncState = {
      val state = staged.result
      state.copy(minerTree = state.minerTree.copy(dictionary = state.minerTree.dictionary.copy()))
    }
  }

  private final class StagedState(base: CommittedSyncState, protocol: SyncProtocolContext) {
    private var rollups = base.rollups
    private var routes = base.routes
    private var rollupOrigins = base.rollupOrigins
    private var minerTree = base.minerTree
    private var dataBoxToken = base.dataBoxToken
    private var stagedRollupDictionaries = Map.empty[String, AuthenticatedDictionary]
    private var stagedMinerDictionary = Option.empty[AuthenticatedDictionary]
    private var stagedOperations = Map.empty[DictionaryId, Vector[DictionaryOperation]]
    private var stagedBeforeDigests = Map.empty[DictionaryId, String]

    private var quarantines = base.quarantined

    var relevantEvents: Int = 0
    var dictionaryCopies: Int = 0
    var quarantined: Seq[QuarantineFault] = Seq.empty
    var unrecognizedGenesis: Seq[String] = Seq.empty
    var unauthenticatedGenesis: Seq[(String, String)] = Seq.empty
    /** Set when a block spends the dictionary singleton while its state is faulted. */
    var minerDictionaryMoved: Boolean = false

    /** The quarantined rollup whose current UTXO this input spends, if any. */
    private def quarantinedUtxo(inputId: String): Option[String] =
      if (inputId.isEmpty) None
      else quarantines.collectFirst { case (id, fault) if fault.utxoId == inputId => id }
    // Preserve a prior fault until a fresh bootstrap supplies known dictionary state.
    var minerDictionaryFault: Option[String] = base.minerDictionaryFault

    def result: CommittedSyncState =
      base.copy(rollups = rollups, routes = routes, rollupOrigins = rollupOrigins,
        minerTree = minerTree, dataBoxToken = dataBoxToken,
        minerDictionaryFault = minerDictionaryFault, quarantined = quarantines)

    def dictionaryTransforms: Vector[DictionaryTransform] =
      stagedOperations.toVector.sortBy(_._1.value).flatMap { case (dictionaryId, operations) =>
        val after = dictionaryId match {
          case DictionaryId.Rollup(id) => rollups.get(id).map(_.dictionary)
          case DictionaryId.Miner => Some(minerTree.dictionary)
        }
        after.map(dictionary => DictionaryTransform(dictionaryId, stagedBeforeDigests(dictionaryId),
          operations, Hex.toHexString(dictionary.digest)))
      }

    /** Clears a fault when its rollup is tracked again, so a rebuild leaves no stale entry. */
    def untrack(rollupId: String): Unit = quarantines -= rollupId

    def applyTransaction(block: BlockInfo, tx: BlockTx): Either[BlockFault, Unit] = {
      tx.inputs.headOption.flatMap(in => routes.get(in.id)) match {
        case Some(rollupId) =>
          applyRollupTransform(tx, rollupId).left.map(BlockFault.Rollup(rollupId, _))
        // Skip later dictionary spends after the tracked UTXO becomes unknown.
        case None if isMinerDictionaryTransform(tx) && minerDictionaryFault.isEmpty =>
          applyMinerDictionaryTransform(block, tx).left.map(BlockFault.MinerDictionary)

        // Faulted: there is no tracked tree to apply this to, and it is still the case that the
        // chain moved the dictionary. A rebuild started before this block is now stale, so the
        // block has to say so or a repair permit taken earlier would still look current.
        case None if isMinerDictionaryTransform(tx) =>
          minerDictionaryMoved = true
          Right(())

        // Same for a quarantined rollup, which has no route and so can only be recognized by the
        // UTXO the fault retained. Advancing it keeps the next spend detectable too.
        case None if tx.inputs.headOption.exists(in => quarantinedUtxo(in.id).isDefined) =>
          tx.inputs.headOption.flatMap(in => quarantinedUtxo(in.id)).foreach { rollupId =>
            quarantines.get(rollupId).foreach { fault =>
              quarantines += rollupId -> fault.copy(utxoId = tx.outputs.headOption.map(_.id).getOrElse(""))
            }
          }
          Right(())

        case None => classifyGenesis(block, tx) match {
          // Reported rather than attributed: quarantining this id would drop the rollup already
          // tracked under it, which is the one that is legitimate.
          case GenesisShape.Authentic if rollups.contains(block.id) =>
            unauthenticatedGenesis :+= tx.id -> (s"block ${block.id} already created a rollup; " +
              "a block carries at most one collateral spend")
            Right(())
          // The exact collateral contract enforces the decoded genesis shape. A failure here therefore
          // denotes client or node-data integrity failure, not a chain shape a repair walk can fix.
          case GenesisShape.Authentic => applyGenesis(block, tx) match {
            case Right(_) => Right(())
            case Left(error) =>
              failAuthenticatedGenesis(block, tx, error)
              Right(())
          }
          case GenesisShape.Unauthenticated(reason) =>
            unauthenticatedGenesis :+= tx.id -> reason
            Right(())
          case GenesisShape.UnknownScript =>
            unrecognizedGenesis :+= tx.id
            Right(())
          case GenesisShape.Unrelated => Right(())
        }
      }
    }

    /** Drop a rollup whose transform could not be applied. Its staged copy goes with it. */
    def quarantine(rollupId: String, error: SyncApplyError): Unit = {
      val tree = rollups(rollupId)
      val collateralBoxId = rollupOrigins(rollupId)
      val genesisHeight = tree.startHeight
      routes -= tree.utxoId
      rollups -= rollupId
      rollupOrigins -= rollupId
      stagedRollupDictionaries -= rollupId
      stagedOperations -= DictionaryId.Rollup(rollupId)
      stagedBeforeDigests -= DictionaryId.Rollup(rollupId)
      val fault = QuarantineFault(rollupId, genesisHeight, collateralBoxId, error.message,
        BlockReducer.isRetryable(error), utxoId = tree.utxoId)
      quarantines += rollupId -> fault
      quarantined :+= fault
    }

    /** Records an impossible authenticated-genesis decode as terminal without offering it to repair. */
    private def failAuthenticatedGenesis(block: BlockInfo, tx: BlockTx, error: SyncApplyError): Unit = {
      val collateralBoxId = tx.inputs.head.id
      val reason = s"Authenticated genesis ${tx.id} violated its contract-enforced shape: ${error.message}"
      val fault = QuarantineFault(block.id, block.height, collateralBoxId, reason, retryable = false)
      quarantines += block.id -> fault
      quarantined :+= fault
    }

    /** Restores the block's base dictionary because later spends cannot follow a failed transform. */
    def failMinerDictionary(error: SyncApplyError): Unit = {
      minerTree = base.minerTree
      dataBoxToken = base.dataBoxToken
      stagedMinerDictionary = None
      stagedOperations -= DictionaryId.Miner
      stagedBeforeDigests -= DictionaryId.Miner
      minerDictionaryFault = Some(error.message)
    }

    /**
     * Recognizes the configured holding output and authenticates its first input as collateral.
     * If height is too low or is not collateral, we consider it unrelated
     */
    private def classifyGenesis(block: BlockInfo, tx: BlockTx): GenesisShape = {
      if (block.height < protocol.rollupStartHeight || !createsEmptyRollupState(tx))
        GenesisShape.Unrelated
      else if (tx.outputs.headOption.exists(_.ergoTree == protocol.holdingErgoTree)) {
        tx.inputs.headOption.flatMap(in => block.inputBox(in.id)) match {
          case None =>
            GenesisShape.Unauthenticated(s"input 0 (${tx.inputs.headOption.map(_.id).getOrElse("absent")}) " +
              "was not resolved by the block source, so its collateral token could not be checked")
          case Some(input) if spendsCollateral(input) => GenesisShape.Authentic
          case Some(input) =>
            GenesisShape.Unauthenticated(s"input 0 is not the configured collateral contract/token; " +
              s"script is ${input.ergoTree}, assets are " +
              s"[${input.assets.map(t => s"${t.id}:${t.amount}").mkString(", ")}]")
        }
      }
      // Report collateral spends targeting a holding script outside the configured protocol.
      else if (tx.inputs.headOption.flatMap(in => block.inputBox(in.id)).exists(spendsCollateral))
        GenesisShape.UnknownScript
      else GenesisShape.Unrelated
    }

    /** Whether output 0 holds a freshly created, empty authenticated dictionary. */
    private def createsEmptyRollupState(tx: BlockTx): Boolean =
      tx.outputs.headOption.exists(out =>
        avlDigest(out, tx.id).exists(sameBytes(_, BlockReducer.EmptyDictionaryDigest)))

    private def spendsCollateral(input: TxOutput): Boolean =
      input.assets.exists(t => t.id == protocol.collateralToken && t.amount == 1L) &&
        input.ergoTree == protocol.collateralErgoTree

    private def applyGenesis(block: BlockInfo, tx: BlockTx): Either[SyncApplyError, Unit] = {
      for {
        output <- required(tx.outputs.headOption, tx.id, "genesis output 0")
        registers <- rollupRegisters(output, tx.id)
        _ <- requireState(tx.id, registers.numMiners == 0, "genesis miner count must be zero")
        _ <- requireState(tx.id, registers.totalScore == 0, "genesis total score must be zero")
        _ <- requireState(tx.id, registers.periodOrReward == block.height.toLong,
          "genesis period must equal its containing block height")
        dictionary = PlasmaDictionary.empty()
        _ <- requireDigest(tx.id, dictionary, registers.digest)
      } yield {
        val tree = Rollup(dictionary, registers.numMiners, registers.totalScore,
          Some(registers.periodOrReward), output.value, registers.periodOrReward.toInt,
          hasMiner = false, LFSMPhase.HOLDING, blockId = block.id, utxoId = output.id)
        rollups += block.id -> tree
        routes += output.id -> block.id
        rollupOrigins += block.id -> tx.inputs.head.id
        // Tracking it again ends any fault held for it, which the state invariant requires.
        untrack(block.id)
        relevantEvents += 1
      }
    }

    def recognizesMinerDictionary(tx: BlockTx): Boolean = isMinerDictionaryTransform(tx)

    private def isMinerDictionaryTransform(tx: BlockTx): Boolean =
      tx.outputs.headOption.exists(_.assets.exists(t => t.id == protocol.minerDictionaryToken && t.amount == 1L)) &&
        !tx.outputs.headOption.exists(_.id == protocol.minerDictionaryGenesisId)

    private def applyRollupTransform(tx: BlockTx, rollupId: String): Either[SyncApplyError, Unit] = {
      val tree = rollups(rollupId)
      val applied = tree.phase match {
        case LFSMPhase.HOLDING => applyHolding(tx, rollupId, tree)
        case LFSMPhase.EVAL => applyEvaluation(tx, rollupId, tree)
        case LFSMPhase.PAYOUT => applyPayout(tx, rollupId, tree)
      }
      applied.map { _ => relevantEvents += 1 }
    }

    private def applyHolding(tx: BlockTx,
                             rollupId: String,
                             tree: Rollup): Either[SyncApplyError, Unit] = {
      required(tx.outputs.headOption, tx.id, "rollup output 0").flatMap { output =>
        if (output.ergoTree == protocol.holdingErgoTree) applySubmission(tx, rollupId, tree, output)
        else if (output.ergoTree == protocol.evaluationErgoTree) {
          for {
            registers <- rollupRegisters(output, tx.id)
            _ <- requireDigest(tx.id, tree.dictionary, registers.digest)
            _ <- requireState(tx.id, registers.numMiners == tree.numMiners,
              "holding-to-evaluation changed miner count")
            _ <- requireState(tx.id, registers.totalScore == tree.totalScore,
              "holding-to-evaluation changed total score")
          } yield {
            if (tree.hasMiner)
              replaceRollup(rollupId, tree.copy(currentPeriod = Some(registers.periodOrReward),
                phase = LFSMPhase.EVAL, utxoId = output.id))
            else removeRollup(rollupId)
          }
        } else Left(StateInvariant(tx.id, s"unexpected output contract for holding phase: ${output.ergoTree}"))
      }
    }

    private def applySubmission(tx: BlockTx,
                                rollupId: String,
                                tree: Rollup,
                                output: TxOutput): Either[SyncApplyError, Unit] = {
      for {
        proof <- spendingProof(tx, 0)
        keyValue <- extensionPair(proof, "1", tx.id)
        expectedProof <- extensionBytes(proof, "2", tx.id)
        registers <- rollupRegisters(output, tx.id)
        score <- attempt(tx.id, "submitted NISP is too short to contain a score") {
          Longs.fromByteArray(keyValue._2.slice(0, 8))
        }
        dictionary = mutableRollupDictionary(rollupId)
        insertion <- attempt(tx.id, "could not apply dictionary insertion") {
          dictionary.insert(keyValue._1 -> keyValue._2)
        }
        _ = record(DictionaryId.Rollup(rollupId), DictionaryOperation.Insert(tx.id, Seq(keyValue)))
        // _ <- requireProof(tx.id, "insertion", insertion.proof.ergoValue.getValue, expectedProof)
        _ <- requireDigest(tx.id, dictionary, registers.digest)
        _ <- requireState(tx.id, registers.numMiners == tree.numMiners + 1,
          s"expected miner count ${tree.numMiners + 1}, found ${registers.numMiners}")
        _ <- requireState(tx.id, registers.totalScore == tree.totalScore + score,
          "submission output total score does not include the submitted NISP")
        _ <- requireState(tx.id, tree.currentPeriod.contains(registers.periodOrReward),
          "submission changed the holding-period start")
      } yield {
        val next = tree.copy(dictionary = dictionary, numMiners = registers.numMiners,
          totalScore = registers.totalScore, currentPeriod = Some(registers.periodOrReward),
          hasMiner = tree.hasMiner || sameBytes(keyValue._1, protocol.localMinerHash),
          utxoId = output.id)
        replaceRollup(rollupId, next)
      }
    }

    private def applyEvaluation(tx: BlockTx,
                                rollupId: String,
                                tree: Rollup): Either[SyncApplyError, Unit] = {
      required(tx.outputs.headOption, tx.id, "rollup output 0").flatMap { output =>
        if (output.ergoTree == protocol.evaluationErgoTree) applyFraudProof(tx, rollupId, tree, output)
        else if (output.ergoTree == protocol.payoutErgoTree) {
          for {
            registers <- rollupRegisters(output, tx.id)
            _ <- requireDigest(tx.id, tree.dictionary, registers.digest)
            _ <- requireState(tx.id, registers.numMiners == tree.numMiners,
              "evaluation-to-payout changed miner count")
            _ <- requireState(tx.id, registers.totalScore == tree.totalScore,
              "evaluation-to-payout changed total score")
            _ <- requireState(tx.id, registers.periodOrReward == tree.totalReward,
              "evaluation-to-payout total reward does not match the tracked rollup value")
          } yield {
            if (tree.hasMiner)
              replaceRollup(rollupId, tree.copy(currentPeriod = None,
                totalReward = registers.periodOrReward, phase = LFSMPhase.PAYOUT, utxoId = output.id))
            else removeRollup(rollupId)
          }
        } else Left(StateInvariant(tx.id, s"unexpected output contract for evaluation phase: ${output.ergoTree}"))
      }
    }

    private def applyFraudProof(tx: BlockTx,
                                rollupId: String,
                                tree: Rollup,
                                output: TxOutput): Either[SyncApplyError, Unit] = {
      for {
        proof <- spendingProof(tx, 1)
        miner <- extensionBytes(proof, "0", tx.id)
        expectedLookup <- extensionBytes(proof, "1", tx.id)
        expectedDelete <- extensionBytes(proof, "2", tx.id)
        registers <- rollupRegisters(output, tx.id)
        dictionary = mutableRollupDictionary(rollupId)
        lookup <- attempt(tx.id, "could not apply dictionary lookup") { dictionary.lookUp(miner) }
        // _ <- requireProof(tx.id, "lookup", lookup.proof.ergoValue.getValue, expectedLookup)
        value <- lookup.response.headOption.flatMap(_.tryOp.toOption.flatten) match {
          case Some(bytes) => Right(bytes)
          case None => Left(StateInvariant(tx.id, "fraud proof miner is absent from the dictionary"))
        }
        score <- attempt(tx.id, "dictionary value is too short to contain a score") {
          Longs.fromByteArray(value.slice(0, 8))
        }
        deletion <- attempt(tx.id, "could not apply dictionary deletion") { dictionary.delete(miner) }
        _ = record(DictionaryId.Rollup(rollupId), DictionaryOperation.Delete(tx.id, Seq(miner)))
        // _ <- requireProof(tx.id, "deletion", deletion.proof.ergoValue.getValue, expectedDelete)
        _ <- requireDigest(tx.id, dictionary, registers.digest)
        _ <- requireState(tx.id, registers.numMiners == tree.numMiners - 1,
          s"expected miner count ${tree.numMiners - 1}, found ${registers.numMiners}")
        _ <- requireState(tx.id, registers.totalScore == tree.totalScore - score,
          "fraud proof output total score does not match the removed NISP")
        _ <- requireState(tx.id, tree.currentPeriod.contains(registers.periodOrReward),
          "fraud proof changed the evaluation-period start")
      } yield {
        val next = tree.copy(dictionary = dictionary, numMiners = registers.numMiners,
          totalScore = registers.totalScore, currentPeriod = Some(registers.periodOrReward),
          hasMiner = tree.hasMiner && !sameBytes(miner, protocol.localMinerHash),
          utxoId = output.id)
        replaceRollup(rollupId, next)
      }
    }

    private def applyPayout(tx: BlockTx,
                            rollupId: String,
                            tree: Rollup): Either[SyncApplyError, Unit] = {
      for {
        proof <- spendingProof(tx, 0)
        miners <- extensionNestedBytes(proof, "0", tx.id)
        expectedLookup <- extensionBytes(proof, "1", tx.id)
        expectedDelete <- extensionBytes(proof, "2", tx.id)
        dictionary = mutableRollupDictionary(rollupId)
        lookup <- attempt(tx.id, "could not apply payout dictionary lookup") {
          dictionary.lookUp(miners: _*)
        }
        // _ <- requireProof(tx.id, "lookup", lookup.proof.ergoValue.getValue, expectedLookup)
        _ <- requireState(tx.id, lookup.response.forall(_.tryOp.toOption.flatten.isDefined),
          "payout contains a miner absent from the dictionary")
        deletion <- attempt(tx.id, "could not apply payout dictionary deletion") {
          dictionary.delete(miners: _*)
        }
        _ = record(DictionaryId.Rollup(rollupId), DictionaryOperation.Delete(tx.id, miners))
        // _ <- requireProof(tx.id, "deletion", deletion.proof.ergoValue.getValue, expectedDelete)
        paidLocal = miners.exists(sameBytes(_, protocol.localMinerHash))
        _ <- if (paidLocal) Right(removeRollup(rollupId))
        else applyPayoutSuccessor(tx, rollupId, tree, miners, dictionary)
      } yield {
        ()
      }
    }

    private def applyPayoutSuccessor(tx: BlockTx,
                                     rollupId: String,
                                     tree: Rollup,
                                     miners: Seq[Array[Byte]],
                                     dictionary: AuthenticatedDictionary): Either[SyncApplyError, Unit] =
      tx.outputs.headOption.filter(_.ergoTree == protocol.payoutErgoTree) match {
          case Some(output) =>
            for {
              registers <- rollupRegisters(output, tx.id)
              _ <- requireDigest(tx.id, dictionary, registers.digest)
              _ <- requireState(tx.id, registers.numMiners == tree.numMiners,
                "payout successor changed the committed miner count")
              _ <- requireState(tx.id, registers.totalScore == tree.totalScore,
                "payout successor changed the committed total score")
              _ <- requireState(tx.id, registers.periodOrReward == tree.totalReward,
                "payout successor changed the committed total reward")
            } yield replaceRollup(rollupId, tree.copy(dictionary = dictionary, utxoId = output.id))
          case None => Right(removeRollup(rollupId))
      }

    private def applyMinerDictionaryTransform(block: BlockInfo,
                                              tx: BlockTx): Either[SyncApplyError, Unit] = {
      if (!tx.inputs.headOption.exists(_.id == minerTree.utxoId))
        Left(StateInvariant(tx.id,
          s"miner dictionary transform spends ${tx.inputs.headOption.map(_.id).getOrElse("no input")}, expected ${minerTree.utxoId}"))
      else {
        for {
          proof <- spendingProof(tx, 0)
          operation <- extensionByte(proof, "0", tx.id)
          _ <- operation match {
            case MinerDictionary.ADD_MINER_OP => applyMinerAdd(block, tx, proof)
            case MinerDictionary.REMOVE_MINER_OP => applyMinerRemove(block, tx, proof)
            case other => Left(MalformedTransaction(tx.id, s"unknown miner dictionary operation $other"))
          }
        } yield relevantEvents += 1
      }
    }

    private def applyMinerAdd(block: BlockInfo,
                              tx: BlockTx,
                              proof: InputSpendingProof): Either[SyncApplyError, Unit] = {
      for {
        output <- required(tx.outputs.headOption, tx.id, "miner dictionary output 0")
        dataBox <- required(tx.outputs.lift(1), tx.id, "miner data output 1")
        dataToken <- required(dataBox.assets.headOption, tx.id, "miner data singleton token")
        options <- required(dataBox.registers.lift(1), tx.id, "miner data box R5").flatMap(extensionValueBytes(_, tx.id, "miner data box R5"))
        _ <- requireState(tx.id, options.length >= 32, "miner data box R5 must contain a 32-byte miner hash")
        sigmaProp <- extensionSigmaProp(proof, "1", tx.id)
        keyValue <- extensionPair(proof, "2", tx.id)
        expectedInsert <- extensionBytes(proof, "3", tx.id)
        miner <- attempt(tx.id, "could not derive miner contract") {
          new Contract(sigma.ast.ErgoTree.fromProposition(sigmaProp))
        }
        _ <- requireState(tx.id, sameBytes(miner.hashedPropBytes, keyValue._1),
          "miner dictionary key does not match the signer")
        _ <- requireState(tx.id, sameBytes(options.take(32), keyValue._1),
          "miner data box identity does not match the dictionary key")
        dictionary = mutableMinerDictionary()
        insertion <- attempt(tx.id, "could not apply miner dictionary insertion") {
          dictionary.insert(keyValue._1 -> keyValue._2)
        }
        _ = record(DictionaryId.Miner, DictionaryOperation.Insert(tx.id, Seq(keyValue)))
        // _ <- requireProof(tx.id, "miner dictionary insertion", insertion.proof.ergoValue.getValue, expectedInsert)
        digest <- avlDigest(output, tx.id)
        _ <- requireDigest(tx.id, dictionary, digest)
      } yield {
        val isLocal = sameBytes(options.take(32), protocol.localMinerHash)
        minerTree = minerTree.copy(dictionary = dictionary, numMiners = minerTree.numMiners + 1,
          hasMiner = minerTree.hasMiner || isLocal, utxoId = output.id,
          syncHeight = block.height, savedHeight = block.height)
        if (isLocal) dataBoxToken = Some(dataToken.id)
      }
    }

    private def applyMinerRemove(block: BlockInfo,
                                 tx: BlockTx,
                                 proof: InputSpendingProof): Either[SyncApplyError, Unit] = {
      for {
        output <- required(tx.outputs.headOption, tx.id, "miner dictionary output 0")
        removalInput <- required(tx.inputs.lift(1), tx.id, "miner data input 1")
        removalProof <- required(removalInput.spendingProof, tx.id, "miner data input spending proof")
        sigmaProp <- extensionSigmaProp(removalProof, "1", tx.id)
        expectedLookup <- extensionBytes(removalProof, "2", tx.id)
        expectedDelete <- extensionBytes(removalProof, "3", tx.id)
        miner <- attempt(tx.id, "could not derive miner contract") {
          new Contract(sigma.ast.ErgoTree.fromProposition(sigmaProp))
        }
        dictionary = mutableMinerDictionary()
        lookup <- attempt(tx.id, "could not apply miner dictionary lookup") {
          dictionary.lookUp(miner.hashedPropBytes)
        }
        // _ <- requireProof(tx.id, "miner dictionary lookup", lookup.proof.ergoValue.getValue, expectedLookup)
        _ <- lookup.response.headOption.flatMap(_.tryOp.toOption.flatten) match {
          case Some(_) => Right(())
          case None => Left(StateInvariant(tx.id, "removed miner is absent from the dictionary"))
        }
        deletion <- attempt(tx.id, "could not apply miner dictionary deletion") {
          dictionary.delete(miner.hashedPropBytes)
        }
        _ = record(DictionaryId.Miner,
          DictionaryOperation.Delete(tx.id, Seq(miner.hashedPropBytes)))
        // _ <- requireProof(tx.id, "miner dictionary deletion", deletion.proof.ergoValue.getValue, expectedDelete)
        digest <- avlDigest(output, tx.id)
        _ <- requireDigest(tx.id, dictionary, digest)
      } yield {
        val isLocal = sameBytes(miner.hashedPropBytes, protocol.localMinerHash)
        minerTree = minerTree.copy(dictionary = dictionary, numMiners = minerTree.numMiners - 1,
          hasMiner = minerTree.hasMiner && !isLocal, utxoId = output.id,
          syncHeight = block.height, savedHeight = block.height)
        if (isLocal) dataBoxToken = None
      }
    }

    private def mutableRollupDictionary(rollupId: String): AuthenticatedDictionary = {
      stagedRollupDictionaries.getOrElse(rollupId, {
        stagedBeforeDigests += DictionaryId.Rollup(rollupId) ->
          Hex.toHexString(rollups(rollupId).dictionary.digest)
        val copied = rollups(rollupId).dictionary.copy()
        rollups += rollupId -> rollups(rollupId).copy(dictionary = copied)
        stagedRollupDictionaries += rollupId -> copied
        dictionaryCopies += 1
        copied
      })
    }

    private def mutableMinerDictionary(): AuthenticatedDictionary = {
      stagedMinerDictionary.getOrElse {
        stagedBeforeDigests += DictionaryId.Miner -> Hex.toHexString(minerTree.dictionary.digest)
        val copied = minerTree.dictionary.copy()
        minerTree = minerTree.copy(dictionary = copied)
        stagedMinerDictionary = Some(copied)
        dictionaryCopies += 1
        copied
      }
    }

    private def replaceRollup(rollupId: String, next: Rollup): Unit = {
      routes -= rollups(rollupId).utxoId
      rollups += rollupId -> next
      routes += next.utxoId -> rollupId
    }

    private def removeRollup(rollupId: String): Unit = {
      routes -= rollups(rollupId).utxoId
      rollups -= rollupId
      rollupOrigins -= rollupId
      stagedOperations -= DictionaryId.Rollup(rollupId)
      stagedBeforeDigests -= DictionaryId.Rollup(rollupId)
    }

    private def record(dictionaryId: DictionaryId, operation: DictionaryOperation): Unit =
      stagedOperations += dictionaryId -> (stagedOperations.getOrElse(dictionaryId, Vector.empty) :+ operation)
  }

  private final case class RollupRegisters(digest: Array[Byte],
                                           numMiners: Int,
                                           totalScore: BigInt,
                                           periodOrReward: Long)

  private def rollupRegisters(output: TxOutput,
                              txId: String): Either[SyncApplyError, RollupRegisters] =
    for {
      digest <- avlDigest(output, txId)
      minersHex <- required(output.registers.lift(1), txId, "R5 miner count")
      miners <- decodeValue(minersHex, txId, "R5 miner count")(_.asInstanceOf[Int])
      scoreHex <- required(output.registers.lift(2), txId, "R6 total score")
      score <- decodeValue(scoreHex, txId, "R6 total score")(_.asInstanceOf[CBigInt].wrappedValue)
      periodHex <- required(output.registers.lift(3), txId, "R7 period or total reward")
      period <- decodeValue(periodHex, txId, "R7 period or total reward")(_.asInstanceOf[Long])
    } yield RollupRegisters(digest, miners, score, period)

  private def avlDigest(output: TxOutput, txId: String): Either[SyncApplyError, Array[Byte]] =
    required(output.registers.headOption, txId, "R4 authenticated dictionary").flatMap { hex =>
      decodeValue(hex, txId, "R4 authenticated dictionary") {
        _.asInstanceOf[AvlTree].digest.toArray
      }
    }

  private def spendingProof(tx: BlockTx,
                            index: Int): Either[SyncApplyError, InputSpendingProof] =
    for {
      input <- required(tx.inputs.lift(index), tx.id, s"input $index")
      proof <- required(input.spendingProof, tx.id, s"input $index spending proof")
    } yield proof

  private def extensionByte(proof: InputSpendingProof,
                            key: String,
                            txId: String): Either[SyncApplyError, Byte] =
    extension(proof, key, txId).flatMap(decodeValue(_, txId, s"context variable $key")(_.asInstanceOf[Byte]))

  private def extensionBytes(proof: InputSpendingProof,
                             key: String,
                             txId: String): Either[SyncApplyError, Array[Byte]] =
    extension(proof, key, txId).flatMap(extensionValueBytes(_, txId, s"context variable $key"))

  private def extensionValueBytes(hex: String,
                                  txId: String,
                                  field: String): Either[SyncApplyError, Array[Byte]] =
    decodeValue(hex, txId, field)(_.asInstanceOf[Coll[Byte]].toArray)

  private def extensionPair(proof: InputSpendingProof,
                            key: String,
                            txId: String): Either[SyncApplyError, (Array[Byte], Array[Byte])] =
    extension(proof, key, txId).flatMap { hex =>
      decodeValue(hex, txId, s"context variable $key") { value =>
        val pair = value.asInstanceOf[(Coll[Byte], Coll[Byte])]
        pair._1.toArray -> pair._2.toArray
      }
    }

  private def extensionNestedBytes(proof: InputSpendingProof,
                                   key: String,
                                   txId: String): Either[SyncApplyError, Seq[Array[Byte]]] =
    extension(proof, key, txId).flatMap { hex =>
      decodeValue(hex, txId, s"context variable $key") {
        _.asInstanceOf[Coll[Coll[Byte]]].toArray.toSeq.map(_.toArray)
      }
    }

  private def extensionSigmaProp(proof: InputSpendingProof,
                                 key: String,
                                 txId: String): Either[SyncApplyError, SigmaProp] =
    extension(proof, key, txId).flatMap(decodeValue(_, txId, s"context variable $key")(_.asInstanceOf[SigmaProp]))

  private def extension(proof: InputSpendingProof,
                        key: String,
                        txId: String): Either[SyncApplyError, String] =
    proof.ext.get(key) match {
      case Some(value) => Right(value)
      case None => Left(MalformedTransaction(txId, s"missing context variable $key"))
    }

  private def decodeValue[A](hex: String,
                             txId: String,
                             field: String)(read: Any => A): Either[SyncApplyError, A] =
    attempt(txId, s"$field has an invalid serialized value or type") {
      read(ErgoValue.fromHex(hex).getValue)
    }

  /**
   * Optional diagnostic for comparing transaction proof bytes with locally generated bytes.
   *
   * Digest checks determine correctness; proof bytes may differ across valid implementations, so the
   * call sites are commented rather than deleted. The bindings that feed them stay live on purpose —
   * extracting each context variable still proves it is present and decodable, which is a check the
   * contracts rely on and which deleting the bindings would silently drop.
   */
  private def requireProof(txId: String,
                           operation: String,
                           actualValue: Any,
                           expected: Array[Byte]): Either[SyncApplyError, Unit] =
    attempt(txId, s"$operation proof has an invalid runtime type") {
      actualValue.asInstanceOf[Coll[Byte]].toArray
    }.flatMap { actual =>
      if (sameBytes(actual, expected)) Right(()) else Left(InvalidProof(txId, operation))
    }

  private def requireDigest(txId: String,
                            dictionary: AuthenticatedDictionaryView,
                            expected: Array[Byte]): Either[SyncApplyError, Unit] =
    requireState(txId, sameBytes(dictionary.digest, expected),
      "authenticated dictionary digest does not match output R4")

  private def requireState(txId: String,
                           condition: Boolean,
                           detail: => String): Either[SyncApplyError, Unit] =
    if (condition) Right(()) else Left(StateInvariant(txId, detail))

  private def required[A](value: Option[A],
                          txId: String,
                          field: String): Either[SyncApplyError, A] =
    value match {
      case Some(found) => Right(found)
      case None => Left(MalformedTransaction(txId, s"missing $field"))
    }

  private def attempt[A](txId: String,
                         detail: String)(f: => A): Either[SyncApplyError, A] =
    try Right(f)
    catch {
      case NonFatal(ex) =>
        Left(MalformedTransaction(txId, s"$detail: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"))
    }

  private def sameBytes(left: Array[Byte], right: Array[Byte]): Boolean =
    java.util.Arrays.equals(left, right)
}
