package work.lithos.mutations

import org.ergoplatform.sdk.{ErgoId, ErgoToken}


case class Token(id: ErgoId, amount: Long){
  require(amount > 0, "Cannot create token with 0 amount")
  def toErgo: ErgoToken = new ErgoToken(id, amount)
  def +(amnt: Long): Token = Token(id, amount + amnt)
  def -(amnt: Long): Token = {
    require(amount - amnt > 0, "Subtraction caused negative or zero token amount")
    Token(id, amount - amnt)
  }
}

object Token{
  def apply(id: ErgoId, amount: Long) = new Token(id, amount)
  def apply(id: String, amount: Long) = new Token(ErgoId.create(id), amount)
  def ergo(token: ErgoToken): Token = Token(token.getId, token.getValue)
}
