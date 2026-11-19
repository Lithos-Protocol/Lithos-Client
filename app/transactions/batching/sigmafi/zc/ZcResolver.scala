package transactions.batching.sigmafi.zc

import configs.NodeContext
import node.NodeApi
import node.model.IndexedBox
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import javax.inject.{Inject, Singleton}
import scala.util.Try

/** How many node reads one request may still make. Shared by every lookup the request runs. */
final class ZcBudget(reads: Int) {
  private val left = new AtomicInteger(reads)
  def take(): Boolean = left.getAndUpdate(n => if (n > 0) n - 1 else 0) > 0
  def remaining: Int = left.get()
}

object ZcBudget {
  def none: ZcBudget = new ZcBudget(0)
}

/**
 * What the tracker cannot know about a box it did not see made: the mint behind a bond asset, share or receipt it
 * seeded (`origin`), the script behind a party hash (`script`), and the first box of an offer's or ask's chain
 * (`chainRoot`). Each comes from the node's index, at most one read per call against the caller's [[ZcBudget]], and is
 * kept: an origin or a chain once its block is [[Settled]] deep, a script for good. A lookup that finds nothing is
 * kept too, for [[RetryBlocks]] blocks, so repeated requests for a spammer's box cost one read per window.
 *
 * Every map is bounded: past its cap an entry is dropped at random, which costs at most one more read later.
 */
@Singleton
final class ZcResolver(api: () => NodeApi, tracker: ZcTracker, maxEntries: Int = ZcResolver.MaxEntries,
                       maxScriptChars: Long = ZcResolver.MaxScriptChars) {

  @Inject def this(nodeContext: NodeContext, tracker: ZcTracker) = this(() => nodeContext.getNodeApi, tracker)

  import ZcResolver._

  private val origins = new ConcurrentHashMap[String, ZcOrigin]()
  private val scripts = new ConcurrentHashMap[String, String]()
  private val scriptChars = new AtomicLong(0L)
  /** A box's chain root, and an ERG offer chain's first capital. */
  private val roots = new ConcurrentHashMap[String, (String, Long)]()
  /** What was looked up and not found, to the height it may be tried again. */
  private val missing = new ConcurrentHashMap[String, Int]()
  /** Node reads made, for specs and the audit's cost figures. */
  val reads = new AtomicLong(0L)
  private val learned = new AtomicLong(0L)

  /** Moves whenever an origin or a script is newly kept, so anything derived from the cache knows to rebuild. */
  def generation: Long = learned.get()

  private def height: Int = tracker.view.map(_.book.height).getOrElse(0)

  private def tried(key: String): Boolean = Option(missing.get(key)).exists(_ > height)

  private def miss(key: String): Unit = { bound(missing); missing.put(key, height + RetryBlocks) }

  private def bound[K, V](m: ConcurrentHashMap[K, V]): Unit = if (m.size >= maxEntries) {
    val it = m.keySet().iterator()
    var drop = maxEntries / 8 + 1
    while (it.hasNext && drop > 0) { it.next(); it.remove(); drop -= 1 }
  }

  /** One node read against `budget`; a failed or refused read is None, never an exception. */
  private def read[A](budget: ZcBudget)(f: NodeApi => Try[A]): Option[A] =
    if (!budget.take()) None
    else {
      reads.incrementAndGet()
      Try(f(api())).toOption.flatMap(r => Option(r)).flatMap(_.toOption)
    }

  // ─── origins ───────────────────────────────────────────────────────────────

  /** The mint of `token` from the tracker or the cache, with no read. */
  def knownOrigin(token: String): Option[ZcOrigin] =
    tracker.view.flatMap(_.origin(token)).orElse(Option(origins.get(token)))

  /**
   * The mint of `token`: the box whose id it is, spent under an `Order`, `PricedOrder`, share mint request or
   * `WrapMint`. Anyone can mint a token by another path only with another id, so a spent minter box of that id is the
   * proof. None when unproven, or when the budget is spent first.
   */
  def origin(token: String, budget: ZcBudget): Option[ZcOrigin] =
    knownOrigin(token).orElse {
      val key = "o:" + token
      if (tried(key)) None
      else read(budget)(_.indexedBoxById(token)) match {
        case None => None
        case Some(found) => found.flatMap(minted) match {
          case Some(o) =>
            // Kept at any depth: a fork undoing the mint also undoes the fill that made the state box it proves
            bound(origins)
            if (origins.put(token, o) == null) learned.incrementAndGet()
            Some(o)
          case None =>
            miss(key)
            None
        }
      }
    }

  // ─── scripts ───────────────────────────────────────────────────────────────

  /** The script behind `hash` from the tracker or the cache, with no read. */
  def knownScript(hash: String): Option[String] = tracker.script(hash).orElse(Option(scripts.get(hash)))

  /**
   * Whether `record`'s named script is disclosed, from what is already known: Some(true) found, Some(false) its
   * creating transaction was read and does not carry it, None not looked up. A box naming no script discloses nothing
   * it needs to.
   */
  def knownDisclosed(record: ZcRecord): Option[Boolean] = record.namedHash match {
    case None => Some(true)
    case Some(h) if knownScript(h).isDefined => Some(true)
    case Some(_) if tried("s:" + record.boxId) => Some(false)
    case _ => None
  }

  /**
   * The script `record` names by hash: from the box's creating transaction (variable 64 on any input, then every
   * input and output tree), and for a bond, also the fill that minted its asset, which paid the borrower. None when
   * neither discloses it: zcs1 batchers then skip the box.
   */
  def script(record: ZcRecord, pending: Boolean, budget: ZcBudget): Option[String] =
    record.namedHash.flatMap { hash =>
      knownScript(hash).orElse {
        val key = "s:" + record.boxId
        if (tried(key)) None
        else carried(record.box.transactionId, pending, budget).flatMap { fromBox =>
          remember(fromBox)
          val fromFill = record match {
            case b: ZcBond if !fromBox.contains(hash) =>
              knownOrigin(b.asset).flatMap(o => carried(o.txId, o.height.isEmpty, budget))
            case _ => Some(Map.empty[String, String])
          }
          fromFill.foreach(remember)
          val found = fromBox.get(hash).orElse(fromFill.flatMap(_.get(hash)))
          // Only a lookup that read everything it needed proves the script undisclosed
          if (found.isEmpty && fromFill.isDefined) miss(key)
          found
        }
      }
    }

  /**
   * The scripts a transaction carries, by hash: empty when the node answered that it has no such transaction, None
   * when it could not be asked.
   */
  private def carried(txId: String, pending: Boolean, budget: ZcBudget): Option[Map[String, String]] = {
    val answered: Option[Option[(Seq[Map[String, String]], Seq[String])]] =
      if (pending) read(budget)(_.unconfirmedTransactionById(txId))
        .map(_.map(tx => (tx.inputs.map(_.spendingProof.extension), tx.outputs.map(_.ergoTree))))
      else read(budget)(_.indexedTransactionById(txId))
        .map(_.map(tx => (tx.inputs.flatMap(_.spendingProof.map(_.extension)), (tx.inputs ++ tx.outputs).map(_.box.ergoTree))))
    answered.map(_.fold(Map.empty[String, String]) { case (exts, trees) =>
      (exts.flatMap(_.get(ZcTx.DisclosureVar).toSeq.flatMap(ZcBoxes.disclosedTrees)) ++ trees)
        .filter(_.length <= ZcTx.MaxScriptChars)
        .flatMap(t => Try(Hex.toHexString(Blake2b256.hash(Hex.decode(t)))).toOption.map(_ -> t.toLowerCase)).toMap
    })
  }

  /** Keeps `found`, first dropping scripts no tracked box names when it would not fit, then everything if still full. */
  private def remember(found: Map[String, String]): Unit = found.foreach { case (h, t) =>
    if (scriptChars.get() + t.length > maxScriptChars) {
      val named = tracker.book.map(_.byParty).getOrElse(Map.empty)
      val it = scripts.entrySet().iterator()
      while (it.hasNext) {
        val e = it.next()
        if (!named.contains(e.getKey)) { it.remove(); scriptChars.addAndGet(-e.getValue.length.toLong) }
      }
      if (scriptChars.get() + t.length > maxScriptChars) { scripts.clear(); scriptChars.set(0L) }
    }
    if (scripts.putIfAbsent(h, t) == null) {
      scriptChars.addAndGet(t.length.toLong)
      learned.incrementAndGet()
    }
  }

  // ─── chains ────────────────────────────────────────────────────────────────

  /**
   * The first box of the chain `record` continues, and that box's nanoERG (an ERG offer's original capital), walking
   * predecessors back through the index. None when the walk runs out of budget or meets a box it cannot read.
   */
  def chainRoot(record: ZcRecord, budget: ZcBudget): Option[(String, Long)] = {
    var at: ZcRecord = record
    var path = List.empty[String]
    var answer: Option[(String, Long)] = None
    var done = false
    var steps = 0
    while (!done) {
      Option(roots.get(at.boxId)) match {
        case Some(root) => answer = Some(root); done = true
        case None => predecessor(at) match {
          case None =>
            answer = Some(at.boxId -> at.box.value)
            done = true
          case Some(prev) if steps < MaxChainSteps =>
            path = at.boxId :: path
            steps += 1
            read(budget)(_.indexedBoxById(prev)).flatten.flatMap(b => ZcBoxes.read(b.box)) match {
              case Some(r) => at = r
              case None => done = true
            }
          case Some(_) => done = true
        }
      }
    }
    answer.foreach { root =>
      bound(roots)
      (at.boxId :: path).foreach(id => roots.put(id, root))
    }
    answer
  }

  private def predecessor(r: ZcRecord): Option[String] = r match {
    case o: ZcOffer => o.predecessor
    case a: ZcAsk => a.predecessor
    case _ => None
  }
}

object ZcResolver {

  /** Blocks deep before an origin is kept for good: a fork can still undo a mint shallower than this. */
  final val Settled = 30

  /** Blocks before a lookup that found nothing is tried again. */
  final val RetryBlocks = 30

  final val MaxEntries = 50000
  final val MaxScriptChars = 8000000L

  /** Predecessors walked per chain lookup; a longer chain resumes from the cache on the next request. */
  final val MaxChainSteps = 64

  /**
   * The origin `found` proves: spent, under a minting contract. A share request's record carries its terms and the
   * unit it held; a wrap request's, the token it wrapped.
   */
  private[zc] def minted(found: IndexedBox): Option[ZcOrigin] =
    found.spentTransactionId.flatMap { tx =>
      ZcBoxes.read(found.box).filter(ZcBook.isMinter).map(r => ZcOrigin.of(r, tx, found.spendingHeight))
    }
}
