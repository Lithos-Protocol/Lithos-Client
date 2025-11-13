package work.lithos.mutations

/**
 * Mutators are functions which take a transactional context and create a set of output boxes so long as
 * the pre-requisites are satisfied
 */
trait Mutator {
  val preReqs: Seq[TxContext => Boolean]
  protected def mutation(tCtx: TxContext): Seq[UTXO]

  def execute(tCtx: TxContext): Seq[UTXO] = {
    if(satisfiesPreReqs(tCtx)){
      mutation(tCtx)
    }else{
      throw new PreRequisiteFailedException(s"Failed to apply mutation due to failing pre-requisite #${preReqs.indexWhere(p => !p(tCtx))}")
    }
  }

  def satisfiesPreReqs(tCtx: TxContext): Boolean = preReqs.foldLeft(true)((z, x) => x(tCtx) && z)
  //preContext represents context before application of this mutation
  def asPreRequisite(preContext: TxContext): TxContext => Boolean = {
    //Current context is equal to preContext after application of mutation, and preContext and tCtx are not equal
    (tCtx: TxContext) => tCtx.equals(execute(preContext)) && !tCtx.equals(preContext)
  }
}
