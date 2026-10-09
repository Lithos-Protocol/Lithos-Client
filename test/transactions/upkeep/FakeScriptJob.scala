package transactions.upkeep

import org.ergoplatform.appkit.{NetworkType, Parameters}
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

/**
 * The smallest [[ScriptJob]]: boxes at the always-true script, due when the spec says, and a
 * successor that recreates the box [[FakeScriptJob.Tip]] lighter and pays the tip to `payTo`.
 *
 * The always-true script is what lets the base class's keyless prover sign it offline, the same
 * way it signs a due-job box, so everything [[ScriptJob]] owns — discovery, assembly, signing, the
 * capital entries — is exercised without the heartbeat's own rule in the way.
 */
final class FakeScriptJob(boxIds: Seq[String] = Seq.empty, val name: String = "script") extends ScriptJob(boxIds) {

  import FakeScriptJob._

  /** Whether every box is due. */
  @volatile var isDue: Boolean = true

  /** Boxes at the script the job says it does not maintain. */
  @volatile var foreign: Set[String] = Set.empty

  /** Boxes the successor reads without spending. */
  @volatile var dataInputs: Seq[InputUTXO] = Seq.empty

  /** The plan's revenue indices; the tip output's when not set. */
  @volatile var revenue: Seq[Int] = Seq(1)

  /** Say the box cannot pay for its successor. */
  @volatile var cannotPay: Boolean = false

  /** NanoERG the plan leaves unspent, which [[ScriptJob]] must refuse. */
  @volatile var leftOver: Long = 0L

  /** Where the plan sends the tip: `bc.payTo` unless set, which [[ScriptJob]] must refuse as revenue. */
  @volatile var tipTo: Option[Contract] = None

  /** Add an output at the fee proposition, which [[ScriptJob]] must refuse. */
  @volatile var feeOutput: Long = 0L

  /** Add an output at this contract, neither the box's nor `payTo`, which [[ScriptJob]] must refuse. */
  @volatile var extraTo: Option[Contract] = None

  /** Each box's priority by id; zero, the default, for any other. */
  @volatile var priorities: Map[String, Long] = Map.empty

  override def contract(network: NetworkType): Contract = Contract.SIGMA_TRUE

  override def maintains(box: InputUTXO): Boolean = !foreign.contains(box.id.toString)

  override def due(box: InputUTXO, height: Int): Boolean = isDue

  override def priority(box: InputUTXO): Long = priorities.getOrElse(box.id.toString, 0L)

  override def plan(box: InputUTXO, bc: BuildContext): Option[Successor] =
    if (cannotPay) None
    else Some(Successor(
      outputs = Seq(
        UTXO(box.contract, box.value - Tip, box.tokens, box.registers).setCreationHeight(bc.height),
        UTXO(tipTo.getOrElse(bc.payTo), Tip - leftOver - feeOutput - extraTo.map(_ => Tip / 2).getOrElse(0L))
          .setCreationHeight(bc.height)) ++
        (if (feeOutput > 0L) Seq(UTXO(Contract.FEE, feeOutput).setCreationHeight(bc.height)) else Seq.empty) ++
        extraTo.map(c => UTXO(c, Tip / 2).setCreationHeight(bc.height)).toSeq,
      dataInputs = dataInputs,
      revenue = revenue))
}

object FakeScriptJob {

  /** What one successor pays, in nanoERG: well over the dust minimum of a token-free box. */
  final val Tip: Long = Parameters.OneErg / 1000
}
