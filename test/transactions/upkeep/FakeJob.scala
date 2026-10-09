package transactions.upkeep

import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import transactions.candidate.{CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import java.util.concurrent.atomic.AtomicInteger

/**
 * A job the specs steer by hand: what it discovers, whether a box is due, and how a build ends.
 *
 * Its boxes are anyone-can-spend, so the successor can be signed offline by the wallet's prover
 * without a key being asked for; a spec that wants the source's wallet check to fire hands it a box
 * at the wallet's own key instead. Counters say what the source asked for, which is how a spec
 * tells "not built" from "built and dropped".
 */
final class FakeJob(wallet: NodeWallet, val name: String = "fake") extends UpkeepJob {

  import FakeJob._

  @volatile var discovered: Seq[String] = Seq.empty
  @volatile var listed: Set[String] = Set.empty
  @volatile var discoverFails: Boolean = false
  @volatile var isDue: Boolean = true
  @volatile var behaviour: Behaviour = Advance
  /** What each box says it pays, by id, for the source's ordering; absent is the default 0. */
  @volatile var declaredTips: Map[String, Long] = Map.empty

  val discoveries = new AtomicInteger(0)
  val dueChecks = new AtomicInteger(0)
  val builds = new AtomicInteger(0)

  override def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String] = {
    discoveries.incrementAndGet()
    if (discoverFails) throw new IllegalStateException("the fake node is down")
    discovered
  }

  override def configured: Set[String] = listed

  override def due(box: InputUTXO, height: Int): Boolean = {
    dueChecks.incrementAndGet()
    isDue
  }

  /** Declared only: what a build actually pays stays [[FakeJob.Tip]], since only the order is under test. */
  override def expectedRevenue(box: InputUTXO, bc: BuildContext): Long = declaredTips.getOrElse(box.id.toString, 0L)

  override def build(box: InputUTXO, bc: BuildContext): Option[UpkeepJob.Built] = {
    builds.incrementAndGet()
    val (ctx, height, payTo) = (bc.ctx, bc.height, bc.payTo)
    behaviour match {
      case Advance => Some(advance(ctx, Seq(box), height, payTo))
      case Padded(tipOutputs) => Some(advance(ctx, Seq(box), height, payTo, tipOutputs))
      case Refuse => None
      case Throw => throw new IllegalStateException("the fake job cannot sign")
      case ThrowFor(boxId) if boxId == box.id.toString => throw new IllegalStateException(s"the fake job trips on $boxId")
      case ThrowFor(_) => Some(advance(ctx, Seq(box), height, payTo))
      case SpendAlso(extra) => Some(advance(ctx, Seq(box, extra.toInputUTXO(ctx)), height, payTo))
      case CannotPayFor(boxId) if boxId == box.id.toString => None
      case CannotPayFor(_) => throw new IllegalStateException("the fake job cannot sign")
      case CannotPayAny(ids) if ids.contains(box.id.toString) => None
      case CannotPayAny(_) => Some(advance(ctx, Seq(box), height, payTo))
      case PayElsewhere(to) => Some(advance(ctx, Seq(box), height, to))
    }
  }

  /**
   * Every input recreated `Tip` lighter, and the tips at `payTo` over `tipOutputs` boxes: fee-less,
   * and it balances to zero change. More tip outputs make a dearer transaction, which is how a spec
   * gets two successors of different cost out of boxes of the same shape.
   */
  private def advance(ctx: BlockchainContext, inputs: Seq[InputUTXO], height: Int,
                      payTo: Contract, tipOutputs: Int = 1): UpkeepJob.Built = {
    val successors = inputs.map(in =>
      UTXO(in.contract, in.value - Tip, in.tokens, in.registers).setCreationHeight(height))
    val total = Tip * inputs.size
    val tips = (0 until tipOutputs).map { i =>
      UTXO(payTo, total / tipOutputs + (if (i == 0) total % tipOutputs else 0L)).setCreationHeight(height)
    }
    val outputs = successors ++ tips
    val unsigned = TxBuilder(ctx).setInputs(inputs: _*).setOutputs(outputs: _*).buildTx(0L, wallet.p2pk)
    val signed = wallet.sign(unsigned)
    val entries = tips.indices.map(i => CapitalEntry(CapitalOrigin.ExecutorReward,
      InputUTXO(signed.getOutputsToSpend.get(inputs.size + i)), parentTxId = signed.getId))
    UpkeepJob.Built(signed, entries)
  }
}

object FakeJob {

  /** What one beat pays, in nanoERG: well over the dust minimum of a token-free box. */
  final val Tip: Long = Parameters.OneErg / 1000

  sealed trait Behaviour

  /** Build the successor and the tip. */
  case object Advance extends Behaviour

  /**
   * Build the successor and the tip split over this many outputs, so the transaction costs the
   * block more than [[Advance]]'s. Each output must still clear the consensus minimum: at twenty,
   * a tip share is 50,000 nanoERG against a minimum near 20,000.
   */
  final case class Padded(tipOutputs: Int) extends Behaviour

  /** Say the box cannot be advanced: the box cannot pay for its successor. */
  case object Refuse extends Behaviour

  /** Say these boxes cannot pay, and advance every other. */
  final case class CannotPayAny(ids: Set[String]) extends Behaviour

  /** Send the tip to this contract instead of the miner's: a direct job going astray. */
  final case class PayElsewhere(to: Contract) extends Behaviour

  /** Fail inside the build. */
  case object Throw extends Behaviour

  /** Fail inside the build for one box, and advance every other. */
  final case class ThrowFor(boxId: String) extends Behaviour

  /** Say one box cannot be advanced, and fail inside the build for every other. */
  final case class CannotPayFor(boxId: String) extends Behaviour

  /** Build a successor that also spends a box the job never discovered. */
  final case class SpendAlso(extra: NodeBox) extends Behaviour
}
