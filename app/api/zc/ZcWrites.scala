package api.zc

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.LDPoolsApi
import api.LithosApiErrors.{LithosBadRequest, LithosUnavailable}
import api.models.LDNftLookupRequest
import configs.NodeContext
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue, Json}
import transactions.batching.sigmafi.zc.{ZcResolver, ZcTracker}
import transactions.engine.execution.ZcWriteExecution
import transactions.engine.wallet.EngineFunding
import transactions.engine.{EngineIntent, TransactionEngine, ZcIntent}

import java.util.concurrent.TimeoutException
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/** The ZC writes: each check runs here and signs nothing; each send is handed to the transaction engine. */
@Singleton
class ZcWrites(node: NodeContext, engine: ActorRef, tracker: ZcTracker, resolver: ZcResolver, config: Configuration,
               system: ActorSystem, capability: Seq[String] => Set[String]) {

  @Inject def this(node: NodeContext, @Named("transaction-engine") engine: ActorRef, tracker: ZcTracker,
                   resolver: ZcResolver, config: Configuration, system: ActorSystem, pools: LDPoolsApi) =
    this(node, engine, tracker, resolver, config, system, ZcWrites.provisionNfts(pools) _)

  def this(node: NodeContext, engine: ActorRef, tracker: ZcTracker, resolver: ZcResolver, config: Configuration,
           system: ActorSystem) = this(node, engine, tracker, resolver, config, system, (_: Seq[String]) => Set.empty[String])

  private lazy val checks = new ZcWriteExecution(node, EngineFunding(engine, EngineFunding.AskTimeout, system.dispatcher),
    tracker, resolver, capability = capability)

  private def body(b: Option[JsValue]): JsObject =
    b.flatMap(_.asOpt[JsObject]).getOrElse(throw LithosBadRequest("the body must be a JSON object"))

  /** A path's body may be left out: `{}`. */
  private def optional(b: Option[JsValue]): JsObject = b.fold(Json.obj())(j => body(Some(j)))

  private def submit(intent: ZcIntent): JsObject = {
    val now = System.currentTimeMillis()
    val scheduled = TransactionEngine.Schedule(EngineIntent.Zc(intent), now, now + ZcWrites.SendWindow.toMillis)
    val answer = try Await.result(engine.ask(scheduled)(Timeout(ZcWrites.AnswerWait)), ZcWrites.AnswerWait) catch {
      case _: TimeoutException => throw LithosUnavailable("the transaction engine did not answer in time; the request may " +
        "still have reached the node, so check the portfolio before sending it again")
    }
    answer match {
      case result: JsObject => result
      case TransactionEngine.Deferred(_, reason) =>
        throw LithosUnavailable(s"the transaction engine did not run the request, so nothing was sent: $reason")
      case other => throw new IllegalStateException(s"the transaction engine answered ${other.getClass.getSimpleName}")
    }
  }

  def createOrder(b: Option[JsValue], send: Boolean): JsObject =
    if (send) submit(ZcIntent.CreateOrder(body(b), tracker, resolver)) else checks.createOrder(body(b), send = false)

  def createOffer(b: Option[JsValue], send: Boolean): JsObject =
    if (send) submit(ZcIntent.CreateOffer(body(b), tracker, resolver)) else checks.createOffer(body(b), send = false)

  def createAsk(b: Option[JsValue], send: Boolean): JsObject =
    if (send) submit(ZcIntent.CreateAsk(body(b), tracker, resolver)) else checks.createAsk(body(b), send = false)

  def cancel(boxId: String, send: Boolean): JsObject =
    if (send) submit(ZcIntent.Cancel(boxId.trim.toLowerCase, tracker, resolver)) else checks.cancel(boxId.trim.toLowerCase, send = false)

  /**
   * Any other write: `action` as [[ZcIntent.Write.Actions]] names it, `id` the box, bond asset or token in its path. Ids
   * are checked before anything is queued.
   */
  def write(action: String, id: Option[String], b: Option[JsValue], send: Boolean): JsObject = {
    val key = id.map(_.trim.toLowerCase)
    key.foreach(k => if (!k.matches("[0-9a-f]{64}")) throw LithosBadRequest(s"'$k' is not a 64-character hex id"))
    val parsed = optional(b)
    if (send) submit(ZcIntent.Write(action, key, parsed, tracker, resolver, capability))
    else ZcIntent.Write.run(checks, action, key, parsed, send = false)
  }
}

object ZcWrites {
  final val SendWindow: FiniteDuration = 30.seconds
  final val AnswerWait: FiniteDuration = SendWindow + 40.seconds

  /** Live LithosDex provision NFTs among `ids`; a failed lookup refuses, since a lot must never hold one unwrapped. */
  def provisionNfts(pools: LDPoolsApi)(ids: Seq[String]): Set[String] =
    if (ids.isEmpty) Set.empty
    else Try(pools.lookupNfts(LDNftLookupRequest(ids))) match {
      case Success(found) => found.nfts.filter(_.provisionNft).map(_.tokenId).toSet
      case Failure(ex) => throw LithosUnavailable(s"could not check the lot's items for LithosDex provision NFTs: ${ex.getMessage}", ex)
    }
}
