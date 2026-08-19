package transactions.rollups

import evaluation.Evaluator
import lfsm.LFSMHelpers
import mutations.{BoxLoader, NodeWallet}
import nisp.NISP
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Longs
import sigma.Colls
import sigma.data.CBigInt
import transactions.ProtocolContracts
import transactions.rollups.TransactionMessages.LatestRollup
import utils.Globals
import utils.Helpers.{evalContract, holdingContract, payoutContract}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

object RollupTransactions {

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
    val tree = latestState.NISPTree.dictionary
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
      ContextVar.of(2.toByte, insert.proof.ergoValue)
    )

    val lastMiners = holdingInput.registers(1).getValue.asInstanceOf[Int]
    val lastScore = holdingInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue

    val output = UTXO(holding, holdingInput.value, holdingInput.tokens,
      registers = Seq(
        copiedTree.ergoValue,
        ErgoValue.of(lastMiners + 1),
        ErgoValue.of((score + BigInt(lastScore)).bigInteger),
        holdingInput.registers(3)
      ))

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
    val output = UTXO(eval, holdingInput.value, holdingInput.tokens,
      registers = Seq(
        holdingInput.registers.head,
        holdingInput.registers(1),
        holdingInput.registers(2),
        ErgoValue.of(ctx.getHeight.toLong),
        holdingInput.registers(3)
      ))

    val totalOutputs = Seq(output) ++ feeOutputs
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(holdingInput) ++ otherInputs): _*)
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
    val regs = {
        val registers = Seq(
          evalInput.registers.head,
          evalInput.registers(1),
          evalInput.registers(2),
          ErgoValue.of(evalInput.value)
        )
      if(evalInput.tokens.nonEmpty)
        registers :+ ErgoValue.of(evalInput.tokens.head.amount)
      else
        registers
    }
    val output = UTXO(payout, evalInput.value, evalInput.tokens,
      registers = regs)
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
    val tree = latestState.NISPTree.dictionary
    val copiedTree = tree.copy()

    val lookUp = copiedTree.lookUp(wallet.contract.hashedPropBytes)
    val delete = copiedTree.delete(wallet.contract.hashedPropBytes)
    val score = Longs.fromByteArray(lookUp.response.head.ergoValue.getValue.toArray.slice(0, 8))
    val totalScore = payInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val totalReward = payInput.registers(3).getValue.asInstanceOf[Long]
    val tokenReward = if(payInput.tokens.nonEmpty) payInput.registers(4).getValue.asInstanceOf[Long] else 0L
    val totalTokens = payInput.tokens.headOption

    val amountToPay = LFSMHelpers.paymentFromScore(score, totalScore, totalReward)
    val amountTokens = LFSMHelpers.paymentFromScore(score, totalScore, tokenReward)
    val inputWithContext = payInput.setCtxVars(
      ContextVar.of(0.toByte,
        ErgoValue.of(Array(Colls.fromArray(wallet.contract.hashedPropBytes)),
          ErgoType.collType(scalaByteType))),
      ContextVar.of(1.toByte, lookUp.proof.ergoValue),
      ContextVar.of(2.toByte, delete.proof.ergoValue)
    )
    if (amountToPay < payInput.value && payInput.value - amountToPay > 1000000L) {
      val nextTokens = {
        if (totalTokens.isDefined) {
          if (totalTokens.get.amount == amountTokens)
            Seq.empty[Token] // If this branch is taken, payout contract would fail
          else
            Seq(totalTokens.get - amountTokens)
        } else {
          Seq.empty[Token]
        }
      }
      val tokensOutputted = {
        if (totalTokens.isDefined) {
          Seq(Token(LFSMHelpers.LIT_ID, amountTokens))
        } else {
          Seq.empty[Token]
        }
      }
      val output = UTXO(payout, payInput.value - amountToPay, nextTokens,
        registers = payInput.registers).withReg(0, copiedTree.ergoValue)
      val minerOutput = UTXO(wallet.contract, amountToPay, tokensOutputted)
      val totalOutputs = Seq(output, minerOutput) ++ feeOutputs
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(totalOutputs:_*)
        .buildTx(0, wallet.p2pk)
      wallet.sign(uTx)
    } else {
      val tokensOutputted = {
        if(totalTokens.isDefined) {
          Seq(Token(LFSMHelpers.LIT_ID, amountTokens))
        }else {
          Seq.empty[Token]
        }
      }
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
        .setOutputs(totalOutputs:_*)
        .buildTx(0, wallet.p2pk)
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
   */
  def genFraudProofTransform(ctx: BlockchainContext,
                       wallet: NodeWallet,
                       evalInput: InputUTXO,
                       walletInputs: Seq[InputUTXO],
                       latestState: LatestRollup,
                       feeOutputs: Seq[UTXO],
                       miner: Array[Byte],
                       fpContractHashHex: String): SignedTransaction = {

    // Compiled once for the JVM's life. This runs inside `attemptTx`, which retries up to five
    // times, so compiling the seven proofs per attempt was 35 compilations for one slash.
    val fpContracts = ProtocolContracts.fraudProofs(ctx)
    val fpControl = LFSMHelpers.getFPControlBox(ctx)
    val evaluator = Evaluator(ctx, wallet, evalInput, latestState.NISPTree, Seq.empty,
      fpControl, new BoxLoader(ctx, Globals.getNodeConfig.getNodeApi), fpContracts)
    val concreteEval = evaluator.evaluateFor(miner, fpContractHashHex, walletInputs, feeOutputs)
    concreteEval.get
  }
}
