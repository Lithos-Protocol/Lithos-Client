package mutations

import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.appkit.{BlockchainContext, ErgoId, JavaHelpers}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.collection.mutable

class BoxLoader(ctx: BlockchainContext) {
  private val boxStack = mutable.Stack.empty[InputUTXO]
  private val logger: Logger = LoggerFactory.getLogger("BoxLoader")
  private val usedBoxes: mutable.HashSet[ErgoId] = mutable.HashSet.empty[ErgoId]
  def loadBoxes: BoxLoader = {
    boxStack.clear()
    var totalAdded = 0
    do {
      val boxes = JavaHelpers.toIndexedSeq(ctx.getDataSource.getUnspentWalletBoxes)
        .map(InputUTXO(_)).filter(i => !usedBoxes(i.id)).sortBy(_.value)
      totalAdded += boxes.size
      for (i <- boxes) boxStack.push(i)
    }while(totalAdded < 500)
    logger.info(s"Loaded ${boxStack.size} boxes in BoxLoader")
    this
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
