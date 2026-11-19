package transactions.engine

import play.api.libs.json.{JsObject, Json}
import transactions.batching.sigmafi.zc.{ZcResolver, ZcTracker}
import transactions.engine.execution.ZcWriteExecution

/**
 * One ZC write that signs, carried to the engine as an ordinary intent. It carries the tracker and resolver its
 * execution reads, so the engine supplies only its wallet and its stop signal.
 */
sealed trait ZcIntent {
  def tracker: ZcTracker
  def resolver: ZcResolver
  /** Which of the given token ids are capability tokens, which a lot refuses. */
  def capability: Seq[String] => Set[String] = _ => Set.empty
  def execute(execution: ZcWriteExecution): JsObject
}

object ZcIntent {

  /** Largest body queued, as compact JSON: a create body with a 20-point curve is about 2 KB. */
  final val MaxBodyChars = 16384

  private def hex(id: String): Boolean = id.matches("[0-9a-fA-F]{64}")

  private[engine] def admissible(intent: ZcIntent): Boolean = intent match {
    case Cancel(boxId, _, _) => hex(boxId)
    case CreateOrder(b, _, _) => Json.stringify(b).length <= MaxBodyChars
    case CreateOffer(b, _, _) => Json.stringify(b).length <= MaxBodyChars
    case CreateAsk(b, _, _) => Json.stringify(b).length <= MaxBodyChars
    case Write(action, id, b, _, _, _) => Write.Actions(action) && id.forall(hex) && Json.stringify(b).length <= MaxBodyChars
  }

  final case class CreateOrder(body: JsObject, tracker: ZcTracker, resolver: ZcResolver) extends ZcIntent {
    def execute(e: ZcWriteExecution): JsObject = e.createOrder(body, send = true)
  }
  final case class CreateOffer(body: JsObject, tracker: ZcTracker, resolver: ZcResolver) extends ZcIntent {
    def execute(e: ZcWriteExecution): JsObject = e.createOffer(body, send = true)
  }
  final case class CreateAsk(body: JsObject, tracker: ZcTracker, resolver: ZcResolver) extends ZcIntent {
    def execute(e: ZcWriteExecution): JsObject = e.createAsk(body, send = true)
  }
  final case class Cancel(boxId: String, tracker: ZcTracker, resolver: ZcResolver) extends ZcIntent {
    def execute(e: ZcWriteExecution): JsObject = e.cancel(boxId, send = true)
  }

  /** Every other write: `action` names it, `id` the box, bond asset or token its path names. */
  final case class Write(action: String, id: Option[String], body: JsObject, tracker: ZcTracker, resolver: ZcResolver,
                         override val capability: Seq[String] => Set[String] = _ => Set.empty)
    extends ZcIntent {
    def execute(e: ZcWriteExecution): JsObject = Write.run(e, action, id, body, send = true)
  }

  object Write {
    val Actions: Set[String] = Set("createLot", "createWrap", "fractionalize", "fillOrder", "buyAsk", "buyLot", "buyVault",
      "repay", "recollateralize", "liquidate", "claim", "mint", "pull", "unwrap")

    /** Runs `action` on `e`: the one dispatch the checks and the engine share. */
    def run(e: ZcWriteExecution, action: String, id: Option[String], body: JsObject, send: Boolean): JsObject = {
      def path: String = id.getOrElse(throw new IllegalArgumentException(s"$action needs an id"))
      action match {
        case "createLot" => e.createLot(body, send)
        case "createWrap" => e.createWrap(body, send)
        case "fractionalize" => e.fractionalize(path, body, send)
        case "fillOrder" => e.fillOrder(path, body, send)
        case "buyAsk" => e.buyAsk(path, body, send)
        case "buyLot" => e.buyLot(path, body, send)
        case "buyVault" => e.buyVault(path, body, send)
        case "repay" => e.repay(path, body, send)
        case "recollateralize" => e.recollateralize(path, body, send)
        case "liquidate" => e.liquidate(path, body, send)
        case "claim" => e.claim(path, body, send)
        case "mint" => e.mint(path, body, send)
        case "pull" => e.pull(path, body, send)
        case "unwrap" => e.unwrap(path, body, send)
        case other => throw new IllegalArgumentException(s"unknown ZC write $other")
      }
    }
  }
}
