package transactions

import lfsm.LFSMHelpers
import mutations.NodeWallet
import nisp.NISP
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Longs
import sigma.Colls
import sigma.data.CBigInt
import transactions.TransactionMessages.LatestRollup
import utils.Helpers.{evalContract, holdingContract, payoutContract}
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

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
    copiedTree.prover.generateProof() // Reset proof for copied tree, proofs will be incorrect if this is not done!

    val insert = copiedTree.insert(
      wallet.contract.hashedPropBytes -> nisp.serialize)

    val inputWithContext = holdingInput.setCtxVars(
      ContextVar.of(0.toByte, ErgoValue.of(wallet.contract.sigmaBoolean.get)),
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
    val output = UTXO(payout, evalInput.value, evalInput.tokens,
      registers = Seq(
        evalInput.registers.head,
        evalInput.registers(1),
        evalInput.registers(2),
        ErgoValue.of(evalInput.value)
      ))
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
    copiedTree.prover.generateProof() // Reset proof for copied tree

    val lookUp = copiedTree.lookUp(wallet.contract.hashedPropBytes)
    val delete = copiedTree.delete(wallet.contract.hashedPropBytes)
    val score = Longs.fromByteArray(lookUp.response.head.ergoValue.getValue.toArray.slice(0, 8))
    val totalScore = payInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val totalReward = payInput.registers(3).getValue.asInstanceOf[Long]
    val totalTokens = payInput.tokens.headOption

    val amountToPay = LFSMHelpers.paymentFromScore(score, totalScore, totalReward)
    val amountTokens = LFSMHelpers.paymentFromScore(score, totalScore, totalTokens.map(_.amount).getOrElse(0L))
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
            Seq.empty[Token]
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
        registers = Seq(
          copiedTree.ergoValue,
          payInput.registers(1),
          payInput.registers(2),
          payInput.registers(3)
        ))
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
      val minerOutput = UTXO(wallet.contract, amountToPay, tokensOutputted)
      val totalOutputs = Seq(minerOutput) ++ feeOutputs
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(totalOutputs:_*)
        .buildTx(0, wallet.p2pk)
      wallet.sign(uTx)
    }
  }
}
