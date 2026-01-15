package mutations

import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.appkit.{BlockchainContext, ErgoId, JavaHelpers}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.InputUTXO

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
    }
    logger.info(s"Returned ${inputBoxes.size} boxes from BoxLoader")

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
