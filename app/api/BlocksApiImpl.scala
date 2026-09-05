package api

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import utils.Globals
import lfsm.LFSMPhase
import org.bouncycastle.util.encoders.Hex
import models.ApiError
import models.BlockMiners
import models.PoolBlock
import play.api.cache.SyncCacheApi
import state.messages.RollupMessages.{CurrentRollup, GetCurrentRollup}

import javax.inject.{Inject, Named}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
/**
  * Provides a default implementation for [[BlocksApi]].
  */
class BlocksApiImpl @Inject()(@Named("sync-handler") syncHandler: ActorRef) extends BlocksApi {
  private implicit val timeout: Timeout = Timeout(5.seconds)
  /**
    * @inheritdoc
    */
  override def getBlockById(utxoId: String, cache: SyncCacheApi): Option[PoolBlock] = {
    val optRollup = Globals.syncView.rollups.collectFirst { case (id, tree) if id == utxoId => tree }
    optRollup match {
      case Some(rollup) =>
        val phase = rollup.phase match {
          case LFSMPhase.HOLDING => "HOLDING"
          case LFSMPhase.EVAL => "EVAL"
          case LFSMPhase.PAYOUT => "PAYOUT"
        }
        Some(PoolBlock(utxoId, rollup.blockId, rollup.startHeight, rollup.numMiners, phase))
      case None => None
    }
  }

  /**
    * @inheritdoc
    */
  override def getBlockMinersById(utxoId: String, cache: SyncCacheApi): Option[List[BlockMiners]] = {
    Globals.syncView.rollups.collectFirst { case (id, metadata) if id == utxoId => metadata.blockId }
      .flatMap { blockId =>
        Await.result(syncHandler ? GetCurrentRollup(blockId), timeout.duration) match {
          case CurrentRollup(_, rollup, _, _) => Some(rollup)
          case _ => None
        }
      }.map { rollup =>
        rollup.dictionary.foldKeys(List.empty[BlockMiners]) { (miners, key) =>
          require(key.length == rollup.dictionary.parameters.keySize,
            s"Rollup ${rollup.blockId} contains an invalid ${key.length}-byte miner key")
        BlockMiners(Hex.toHexString(key)) :: miners
        }.reverse
      }
  }

  /**
    * @inheritdoc
    */
  override def getBlocksByHeight(fromHeight: Option[Int], toHeight: Option[Int], cache: SyncCacheApi): List[String] = {
    // TODO: Implement better logic
    val blocks = Globals.syncView.rollups
    val startHeight = fromHeight.getOrElse(0)
    val endHeight = toHeight.flatMap{h => if(h < 1) None else Some(h)}.getOrElse(10000000)
    blocks.filter(b => b._2.startHeight >= startHeight && b._2.startHeight < endHeight).map(_._1).toList
  }

  /**
    * @inheritdoc
    */
  override def getContractIds(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[String] = {
    val set = Globals.syncView.rollups.map(_._1)
    val page = ApiHelper.handlePagination(offset, limit)
    set.slice(page._1, page._1 + page._2).toList
  }

  /**
    * @inheritdoc
    */
  override def getPoolBlocksByIds(requestBody: List[String], cache: SyncCacheApi): List[Option[PoolBlock]] = {
    for (r <- requestBody) yield getBlockById(r, cache)
  }

  override def getAllPoolBlocks(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[PoolBlock] = {
    val set = getContractIds(limit, offset, cache)
    // TODO: Dont use .get, because trees are individually synced now and treeSet not guaranteed to map to Rollup
    (for(id <- set) yield getBlockById(id, cache).toSeq).flatten.sortBy(_.blockHeight)
  }
}
