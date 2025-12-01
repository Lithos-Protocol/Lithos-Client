package evaluation

import lfsm.LFSMHelpers
import lfsm.fraudproofs.FraudProofContracts
import work.lithos.mutations.{Contract, Mutator, TxContext, UTXO}
// Setup Mutator to create initial output for fraud proof evaluation
class EvaluationMutator(evalContract: Contract) extends Mutator{

  override val preReqs: Seq[TxContext => Boolean] = Seq(
    {(t: TxContext) => t.inputs.head.contract.hashedPropBytes sameElements evalContract.hashedPropBytes },
   // {(t: TxContext) => FraudProofContracts.isFraudProof(t.inputs(1).contract) },
    {(t: TxContext) => t.dataInputs.head.tokens.head.id == LFSMHelpers.getFPToken(t.ctx) },
  )

  override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
    Seq(tCtx.inputs.head.toUTXO)
  }
}
