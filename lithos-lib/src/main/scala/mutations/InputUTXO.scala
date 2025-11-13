package work.lithos
package mutations

import org.ergoplatform.appkit.{ContextVar, ErgoValue, InputBox}
import org.ergoplatform.sdk.ErgoId

import scala.collection.JavaConverters.collectionAsScalaIterableConverter

case class InputUTXO(input: InputBox,
                     ctxVars: Seq[ContextVar] = Seq.empty[ContextVar],
                     addMutators: Seq[Mutator] = Seq.empty[Mutator]) {

  val contract: Contract = Contract(input.getErgoTree)
  val value: Long = input.getValue
  val tokens: Seq[Token] = input.getTokens.asScala.toSeq.map(Token.ergo)
  val registers: Seq[ErgoValue[_]] = input.getRegisters.asScala.toSeq

  def id: ErgoId = input.getId
  def bytes: Array[Byte] = input.getBytes

  def withMutator(mutator: Mutator): InputUTXO =
    this.copy(addMutators = addMutators ++ Seq(mutator))

  def withMutator(mutatorFunc: TxContext => Seq[UTXO], mutatorPreReqs: Seq[TxContext => Boolean]): InputUTXO = {
    val mutator = new Mutator {

      override val preReqs: Seq[TxContext => Boolean] = mutatorPreReqs
      override def mutation(tCtx: TxContext): Seq[UTXO] = mutatorFunc(tCtx)
    }

    withMutator(mutator)
  }

  def setMutators(mutators: Mutator*): InputUTXO = this.copy(addMutators = mutators)

  def withCtxVar(ctxVar: ContextVar): InputUTXO = {
    this.copy(ctxVars = ctxVars ++ Seq(ctxVar))
  }

  def setCtxVars(ctxVars: ContextVar*): InputUTXO = this.copy(ctxVars = ctxVars)

  def toFullInput: InputBox = {
    if(ctxVars.nonEmpty)
      input.withContextVars(ctxVars:_*)
    else
      input
  }

  def copyWithoutMutators: InputUTXO = {
    this.copy(addMutators = Seq.empty[Mutator])
  }

  def toUTXO: UTXO = {
    UTXO(contract, value, tokens, registers)
  }

}

object InputUTXO {
  def apply(input: InputBox,
            ctxVars: Seq[ContextVar] = Seq.empty[ContextVar],
            addMutators: Seq[Mutator] = Seq.empty[Mutator]): InputUTXO = {
    new InputUTXO(input, ctxVars, addMutators)
  }
}
