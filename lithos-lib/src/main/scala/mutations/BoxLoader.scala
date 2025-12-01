package mutations

import org.ergoplatform.appkit.{BlockchainContext, JavaHelpers}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.InputUTXO

import scala.collection.mutable

class BoxLoader(ctx: BlockchainContext) {
  private val boxStack = mutable.Stack.empty[InputUTXO]
  private val logger: Logger = LoggerFactory.getLogger("BoxLoader")
  def loadBoxes: BoxLoader = {
    boxStack.clear()
    val boxes = JavaHelpers.toIndexedSeq(ctx.getDataSource.getUnconfirmedUnspentWalletBoxes)
      .map(InputUTXO(_)).sortBy(_.value)
    for(i <- boxes) boxStack.push(i)
    logger.info(s"Loaded ${boxStack.size} boxes in BoxLoader")
    this
  }

  def getInputs(value: Long): Seq[InputUTXO] = {
    var currentValue = 0L
    var inputBoxes   = Seq.empty[InputUTXO]
    while(currentValue < value){
      if(boxStack.isEmpty) {
        throw new RuntimeException(s"Failed to find enough InputUTXOs for value ${value}" +
          s" (only got ${inputBoxes.size} for ${currentValue})")
      }
      val input    = boxStack.pop()
      currentValue = currentValue + input.value
      inputBoxes = inputBoxes :+ input
    }
    logger.info(s"Returned ${inputBoxes.size} boxes from BoxLoader")

    inputBoxes.toSeq
  }

  def pushMempoolUTXO(output: InputUTXO): BoxLoader = {
    boxStack.push(output)
    this
  }
}
