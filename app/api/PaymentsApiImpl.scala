package api

import configs.NodeConfig
import model.PaymentTransaction
import play.api.Configuration
import play.api.cache.SyncCacheApi
import utils.{NISPTreeCache, PayoutRecord}


/**
  * Provides a default implementation for [[PaymentsApi]].
  */
class PaymentsApiImpl extends PaymentsApi {
  /**
   * @inheritdoc
   */
  override def getPayments(limit: Option[Int], offset: Option[Int], config: Configuration, cache: SyncCacheApi): List[PaymentTransaction] = {
    // TODO: Uses tracked payouts from sync for right now
    val nodeConfig = new NodeConfig(config)
    val page = ApiHelper.handlePagination(offset, limit)
    val cached = cache.getOrElseUpdate[Seq[PayoutRecord]](NISPTreeCache.TRACKED_PAYOUTS)(Seq.empty[PayoutRecord])
    cached.slice(page._1, page._1 + page._2)
      .map(p => PaymentTransaction(p.txId, p.utxoId, p.amount, p.score, p.creationHeight, p.blockId, p.minedHeight))
      .toList
  }

  /** TODO: Implement better logic
   * Generally this is an expensive call, mostly because explorer takes a while. Either we only do tracked payouts
   * from sync or we have separate task to build payout history. Both options suck, but at least tracked payouts is simple.
   * Maybe add option to sync payouts from different start height?
   */

  //  override def getPayments(limit: Option[Int], offset: Option[Int], config: Configuration, cache: SyncCacheApi): List[PaymentTransaction] = {
  //
  //    val nodeConfig = new NodeConfig(config)
  //    val page = ApiHelper.handlePagination(offset, limit)
  //    val cached = cache.get[Seq[PaymentTransaction]]("payouts").getOrElse(Seq.empty[PaymentTransaction])
  //    if(page._1 + page._2 > cached.size) {
  //      nodeConfig.getClient.execute {
  //        ctx =>
  //
  //          val txs = getTransactions(ctx, nodeConfig.prover.getAddress.toString, page._1 + page._2, page._2)
  //
  //
  //          val payouts = txs.map {
  //            s =>
  //              // TODO: Naive impl of score
  //              val totalScore = ErgoValue.fromHex(s._1.getInputs.get(0).getAdditionalRegisters.get("R6").serializedValue)
  //                .getValue.asInstanceOf[CBigInt].wrappedValue
  //              val totalReward = BigInt(s._1.getInputs.get(0).getAdditionalRegisters.get("R7").renderedValue)
  //              val score = (BigInt(s._2.getValue) * totalScore) / totalReward
  //              PaymentTransaction(s._1.getId, s._1.getInputs.get(0).getBoxId, s._2.getValue, score.toLong)
  //          }.toList
  //          cache.set("payouts", payouts)
  //          payouts.slice(page._1, page._1 + page._2)
  //      }
  //    }else{
  //      cached.slice(page._1, page._1 + page._2).toList
  //    }
  //  }
  //  def getTransactions(ctx: BlockchainContext, proverAddress: String, offset: Int, limit: Int): Seq[(TransactionInfo, OutputInfo)] = {
  //    val dataSource = ctx.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]
  //    var txsList = Seq.empty[(TransactionInfo, OutputInfo)]
  //    var expOffset = 0
  //
  //    while(txsList.size < offset + limit){
  //      val txs = dataSource.getExplorerApi.getApiV1AddressesP1Transactions(proverAddress, expOffset, limit, false).execute().body
  //      if(txs.getTotal == 0){
  //        return txsList
  //      }
  //      val filteredTxs = JavaHelpers.toIndexedSeq(txs.getItems)
  //        .filter(t => t.getInputs.get(0).getAddress == Helpers.payoutContract(ctx).address(ctx).toString)
  //      val withMiner = filteredTxs.map {
  //        t =>
  //          val outputs = JavaHelpers.toIndexedSeq(t.getOutputs)
  //          val feeAddress = Contract(ErgoTreePredef.feeProposition(720)).address(ctx).toString
  //          val feeIndex = outputs.indexWhere(o => o.getAddress == feeAddress)
  //          t -> outputs.zipWithIndex.find(o => o._1.getAddress == proverAddress && o._2 < feeIndex)
  //      }
  //      val hasMiner = withMiner.filter(_._2.isDefined).map(h => h._1 -> h._2.get._1)
  //      txsList = txsList ++ hasMiner
  //      expOffset = expOffset + limit
  //    }
  //    txsList
  //  }
}