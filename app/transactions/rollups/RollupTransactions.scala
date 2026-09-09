package transactions.rollups

import evaluation.{CommitmentSource, Evaluator}
import lfsm.states.RollupInfoState
import lfsm.{LFSMHelpers, LFSMPhase, RollupProtocol}
import mutations.{BoxLoader, NodeWallet}
import nisp.{NISP, NispCommitment, ResolvedNisps}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi.scalaByteType
import scorex.utils.Longs
import sigma.Colls
import sigma.data.CBigInt
import transactions.ProtocolContracts
import transactions.dex.DexContracts
import transactions.rollups.TransactionMessages.LatestRollup
import utils.Globals
import utils.Helpers.{evalContract, holdingContract, holdingLogicContract, payoutContract}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

object RollupTransactions {

  /** Holding op 0: submit a NISP. Op 1 is both the transform and the in-block top-up. */
  private val SubmitOp: Byte = 0
  private val TransformOp: Byte = 1

  /** The guard runs the rules out of variable 64 after checking their hash, so every spend sends them. */
  private def logicVar(ctx: BlockchainContext): ContextVar =
    ContextVar.of(64.toByte, ErgoValue.of(Colls.fromArray(holdingLogicContract(ctx).valueBytes), scalaByteType))

  /** R7 of a rollup box, read under the phase its script puts it in. */
  private def stateOf(box: InputUTXO, phase: LFSMPhase): RollupInfoState =
    RollupInfoState.fromErgoValue(box.registers(3), phase)

  /** The height a rollup's NISPs must prove work against, which is the block it was created in. */
  def genesisBlockHeight(holdingInput: InputUTXO): Int =
    stateOf(holdingInput, LFSMPhase.HOLDING).genesisBlockHeight.toInt

  /** The refundable ERG a submission of this score has to add to the holding box. */
  def submissionBond(score: Long): Long = RollupProtocol.bondForScore(score)

  def genNISPSubmission(ctx: BlockchainContext,
                        wallet: NodeWallet,
                        holdingInput: InputUTXO,
                        walletInputs: Seq[InputUTXO],
                        latestState: LatestRollup,
                        feeOutputs: Seq[UTXO],
                        nisp: NISP,
                        score: Long
                       ): SignedTransaction = {
    // A submission recreates the box under its own script rather than a freshly compiled one, which
    // is what `validUTXO` compares. Only the two phase transitions below compile a target contract,
    // because only they move the box to a different one.
    val holding = holdingInput.contract

    val otherInputs = walletInputs
    val tree = latestState.rollup.dictionary
    val copiedTree = tree.copy()

    val payload = nisp.serialize
    val entry = NispCommitment.fromPayload(payload)
    require(entry.score == score, "Submission score does not match its serialized payload")
    val insert = copiedTree.insert(wallet.contract.hashedPropBytes -> entry.bytes)

    val inputWithContext = holdingInput.setCtxVars(
      ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(wallet.contract.valueBytes), scalaByteType)),
      ContextVar.of(
        1.toByte,
        ErgoValue.pairOf(
          ErgoValue.of(Colls.fromArray(wallet.contract.hashedPropBytes), scalaByteType),
          ErgoValue.of(payload))
      ),
      ContextVar.of(2.toByte, insert.proof.ergoValue),
      ContextVar.of(3.toByte, ErgoValue.of(SubmitOp)),
      logicVar(ctx)
    )

    val lastMiners = holdingInput.registers(1).getValue.asInstanceOf[Int]
    val lastScore = holdingInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val state = stateOf(holdingInput, LFSMPhase.HOLDING)

    // The bond is funded out of the wallet inputs and enters rollup custody, so the box value and
    // the ledger both rise by it. It comes back at payout, or goes to a prover who disproves this
    // entry, and the contract refuses any other figure.
    val bond = RollupProtocol.bondForScore(score)

    val output = UTXO(holding, holdingInput.value + bond, holdingInput.tokens,
      registers = holdingInput.registers
        .updated(0, copiedTree.ergoValue)
        .updated(1, ErgoValue.of(lastMiners + 1))
        .updated(2, ErgoValue.of((score + BigInt(lastScore)).bigInteger))
        .updated(3, state.withBond(RollupProtocol.collectBond(state.totalBond, bond)).ergoValue))

    val totalOutputs = Seq(output) ++ feeOutputs
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
      .setOutputs(totalOutputs: _*)
      .buildTx(0, wallet.p2pk)
    wallet.sign(uTx)
  }

  def genHoldingTransform(ctx: BlockchainContext,
                          wallet: NodeWallet,
                          holdingInput: InputUTXO,
                          walletInputs: Seq[InputUTXO],
                          feeOutputs: Seq[UTXO],
                          blockHeight: Int): SignedTransaction = {
    val eval = evalContract(ctx)

    val otherInputs = walletInputs
    val state = stateOf(holdingInput, LFSMPhase.HOLDING)
    // Only the period start moves. The rollup's own block and the bond ledger carry through, the
    // latter because evaluation is where the bonds are slashed or handed on to payout.
    //
    // The period is the block this lands in, not the tip it was built on. Evaluation demands it a
    // full period past the last one, so a transform offered at the first height that clears the
    // bound would otherwise write a period one block short and be refused.
    val nextState = RollupInfoState.evaluation(
      blockHeight.toLong, state.genesisBlockHeight, state.totalBond)

    val output = UTXO(eval, holdingInput.value, holdingInput.tokens,
      registers = holdingInput.registers.updated(3, nextState.ergoValue))
    // Attach context vars like this until SigmaMap impl in sigma state
    val holdingLogic = logicVar(ctx)
    val inputWithContext = DexContracts.attachCtxVars (holdingInput,
      Seq((3.toByte, ErgoValue.of(TransformOp)),
        (holdingLogic.getId, holdingLogic.getValue)))

    val totalOutputs = Seq(output) ++ feeOutputs
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
      .setOutputs(totalOutputs: _*)
      .setPreHeader(ctx.createPreHeader().height(blockHeight).build())
      .buildTx(0, wallet.p2pk)
    wallet.sign(uTx)
  }

  /**
   * What a top-up can add to a holding box, and the tokens it has to hand back.
   *
   * `added` is what is left of the revenue inputs once the fee and the residue output are paid for.
   * The contract needs it strictly positive, so a plan holding zero or less is not buildable.
   */
  final case class TopUpPlan(added: Long, residue: Seq[Token]) {
    def isViable: Boolean = added > 0
  }

  /** Tokens summed per id, since one id may arrive on several boxes. */
  private[transactions] def mergeTokens(tokens: Seq[Token]): Seq[Token] =
    tokens.foldLeft(Vector.empty[Token]) { (merged, token) =>
      val idx = merged.indexWhere(_.id.toString == token.id.toString)
      if (idx < 0) merged :+ token else merged.updated(idx, merged(idx) + token.amount)
    }

  def planTopUp(revenueInputs: Seq[InputUTXO], feeOutputs: Seq[UTXO]): TopUpPlan = {
    val residue = mergeTokens(revenueInputs.flatMap(_.tokens))
    // Holding conserves its own token vector exactly, so revenue tokens need an output of their own
    // and that output needs the minimum ERG a box can hold.
    val residueCost = if (residue.isEmpty) 0L else UTXO.MIN_CHANGE
    TopUpPlan(revenueInputs.map(_.value).sum - feeOutputs.map(_.value).sum - residueCost, residue)
  }

  /**
   * Holding op 1 inside the rollup's own block: raise the box's ERG and change nothing else.
   *
   * Tokens, all four registers and the script carry through untouched, so the box the block's
   * genesis created is the box that ends the block, only larger. The contract allows this only while
   * HEIGHT equals the rollup's own block height, which is what makes it a candidate-only spend.
   *
   * Holding is input 0 and output 0 because the contract identifies it by position. Any tokens the
   * revenue inputs carried leave to the miner's own wallet.
   *
   * @param blockHeight the block being mined, which is one past the context's tip. It is what the
   *                    contract compares against, so it is pinned into a pre-header rather than
   *                    left to the height signing would otherwise assume.
   */
  def genHoldingTopUp(ctx: BlockchainContext,
                      wallet: NodeWallet,
                      holdingInput: InputUTXO,
                      revenueInputs: Seq[InputUTXO],
                      feeOutputs: Seq[UTXO],
                      blockHeight: Int): SignedTransaction = {
    val state = stateOf(holdingInput, LFSMPhase.HOLDING)
    require(blockHeight.toLong == state.genesisBlockHeight,
      s"a holding top-up is only valid in the rollup's own block (${state.genesisBlockHeight}), " +
        s"not $blockHeight")

    val plan = planTopUp(revenueInputs, feeOutputs)
    require(plan.isViable,
      s"holding top-up adds ${plan.added}, and the contract requires the value to increase")

    // The box's own script, not a freshly compiled one. The contract compares its proposition bytes
    // against the successor's, so a build whose constants have drifted from the deployed guard would
    // put the successor under a different script and reduce the spend to false.
    //
    // Every output is stamped with the block being mined rather than left to the context's tip.
    // Consensus refuses an output created below the newest input, and revenue earned in this same
    // block already carries the block's own height.
    val output = UTXO(holdingInput.contract, holdingInput.value + plan.added, holdingInput.tokens,
      registers = holdingInput.registers).setCreationHeight(blockHeight)
    val residueOutput = if (plan.residue.isEmpty) Seq.empty[UTXO]
      else Seq(UTXO(wallet.contract, UTXO.MIN_CHANGE, plan.residue).setCreationHeight(blockHeight))

    val holdingLogic = logicVar(ctx)
    val inputWithContext = DexContracts.attachCtxVars(holdingInput,
      Seq((3.toByte, ErgoValue.of(TransformOp)),
        (holdingLogic.getId, holdingLogic.getValue)))

    val uTx = TxBuilder(ctx)
      .setInputs((Seq(inputWithContext) ++ revenueInputs): _*)
      .setOutputs((Seq(output) ++ residueOutput ++ feeOutputs): _*)
      .setPreHeader(ctx.createPreHeader().height(blockHeight).build())
      .buildTx(0, wallet.p2pk)
    wallet.sign(uTx)
  }

  def genEvalTransform(ctx: BlockchainContext,
                       wallet: NodeWallet,
                       evalInput: InputUTXO,
                       walletInputs: Seq[InputUTXO],
                       feeOutputs: Seq[UTXO]): SignedTransaction = {
    val payout = payoutContract(ctx)

    val otherInputs = walletInputs
    val state = stateOf(evalInput, LFSMPhase.EVAL)
    // R7 stops holding heights here. Slot 0 becomes the distributable ERG, which is the box less the
    // bonds it owes back, and slot 1 becomes the LIT held rather than the rollup's block.
    val litHeld = RollupProtocol.litToken(evalInput.tokens).map(_.amount).getOrElse(0L)
    val nextState = RollupInfoState.payout(
      RollupProtocol.distributableReward(evalInput.value, state.totalBond), litHeld, state.totalBond)

    val output = UTXO(payout, evalInput.value, evalInput.tokens,
      registers = evalInput.registers.updated(3, nextState.ergoValue))
    val totalOutputs = Seq(output) ++ feeOutputs
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(evalInput) ++ otherInputs): _*)
      .setOutputs(totalOutputs: _*)
      .buildTx(0, wallet.p2pk)
    wallet.sign(uTx)
  }

  /**
   * What paying one miner out of this rollup will need, decided before anything is built.
   *
   * `isFinal` is the spend that empties the box, and the one carrying the LIT residue left by
   * flooring every earlier share against the original R7 snapshot.
   */
  final case class PayoutPlan(isFinal: Boolean, amountToPay: Long, amountTokens: Long,
                              litResidue: Long) {
    def needsChangeOutput: Boolean = isFinal && litResidue > 0
  }

  /**
   * The remainder below which a payout cannot recreate its box, so it spends it instead. A successor
   * holding less than this is not a legal output.
   */
  private final val MinSuccessorValue: Long = UTXO.MIN_CHANGE

  def planPayout(wallet: NodeWallet,
                 payInput: InputUTXO,
                 latestState: LatestRollup): PayoutPlan = {
    val lookUp = latestState.rollup.dictionary.copy().lookUp(wallet.contract.hashedPropBytes)
    val score = NispCommitment.decode(lookUp.response.head.get).score
    val totalScore = payInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val state = stateOf(payInput, LFSMPhase.PAYOUT)

    val bondRefund = RollupProtocol.bondForScore(score)
    val amountToPay =
      LFSMHelpers.paymentFromScore(score, totalScore, state.totalErgReward) + bondRefund
    val amountTokens = LFSMHelpers.paymentFromScore(score, totalScore, state.totalLitReward)
    val isFinal =
      !(amountToPay < payInput.value && payInput.value - amountToPay > MinSuccessorValue)
    val residue = RollupProtocol.litToken(payInput.tokens)
      .map(_.amount - amountTokens).getOrElse(0L)

    PayoutPlan(isFinal, amountToPay, amountTokens, math.max(residue, 0L))
  }

  def genPayout(ctx: BlockchainContext,
                wallet: NodeWallet,
                payInput: InputUTXO,
                walletInputs: Seq[InputUTXO],
                latestState: LatestRollup,
                feeOutputs: Seq[UTXO]
               ): SignedTransaction = {
    val payout = payoutContract(ctx)

    val otherInputs = walletInputs
    val tree = latestState.rollup.dictionary
    val copiedTree = tree.copy()

    val lookUp = copiedTree.lookUp(wallet.contract.hashedPropBytes)
    val delete = copiedTree.delete(wallet.contract.hashedPropBytes)
    val score = NispCommitment.decode(lookUp.response.head.get).score
    val state = stateOf(payInput, LFSMPhase.PAYOUT)
    val nftToken = RollupProtocol.rollupNFT(payInput.tokens)
    val litHeld = RollupProtocol.litToken(payInput.tokens)

    // Every decision this build turns on. Taken from the plan rather than repeated here, so a caller
    // that asked what the payout needs and the builder that makes it cannot answer differently.
    val plan = planPayout(wallet, payInput, latestState)
    val bondRefund = RollupProtocol.bondForScore(score)
    val amountToPay = plan.amountToPay
    val amountTokens = plan.amountTokens
    val inputWithContext = payInput.setCtxVars(
      ContextVar.of(0.toByte,
        ErgoValue.of(Array(Colls.fromArray(wallet.contract.hashedPropBytes)),
          ErgoType.collType(scalaByteType))),
      ContextVar.of(1.toByte, lookUp.proof.ergoValue),
      ContextVar.of(2.toByte, delete.proof.ergoValue)
    )
    val tokensOutputted =
      if (litHeld.isDefined && amountTokens > 0) Seq(Token(litHeld.get.id, amountTokens))
      else Seq.empty[Token]

    if (!plan.isFinal) {
      // The NFT stays with the successor; only the LIT paid out leaves it.
      val nextTokens = Seq(nftToken) ++
        litHeld.filter(_.amount > amountTokens).map(_ - amountTokens).toSeq
      val nextState = state.withBond(RollupProtocol.releaseBond(state.totalBond, bondRefund))
      val output = UTXO(payout, payInput.value - amountToPay, nextTokens,
        registers = payInput.registers)
        .withReg(0, copiedTree.ergoValue)
        .withReg(3, nextState.ergoValue)
      val minerOutput = UTXO(wallet.contract, amountToPay, tokensOutputted)
      val totalOutputs = Seq(output, minerOutput) ++ feeOutputs
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(totalOutputs: _*)
        .buildTx(0, wallet.p2pk)
      wallet.sign(uTx)
    } else {
      // The final spend. It carries the LIT the floored shares left behind, which has to leave in an
      // output of its own: the box is being consumed and only the NFT is burned.
      val residue = litHeld.filter(_ => plan.litResidue > 0)
        .map(t => Token(t.id, plan.litResidue))
      if (residue.isDefined && feeOutputs.isEmpty)
        throw new IllegalArgumentException(
          s"final payout leaves ${residue.get.amount} LIT, which needs an ERG-bearing output this " +
            "transaction cannot fund without a fee box")
      val residueOutput = residue.map(t => UTXO(wallet.contract, UTXO.MIN_CHANGE, Seq(t))).toSeq

      // TxBuilder folds a below-minimum remainder into the fee output; with no fee output and no
      // wallet input there is nothing to fold into and the fold throws. Payout.ergo checks the miner
      // output with `>=` rather than `==` for exactly this reason, so the remainder goes there.
      val feeless = feeOutputs.isEmpty && otherInputs.isEmpty
      val minerValue = if (feeless && payInput.value > amountToPay) payInput.value else amountToPay
      val minerOutput = UTXO(wallet.contract, minerValue, tokensOutputted)
      val totalOutputs = Seq(minerOutput) ++ residueOutput ++ feeOutputs
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(totalOutputs: _*)
        // The box is consumed here, so its NFT has nowhere left to go and the contract requires
        // that no output carries it.
        .buildTx(0, wallet.p2pk, Seq(nftToken))
      wallet.sign(uTx)
    }
  }

  /**
   * Slash one miner out of a rollup's evaluation box, using the fraud proof `RollupEvaluator`
   * already found for them.
   *
   * The proof is re-run rather than cached, because it is only valid against the exact tree state
   * it was built on. That re-run is also the check that the fraud is still there: `evaluateFor`
   * returns None when the proof no longer reduces to true, and `.get` turns that into a failure.
   *
   * @param miner             hashed prop bytes of the miner to slash
   * @param fpContractHashHex which fraud proof contract to use, as found during evaluation
   * @param commitment        Miner Dictionary state, needed only when the chosen proof is
   *                          `FP_NonMatchingCommitment`. Absent makes that rebuild fail rather than
   *                          emit a transaction whose data inputs the contract would index past
   */
  def genFraudProofTransform(ctx: BlockchainContext,
                       wallet: NodeWallet,
                       evalInput: InputUTXO,
                       walletInputs: Seq[InputUTXO],
                       latestState: LatestRollup,
                       feeOutputs: Seq[UTXO],
                       miner: Array[Byte],
                       fpContractHashHex: String,
                       commitment: Option[CommitmentSource] = None,
                       resolved: Option[ResolvedNisps] = None): SignedTransaction = {

    // Compiled once for the JVM's life. This runs inside `attemptTx`, which retries up to five
    // times, so compiling the nine proofs per attempt was 45 compilations for one slash.
    val fpSet = ProtocolContracts(ctx).fraudProofs
    val fpControl = LFSMHelpers.getFPControlBox(ctx)
    val evaluator = Evaluator(ctx, wallet, evalInput, latestState.rollup, Seq.empty,
      fpControl, new BoxLoader(ctx, Globals.getNodeConfig.getNodeApi), fpSet,
      commitment = commitment, resolved = resolved)
    val concreteEval = evaluator.evaluateFor(miner, fpContractHashHex, walletInputs, feeOutputs)
    concreteEval.get
  }
}
