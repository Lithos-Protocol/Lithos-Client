package api

import api.LithosApiErrors.LithosUnavailable
import configs.NodeContext
import lfsm.contracts.RollupContracts
import models.PaymentTransaction
import node.model.{IndexedBox, IndexedTransaction, Paging}
import play.api.Configuration
import play.api.cache.SyncCacheApi

import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/** Reconstructs confirmed local payout history from the node's address index. */
@Singleton
class PaymentsApiImpl @Inject()(nodeContext: NodeContext) extends PaymentsApi {

  private val IndexPageSize = 100

  /** Maximum address-index pages scanned before reporting the requested history unavailable. */
  private val MaxIndexPages = 200

  /** The protocol script itself, not an address supplied by an index response. */
  private lazy val payoutErgoTree: String =
    RollupContracts.mkPayoutContract(nodeContext.getNetwork).ergoTreeHex

  /** @inheritdoc */
  override def getPayments(limit: Option[Int],
                           offset: Option[Int],
                           config: Configuration,
                           cache: SyncCacheApi): List[PaymentTransaction] = {
    val (start, count) = ApiHelper.handlePagination(offset, limit)
    val minerAddress = nodeContext.getNodeWallet.p2pk.toString
    val minerErgoTree = nodeContext.getNodeWallet.contract.ergoTreeHex
    val nodeApi = nodeContext.getNodeApi

    var rawOffset = 0
    var matched = 0L
    var selected = Vector.empty[PaymentTransaction]
    var exhausted = false
    var pagesRead = 0

    // Apply pagination after filtering address history to protocol payouts.
    while (!exhausted && selected.size < count) {
      if (pagesRead >= MaxIndexPages)
        throw LithosUnavailable(
          s"payment history scan reached its bound of $MaxIndexPages index pages " +
            s"(${MaxIndexPages * IndexPageSize} transactions) before filling this page; " +
            "raise the bound or request a lower offset")
      pagesRead += 1
      val paging = Paging(rawOffset, IndexPageSize)
      // Normalize synchronous client failures with the NodeApi failure result.
      val page = Try(nodeApi.indexedTransactionsByAddress(minerAddress, paging)).flatten match {
        case Success(found) => found
        case Failure(cause) =>
          throw LithosUnavailable(
            s"could not read payment history from the node index at offset $rawOffset: " +
              s"is the indexed node reachable and current? (${cause.getMessage})",
            cause)
      }

      page.items.iterator
        .flatMap(paymentFrom(_, minerAddress, minerErgoTree))
        .foreach { payment =>
          if (matched >= start.toLong && selected.size < count)
            selected :+= payment
          matched += 1L
        }

      val consumed = page.items.size
      val nextOffset = rawOffset.toLong + consumed.toLong
      val reachedReportedTotal = page.total > 0 && nextOffset >= page.total.toLong

      // A short page is terminal when the node omits `total`; advance by the rows actually returned.
      exhausted = consumed == 0 || reachedReportedTotal || (page.total <= 0 && consumed < paging.limit)
      if (!exhausted) {
        if (nextOffset > Int.MaxValue)
          throw LithosUnavailable("payment history exceeds the node index paging range")
        rawOffset = nextOffset.toInt
      }
    }

    selected.toList
  }

  /** Matches confirmed transactions that spend the payout script and pay the primary miner address. */
  private def paymentFrom(tx: IndexedTransaction,
                          minerAddress: String,
                          minerErgoTree: String): Option[PaymentTransaction] = {
    if (tx.numConfirmations <= 0) None
    else {
      val payoutInput: Option[IndexedBox] = tx.inputs.find(_.ergoTree == payoutErgoTree)
      val minerOutput: Option[IndexedBox] = tx.outputs.find(out =>
        out.address == minerAddress && out.ergoTree == minerErgoTree)

      for {
        input <- payoutInput
        output <- minerOutput
      } yield PaymentTransaction(
        transactionId = tx.id,
        payoutUTXO = input.boxId,
        amount = output.value,
        score = None,
        paymentHeight = tx.inclusionHeight,
        blockId = None,
        minedBlockHeight = None)
    }
  }
}
