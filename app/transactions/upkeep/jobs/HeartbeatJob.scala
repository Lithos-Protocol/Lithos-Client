package transactions.upkeep.jobs

import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import sigma.ast.ErgoTree
import transactions.upkeep.{BuildContext, JobFactory, ScriptJob, Successor}
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

/**
 * The reference upkeep job: advances boxes under `DueJob.ergo`, which state their own
 * successor.
 *
 * A due-job box says in its registers when it is next due (R4 last beat, R5 period) and what it
 * pays (R6 tip). Its script lets anyone recreate it once due, stamped with the block height and
 * lighter by at most the tip, so this job holds no protocol knowledge: discovery is "every box at
 * that tree", due is the register rule, and the successor is the box with R4 and its creation
 * height set to the block, any R7 to R9 dropped, and less whatever tip is paid.
 *
 * The tree is pinned, not compiled: [[HeartbeatJob.TreeHex]] is what was reviewed, and the client
 * carries no script source; `test/resources/upkeep/DueJob.ergo` is the record of what the tree is,
 * and the spec compiles it and holds it to the constant.
 *
 * Discovery by script needs the node's extra index; on a plain node the job maintains the box ids
 * listed in `jobs.heartbeat.boxIds`, which go stale with every beat, since a beat gives the box a
 * new id. The source reads each box back through the node's mempool-adjusted view, so a box a
 * pending transaction already spends does not come back and is dropped until a later scan finds
 * it again.
 *
 * The beat pays what the box can spare: the tip, or less when paying it in full would leave the
 * successor under the consensus minimum. A payment too small for a box of its own is left in the
 * successor and the beat is made for free, which the script allows and which keeps the box alive.
 * A box worth less than its own successor's minimum cannot be advanced at all and is declined, so
 * the source holds it rather than offer a transaction the node would refuse. With `minTip` set, a
 * box offering less than that is not this job's to maintain, and a box that offers enough but
 * cannot pay it is declined and held until it changes, so an operator can refuse free beats.
 *
 * @param boxIds the ids listed in config, maintained on every node
 * @param minTip the smallest tip a beat must pay, in nanoERG; 0 maintains every box and beats for free
 */
final class HeartbeatJob(boxIds: Seq[String], minTip: Long = HeartbeatJob.DefaultMinTip) extends ScriptJob(boxIds) {

  import HeartbeatJob.Beat

  override val name: String = HeartbeatJob.Name

  override def contract(network: NetworkType): Contract = HeartbeatJob.contract(network)

  override def maintains(box: InputUTXO): Boolean = Beat.of(box).exists(_.tip >= minTip)

  override def due(box: InputUTXO, height: Int): Boolean = Beat.of(box).exists(_.dueAt(height))

  /** The height the box is due at, so the source's cap keeps the soonest due. */
  override def priority(box: InputUTXO): Long = Beat.of(box).map(_.dueHeight).getOrElse(Long.MaxValue)

  /**
   * What a beat of `box` would pay, worked out exactly as [[plan]] works it out: the tip, or what
   * the box can spare above its successor's floor, or nothing when that is too small for a box of
   * its own or below `minTip`, and nothing for a box below its successor's floor or whose registers
   * are not a beat. Never more than the box can pay, whatever R6 declares.
   */
  override def expectedRevenue(box: InputUTXO, bc: BuildContext): Long =
    Beat.of(box).map(beat => terms(box, beat, bc)).collect { case Terms.Paying(paid) => paid }.getOrElse(0L)

  override def plan(box: InputUTXO, bc: BuildContext): Option[Successor] =
    Beat.of(box).flatMap { beat =>
      terms(box, beat, bc) match {
        case Terms.Declined => None
        case Terms.Paying(paid) =>
          val tipOut = UTXO(bc.payTo, paid).setCreationHeight(bc.height)
          Some(Successor(Seq(successor(box, beat, bc.height, box.value - paid), tipOut), revenue = Seq(1)))
        case Terms.Free => Some(Successor(Seq(successor(box, beat, bc.height, box.value))))
      }
    }

  /**
   * The one place a beat's terms are decided, for [[plan]] and [[expectedRevenue]] alike. A box
   * below its own successor's minimum cannot be recreated at all: declined, and held by the source.
   * Otherwise the beat pays the tip, or what the box can spare above the floor; a payment too small
   * for a box of its own makes the beat free. With `minTip` set, a beat that would pay less than
   * that is declined too, free beats included, so the box is held until it changes.
   */
  private def terms(box: InputUTXO, beat: Beat, bc: BuildContext): Terms = {
    // Sized at the full value, the most a successor could carry, so the floor is never understated.
    val successorFloor = ScriptJob.minimumValue(successor(box, beat, bc.height, box.value), bc)
    if (box.value < successorFloor) Terms.Declined
    else {
      val paid = math.max(0L, math.min(beat.tip, box.value - successorFloor))
      val tipOut = UTXO(bc.payTo, paid).setCreationHeight(bc.height)
      val paysItsOwnBox = paid > 0L && paid >= ScriptJob.minimumValue(tipOut, bc)
      if (minTip > 0L && (!paysItsOwnBox || paid < minTip)) Terms.Declined
      else if (paysItsOwnBox) Terms.Paying(paid)
      else Terms.Free
    }
  }

  private sealed trait Terms
  private object Terms {
    case object Declined extends Terms
    case object Free extends Terms
    final case class Paying(paid: Long) extends Terms
  }

  /** The box as its script demands it back: same script and tokens, R4 at `height`, R5 and R6 kept. */
  private def successor(box: InputUTXO, beat: Beat, height: Int, value: Long): UTXO =
    UTXO(box.contract, value, box.tokens,
      Seq(ErgoValue.of(height), ErgoValue.of(beat.period), ErgoValue.of(beat.tip)))
      .setCreationHeight(height)
}

object HeartbeatJob {

  final val Name = "heartbeat"

  /** The one key of its own: `minTip`, the smallest tip a beat must pay; [[DefaultMinTip]] by default. */
  final val MinTipKey = "minTip"

  /**
   * The default `minTip`: a thousandth of an ERG, so that a beat pays for its own tip output and a
   * box anyone paid dust into at the public script is not re-stamped for free, block after block,
   * with its rent clock reset each time. 0 maintains every box, free beats included.
   */
  final val DefaultMinTip: Long = 1000000L

  val Factory: JobFactory = JobFactory(Name,
    job => new HeartbeatJob(job.boxIds, job.block.getOptional[Long](MinTipKey).getOrElse(DefaultMinTip)),
    check = block => block.getOptional[Long](MinTipKey) match {
      case Some(t) if t < 0L => Seq(MinTipKey -> "must not be negative")
      case _ => Seq.empty
    })

  /**
   * The ErgoTree of `DueJob.ergo` (kept with the tests) as reviewed. The script takes no compile-time constants
   * and names no address, so the tree is the same on every network; `HeartbeatJobSpec` compiles it
   * for mainnet and testnet and holds both to this.
   */
  final val TreeHex: String =
    "1b8f01040400040005000400d804d601e4c6a70504d602e4c6a70605d603b2a5730000d604c1a7d1edededededed" +
      "edededed9172017301927202730293c5b2a4730300c5a7927ea3059a7ee4c6a70404057e72010593c27203c2a793" +
      "db63087203db6308a793e4c672030404a3938cc7720301a393e4c672030504720193e4c672030605720292c17203" +
      "997204a172027204"

  // Lazy, so that a class whose name config validation touches parses nothing at startup.
  private lazy val pinned: Contract = Contract(ErgoTree.fromHex(TreeHex))

  /** The pinned contract, whatever the network: discovery compares every box against its tree. */
  def contract(networkType: NetworkType): Contract = pinned

  /**
   * A due-job box's registers, read: R4 Int, R5 Int and R6 Long, with a positive period and a tip
   * not below zero. Anything else is passed over, because the script refuses to spend it.
   */
  final case class Beat(lastBeat: Int, period: Int, tip: Long) {
    def dueHeight: Long = lastBeat.toLong + period.toLong

    def dueAt(height: Int): Boolean = height.toLong >= dueHeight
  }

  object Beat {

    def of(registers: Seq[ErgoValue[_]]): Option[Beat] = registers match {
      case Seq(r4, r5, r6, _*) => (r4.getValue, r5.getValue, r6.getValue) match {
        case (last: java.lang.Integer, period: java.lang.Integer, tip: java.lang.Long)
          if period > 0 && tip >= 0L => Some(Beat(last, period, tip))
        case _ => None
      }
      case _ => None
    }

    def of(box: InputUTXO): Option[Beat] = of(box.registers)
  }
}
