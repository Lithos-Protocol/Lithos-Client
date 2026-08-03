package mutations

import node.MutationConversions._
import node.NodeApi
import node.model.{ConfirmationRange, Paging}
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.collection.mutable
import scala.util.{Failure, Success}

class BoxLoader(ctx: BlockchainContext, nodeApi: NodeApi) {
  private val boxStack = mutable.Stack.empty[InputUTXO]
  private val logger: Logger = LoggerFactory.getLogger("BoxLoader")
  private val usedBoxes: mutable.HashSet[ErgoId] = mutable.HashSet.empty[ErgoId]

  def loadBoxes: BoxLoader = {
    boxStack.clear()
    val boxes = fetchUnspent.filter(i => !usedBoxes(i.id)).sortBy(_.value)
    for (i <- boxes) boxStack.push(i)
    logger.info(s"Loaded ${boxStack.size} boxes in BoxLoader")
    this
  }

  private def fetchUnspent: Seq[InputUTXO] = {
    var loaded = Seq.empty[InputUTXO]
    var paging = Paging(0, BoxLoader.PageSize)
    var exhausted = false
    while (!exhausted && loaded.size < BoxLoader.MaxBoxes) {
      nodeApi.walletUnspentBoxes(ConfirmationRange.Default, paging) match {
        case Success(page) =>
          loaded = loaded ++ page.map(_.toInputUTXO(ctx))
          exhausted = page.size < paging.limit
          paging = paging.next
        case Failure(ex) =>
          logger.error(s"Failed to load unspent wallet boxes: ${ex.getMessage}", ex)
          exhausted = true
      }
    }
    loaded.take(BoxLoader.MaxBoxes)
  }

  def getInputs(value: Long): Seq[InputUTXO] = {
    var currentValue = 0L
    var inputBoxes   = Seq.empty[InputUTXO]
    while(currentValue < value){
      if(boxStack.isEmpty) {
        throw new NotEnoughInputsException(s"Failed to find enough InputUTXOs for value ${value}" +
          s" (only got ${inputBoxes.size} for ${currentValue})")
      }
      val input    = boxStack.pop()
      if(!usedBoxes(input.id)) {
        currentValue = currentValue + input.value
        usedBoxes += input.id
        inputBoxes = inputBoxes :+ input
      }
      if(value == UTXO.MIN_FEE && input.tokens.nonEmpty && input.value == UTXO.MIN_FEE){
        if(boxStack.isEmpty) {
          throw new NotEnoughInputsException(s"Failed to find enough InputUTXOs for value ${value}" +
            s" (only got ${inputBoxes.size} for ${currentValue}, but input had a token!)")
        }
        val nextInput = boxStack.pop()
        if(!usedBoxes(nextInput.id)) {
          currentValue = currentValue + nextInput.value
          usedBoxes += nextInput.id
          inputBoxes = inputBoxes :+ nextInput
        }
      }
    }
    //logger.info(s"Returned ${inputBoxes.size} boxes from BoxLoader")

    inputBoxes.toSeq
  }
  def getInputs(value: Long, tokenId: ErgoId, tokenAmount: Long): Seq[InputUTXO] = {
    var currentValue = 0L
    var currentTokens = 0L
    var inputBoxes   = Seq.empty[InputUTXO]
    while(currentValue < value || currentTokens < tokenAmount){
      if(boxStack.isEmpty) {
        throw new NotEnoughInputsException(s"Failed to find enough InputUTXOs for value $value and tokens $tokenAmount" +
          s" (only got ${inputBoxes.size} for value $currentValue and tokens $currentTokens)")
      }
      val input    = boxStack.pop()
      if(!usedBoxes(input.id)) {
        currentValue = currentValue + input.value
        val inputTokens = input.tokens.find(_.id == tokenId).map(_.amount)
        currentTokens = currentTokens + inputTokens.getOrElse(0L)
        usedBoxes += input.id
        inputBoxes = inputBoxes :+ input
      }
    }
    //logger.info(s"Returned ${inputBoxes.size} boxes from BoxLoader")

    inputBoxes.toSeq
  }

  def getAll = {
    val all = boxStack.toArray
    usedBoxes ++= all.map(_.id)
    boxStack.clear()
    all
  }

  def pushMempoolUTXO(output: InputUTXO): BoxLoader = {
    boxStack.push(output)
    this
  }
}

object BoxLoader {
  final val PageSize = 500
  final val MaxBoxes = 500
}
