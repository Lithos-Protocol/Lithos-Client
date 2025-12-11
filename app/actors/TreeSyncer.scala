package actors


import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import lfsm.NISPTree
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration

import javax.inject.Inject

class TreeSyncer @Inject() (config: Configuration, @Assisted tree: NISPTree) extends Actor {
  val logger: Logger = LoggerFactory.getLogger("TreeSyncer-"+tree.blockId)
  override def receive: Receive = {
    case BlockMessage(fb) =>

  }
}

object TreeSyncer {

  trait SyncFactory {
    def apply(tree: NISPTree): Actor
  }
}