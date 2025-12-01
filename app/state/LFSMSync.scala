package state

import lfsm.{LFSMPhase, NISPTree}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoClient, ErgoProver, ErgoValue}
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.SyncCacheApi
import utils.Helpers.{evalContract, holdingContract, payoutContract}
import utils.NISPTreeCache.TREE_SET
import utils.NISPTreeCache

import scala.collection.JavaConverters

object LFSMSync {
  private val logger: Logger = LoggerFactory.getLogger("LFSMSync")

  def searchHoldingContracts(fullBlock: FullBlock, client: ErgoClient, cache: SyncCacheApi, prover: ErgoProver): Unit = {
    client.execute {
      ctx =>
        val txs = JavaConverters.asScalaIterator(fullBlock.getBlockTransactions.getTransactions.iterator())
        val holdingTxs = txs.filter {
          x =>
            JavaConverters
              .asScalaIterator(x.getOutputs.iterator()).toIndexedSeq
              .map(_.getErgoTree).contains(Hex.toHexString(holdingContract(ctx).propBytes))
        }
        holdingTxs.foreach {
          e =>
            val output = JavaConverters.asScalaIterator(e.getOutputs.iterator()).toIndexedSeq.find {
              o => o.getErgoTree == Hex.toHexString(holdingContract(ctx).propBytes)
            }.get
            val numMiners = output.getAdditionalRegisters.getOrDefault("R5", "none")
            numMiners match {
              case "none" =>
                logger.warn("Found invalid holding contract, skipping it during sync process")
              case ergoVal =>
                if (ergoVal == ErgoValue.of(0).toHex) {
                  logger.info(s"Found new holding contract at block ${fullBlock.getHeader.getHeight}")
                  NISPTreeCache.cacheNewHolding(ctx, output, cache)
                } else if (ErgoValue.fromHex(ergoVal).getValue.asInstanceOf[Int] > 0) {
                  logger.info(s"Found existing holding contract at block ${fullBlock.getHeader.getHeight}")
                  NISPTreeCache.cacheExistingHolding(ctx, prover, e.getInputs.get(0), output, cache)
                } else {
                  logger.warn("Found invalid holding contract, skipping it during sync process")
                }
            }
        }
    }
  }

  def searchEvalContracts(fullBlock: FullBlock, client: ErgoClient, cache: SyncCacheApi, prover: ErgoProver): Unit = {
    client.execute {
      ctx =>
        val txs = JavaConverters.asScalaIterator(fullBlock.getBlockTransactions.getTransactions.iterator())
        val evalTxs = txs.filter {
          x =>
            JavaConverters
              .asScalaIterator(x.getOutputs.iterator()).toIndexedSeq
              .map(_.getErgoTree).contains(Hex.toHexString(evalContract(ctx).propBytes))
        }
        evalTxs.foreach {
          e =>
            val output = JavaConverters.asScalaIterator(e.getOutputs.iterator()).toIndexedSeq.find {
              o => o.getErgoTree == Hex.toHexString(evalContract(ctx).propBytes)
            }.get
            val input = e.getInputs.get(0)
            val optNISPTree = cache.get[NISPTree](input.getBoxId)
            logger.info(s"Checking payout for ${input.getBoxId}")
            optNISPTree match {
              case Some(nispTree) =>
                nispTree.phase match {
                  case LFSMPhase.HOLDING =>
                    logger.info(s"Found new evaluation contract at block ${fullBlock.getHeader.getHeight}")
                    NISPTreeCache.cacheNewEval(ctx, input, output, cache)
                  case LFSMPhase.EVAL =>
                    logger.info(s"Found existing evaluation contract at block ${fullBlock.getHeader.getHeight}")
                    NISPTreeCache.cacheExistingEval(ctx, input, e.getInputs.get(1), output, cache, prover)
                  case _ =>
                    logger.error("NISP Tree in invalid phase for evaluation")
                }
              case None =>
                logger.error(s"Could not find NISP Tree ${input.getBoxId}")
            }


        }
    }
  }

  def searchPayoutContracts(fullBlock: FullBlock, client: ErgoClient, cache: SyncCacheApi, prover: ErgoProver): Unit = {
    client.execute {
      ctx =>
        val txs = JavaConverters.asScalaIterator(fullBlock.getBlockTransactions.getTransactions.iterator())
        val payoutTxs = txs.filter {
          x =>
            JavaConverters
              .asScalaIterator(x.getOutputs.iterator()).toIndexedSeq
              .map(_.getErgoTree).contains(Hex.toHexString(payoutContract(ctx).propBytes)) ||
              cache.get[Seq[String]](TREE_SET).get.contains(x.getInputs.get(0).getBoxId)
        }
        payoutTxs.foreach {
          e =>
            val output = JavaConverters.asScalaIterator(e.getOutputs.iterator()).toIndexedSeq.find {
              o => o.getErgoTree == Hex.toHexString(payoutContract(ctx).propBytes)
            }
            val input = e.getInputs.get(0)
            val optNISPTree = cache.get[NISPTree](input.getBoxId)
            logger.info(s"Checking payout for ${input.getBoxId}")
            optNISPTree match {
              case Some(nispTree) =>
                nispTree.phase match {
                  case LFSMPhase.EVAL =>
                    logger.info(s"Found new payout contract at block ${fullBlock.getHeader.getHeight}")
                    NISPTreeCache.cacheNewPayout(ctx, input, output.get, cache)
                  case LFSMPhase.PAYOUT =>
                    logger.info(s"Found existing payout contract at block ${fullBlock.getHeader.getHeight}")
                    NISPTreeCache.cacheExistingPayout(ctx, input, output, cache, prover)
                  case _ =>
                    logger.error("NISP Tree in invalid phase for payouts")
                }
              case None =>
                logger.error(s"Could not find NISP Tree ${input.getBoxId}")
            }

          //TODO Add back FP stuff next week

        }
    }
  }
}
