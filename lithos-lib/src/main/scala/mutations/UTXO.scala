package work.lithos.mutations

import org.ergoplatform.appkit.{BlockchainContext, ErgoId, ErgoValue, OutBox, Parameters}
import sigma.Coll
import sigmastate.utils.Helpers

import scala.util.{Failure, Success, Try}

case class UTXO(contract: Contract, value: Long,
                tokens: Seq[Token] = Seq.empty[Token],
                registers: Seq[ErgoValue[_]] = Seq.empty[ErgoValue[_]],
                creationHeight: Option[Int] = None) {

  def setTokens(tokens: Token*): UTXO = this.copy(tokens = tokens)

  def setRegs(regs: ErgoValue[_]*): UTXO = this.copy(registers = regs)

  def withReg(idx: Int, reg: ErgoValue[_]): UTXO = {
    setRegs(registers.patch(idx, Seq(reg), 1): _*)
  }

  def setCreationHeight(height: Int): UTXO = this.copy(creationHeight = Some(height))

  def setValue(value: Long): UTXO = this.copy(value = value)
  def subValue(amnt: Long):  UTXO = this.copy(value = value - amnt)
  def addValue(amnt: Long):  UTXO = this.copy(value = value + amnt)

  def setContract(contract: Contract): UTXO = this.copy(contract = contract)

  def addToken(token: Token): UTXO = {
    val tokenIdx = tokens.indexWhere(t => t.id.toString == token.id.toString)
    val optToken: Option[Token] = if(tokenIdx != -1) Some(tokens(tokenIdx)) else None
    optToken match {
      case Some(value) =>
        setTokens(tokens.patch(tokenIdx, Seq(value + token.amount), 1): _*)
      case None =>
        addToken(token)
    }
  }

  def removeToken(tokenId: ErgoId, tokenAmount: Long): UTXO = {
    if(tokenAmount > 0) {
      val tokenIdx = tokens.indexWhere(t => t.id.toString == tokenId.toString)
      val optToken: Option[Token] = if (tokenIdx != -1) Some(tokens(tokenIdx)) else None
      optToken match {
        case Some(value) =>
          if (value.amount - tokenAmount > 0)
            setTokens(tokens.patch(tokenIdx, Seq(value - tokenAmount), 1): _*)
          else
            setTokens(tokens.patch(tokenIdx, Seq(), 1): _*)
        case None =>
          throw new Exception("Tried to subtract non existent token!")
      }
    }else if(tokenAmount == 0){
      this
    }else{
      throw new Exception("Tried to subtract negative token amount!")
    }
  }

  def removeToken(token: Token): UTXO = removeToken(token.id, token.amount)

  def toOutBox(ctx: BlockchainContext): OutBox = {
    var out = ctx.newTxBuilder().outBoxBuilder()
      .value(value)
      .contract(contract.ergoContract(ctx))

    if(tokens.nonEmpty)
      out = out.tokens(tokens.map(_.toErgo): _*)

    if(registers.nonEmpty)
      out = out.registers(registers: _*)

    if(creationHeight.isDefined)
      out = out.creationHeight(creationHeight.get)

    out.build()
  }

  def toInput(ctx: BlockchainContext, txId: ErgoId, outIdx: Short): InputUTXO = {
    InputUTXO(toOutBox(ctx).convertToInputWith(txId.toString, outIdx))
  }

  def toDummyInput(ctx: BlockchainContext): InputUTXO = {
    toInput(ctx, ErgoId.create("a1086e447695dc8dcb79c0bf3b06ed715ccfa2b28ef44889ebfbda16c00ff34b"), 0.toShort)
  }

  def parseReg[T](idx: Int): T = {
    registers(idx).getValue.asInstanceOf[T]
  }

  def parseCollReg[T](idx: Int): Array[T] = {
    registers(idx).getValue.asInstanceOf[Coll[T]].toArray
  }

}

object UTXO {

  final val ONE_ERG = Parameters.OneErg
  final val MIN_FEE = Parameters.MinFee
  final val MIN_CHANGE = Parameters.MinChangeValue

  def apply(contract: Contract, value: Long,
            tokens: Seq[Token] = Seq.empty[Token],
            registers: Seq[ErgoValue[_]] = Seq.empty[ErgoValue[_]],
            creationHeight: Option[Int] = None): UTXO = {
    new UTXO(contract, value, tokens, registers, creationHeight)
  }

  def feeBox(amnt: Long = MIN_FEE) = UTXO(Contract.FEE_720, amnt)



}
