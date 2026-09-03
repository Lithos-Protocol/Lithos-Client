package transactions.rollups

import evaluation.{CommitmentSource, Evaluator}
import lfsm.states.RollupInfoState
import lfsm.{LFSMHelpers, LFSMPhase, RollupProtocol}
import mutations.{BoxLoader, NodeWallet}
import nisp.NISP
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
    val holding = holdingContract(ctx)

    val otherInputs = walletInputs
    val tree = latestState.rollup.dictionary
    val copiedTree = tree.copy()

    val insert = copiedTree.insert(
      wallet.contract.hashedPropBytes -> nisp.serialize)

    val inputWithContext = holdingInput.setCtxVars(
      ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(wallet.contract.valueBytes), scalaByteType)),
      ContextVar.of(
        1.toByte,
        ErgoValue.pairOf(
          ErgoValue.of(Colls.fromArray(wallet.contract.hashedPropBytes), scalaByteType),
          nisp.ergoValue)
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
                          feeOutputs: Seq[UTXO]): SignedTransaction = {
    val eval = evalContract(ctx)

    val otherInputs = walletInputs
    val state = stateOf(holdingInput, LFSMPhase.HOLDING)
    // Only the period start moves. The rollup's own block and the bond ledger carry through, the
    // latter because evaluation is where the bonds are slashed or handed on to payout.
    val nextState = RollupInfoState.evaluation(
      ctx.getHeight.toLong, state.genesisBlockHeight, state.totalBond)

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
    val score = Longs.fromByteArray(lookUp.response.head.ergoValue.getValue.toArray.slice(0, 8))
    val totalScore = payInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val state = stateOf(payInput, LFSMPhase.PAYOUT)
    val nftToken = RollupProtocol.rollupNFT(payInput.tokens)
    val litHeld = RollupProtocol.litToken(payInput.tokens)

    // A miner takes their pro-rata share of the reward and their own bond back. The bond was never
    // part of the reward, so it is added after the division rather than divided.
    val bondRefund = RollupProtocol.bondForScore(score)
    val amountToPay =
      LFSMHelpers.paymentFromScore(score, totalScore, state.totalErgReward) + bondRefund
    val amountTokens = LFSMHelpers.paymentFromScore(score, totalScore, state.totalLitReward)
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

    if (amountToPay < payInput.value && payInput.value - amountToPay > 1000000L) {
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
      // This is the drain path, so what is left over is below the min change value. TxBuilder folds
      // such a remainder into the fee output; with no fee output and no wallet input there is
      // nothing to fold into and the fold throws. Payout.ergo checks the miner output with `>=`
      // rather than `==` for exactly this reason, so the remainder can go to the miner instead.
      val feeless = feeOutputs.isEmpty && otherInputs.isEmpty
      val minerValue = if (feeless && payInput.value > amountToPay) payInput.value else amountToPay
      val minerOutput = UTXO(wallet.contract, minerValue, tokensOutputted)
      val totalOutputs = Seq(minerOutput) ++ feeOutputs
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
                       commitment: Option[CommitmentSource] = None): SignedTransaction = {

    // Compiled once for the JVM's life. This runs inside `attemptTx`, which retries up to five
    // times, so compiling the seven proofs per attempt was 35 compilations for one slash.
    val fpContracts = ProtocolContracts.fraudProofs(ctx)
    val fpControl = LFSMHelpers.getFPControlBox(ctx)
    val evaluator = Evaluator(ctx, wallet, evalInput, latestState.rollup, Seq.empty,
      fpControl, new BoxLoader(ctx, Globals.getNodeConfig.getNodeApi), fpContracts, commitment)
    val concreteEval = evaluator.evaluateFor(miner, fpContractHashHex, walletInputs, feeOutputs)
    concreteEval.get
  }
}
