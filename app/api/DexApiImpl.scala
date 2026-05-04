package api

import cache.PDCache
import model._
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{ErgoId, JavaHelpers, Parameters}
import plasmadex.{DepositMutator, LiquidityPool, PDHelpers, RedemptionMutator, SwapMutator, WithdrawMutator}
import plasmadex.states.LiquidityState
import utils.Globals
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

/**
  * Provides a default implementation for [[DexApi]].
  */
class DexApiImpl extends DexApi {
  private final val EMPTY_VALUE = "NONE"
  /** @inheritdoc */
  override def syncFee(feeNFTId: String, PDCache: PDCache): FeeSyncResult = ???

  /** @inheritdoc */
  override def claimFee(feeNFTId: String, PDCache: PDCache): FeeClaimResult = {
  ???
  }

  /** @inheritdoc */
  override def checkWithdrawDex(lpId: String, PDCache: PDCache): WithdrawResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)

        WithdrawResult(lp.lsFeesX, lp.lsFeesY, lp.lpBox.id.toString, EMPTY_VALUE, EMPTY_VALUE)
    }
  }

  /** @inheritdoc */
  override def withdrawDex(lpId: String, PDCache: PDCache): WithdrawResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient
    val wallet = Globals.getNodeConfig.getNodeWallet

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val boxLoader = new BoxLoader(ctx)
          .loadBoxes

        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val withdrawMutator = new WithdrawMutator(lp)
        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        if(lp.lsFeesX > Parameters.MinFee){
          val initInput = boxLoader.getInputs(Parameters.MinFee).head
          val uTx = TxBuilder(ctx)
            .setInputs(lpBox.withMutator(withdrawMutator), initInput)
            .mutateOutputs
            .setInputs(withdrawMutator.getCtxLP, initInput)
            .addOutputs(Seq(feeOutput))
            .buildTx(0, wallet.p2pk)
          val signedTx = wallet.prover.sign(uTx)
          val txId = ctx.sendTransaction(signedTx).replace("\"", "")
          WithdrawResult(lp.lsFeesX, lp.lsFeesY, lp.lpBox.id.toString, signedTx.getOutputsToSpend.get(1).getId.toString, txId)
        }else{
          val initInputs = Seq(lpBox.withMutator(withdrawMutator)) ++ boxLoader.getInputs(Parameters.MinFee*2)
          val realInputs = Seq(withdrawMutator.getCtxLP) ++ boxLoader.getInputs(Parameters.MinFee*2)
          val uTx = TxBuilder(ctx)
            .setInputs(initInputs:_*)
            .mutateOutputs
            .setInputs(realInputs:_*)
            .addOutputs(Seq(feeOutput))
            .buildTx(0, wallet.p2pk)
          val signedTx = wallet.prover.sign(uTx)
          val txId = ctx.sendTransaction(signedTx).replace("\"", "")
          WithdrawResult(lp.lsFeesX, lp.lsFeesY, lp.lpBox.id.toString, signedTx.getOutputsToSpend.get(1).getId.toString, txId)
        }
    }
  }

  /** @inheritdoc */
  override def getDexInfo(PDCache: PDCache): DexInfo = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient
    DexInfo(liqState.utxoId, PDHelpers.getLPToken(client).toString, liqState.liquidity)
  }

  /** @inheritdoc */
  override def getDexSyncStatus(lpId: String, PDCache: PDCache): LPSyncStatus = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient
    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    LPSyncStatus(liqState.syncHeight, Globals.getPDSyncState, liqState.utxoId, liqState.dictionary.toString())
  }

  /** @inheritdoc */
  override def swapDex(lpId: String, swapInfo: SwapInfo, PDCache: PDCache): SwapResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient
    val wallet = Globals.getNodeConfig.getNodeWallet

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val boxLoader = new BoxLoader(ctx)
          .loadBoxes
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val swapMutator = new SwapMutator(lp, swapInfo.amount, swapInfo.isERG)
        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        if(swapInfo.isERG){
          val initInputs = boxLoader.getInputs(Parameters.MinFee + swapInfo.amount)
          val uTx = TxBuilder(ctx)
            .setInputs((Seq(lpBox.withMutator(swapMutator)) ++ initInputs):_*)
            .mutateOutputs
            .setInputs((Seq(swapMutator.getCtxLP) ++ initInputs):_*)
            .addOutputs(Seq(feeOutput))
            .buildTx(0, wallet.p2pk)
          val signedTx = wallet.prover.sign(uTx)
          val txId = ctx.sendTransaction(signedTx).replace("\"", "")
          SwapResult(swapMutator.getSwapOutput, txId)
        }else{
          val initInputs = boxLoader.getInputs(Parameters.MinFee, lp.tokenY.id, swapInfo.amount)
          val uTx = TxBuilder(ctx)
            .setInputs((Seq(lpBox.withMutator(swapMutator)) ++ initInputs):_*)
            .mutateOutputs
            .setInputs((Seq(swapMutator.getCtxLP) ++ initInputs):_*)
            .addOutputs(Seq(feeOutput))
            .buildTx(0, wallet.p2pk)
          val signedTx = wallet.prover.sign(uTx)
          val txId = ctx.sendTransaction(signedTx).replace("\"", "")
          SwapResult(swapMutator.getSwapOutput, txId)
        }
    }
  }

  /** @inheritdoc */
  override def checkSwapDex(lpId: String, swapInfo: SwapInfo, PDCache: PDCache): SwapResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val swapMutator = new SwapMutator(lp, swapInfo.amount, swapInfo.isERG)

        SwapResult(swapMutator.getSwapOutput, EMPTY_VALUE)
    }
  }

  /** @inheritdoc */
  override def depositDex(lpId: String, depositInfo: DepositInfo, PDCache: PDCache): DepositResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient
    val wallet = Globals.getNodeConfig.getNodeWallet

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute{
      ctx =>
        val boxLoader = new BoxLoader(ctx)
          .loadBoxes
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val depositMutator = new DepositMutator(lp, liqState, depositInfo.liquidity)
        val initInputs = boxLoader.getInputs(Parameters.MinFee*3 + depositMutator.getAmountX, lp.tokenY.id, depositMutator.getAmountY)
        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        val initOutput = UTXO(wallet.contract, Parameters.MinFee*2 + depositMutator.getAmountX, Seq(Token(lp.tokenY.id, depositMutator.getAmountY)))
        val initUTx = TxBuilder(ctx)
          .setInputs(initInputs:_*)
          .addOutputs(Seq(initOutput, feeOutput))
          .buildTx(0, wallet.p2pk)
        val initTx = wallet.prover.sign(initUTx)

        val uTx = TxBuilder(ctx)
          .setInputs(lpBox.withMutator(depositMutator), InputUTXO(initTx.getOutputsToSpend.get(0)))
          .mutateOutputs
          .setInputs(depositMutator.getCtxLP.get, InputUTXO(initTx.getOutputsToSpend.get(0)))
          .addOutputs(Seq(feeOutput))
          .buildTx(0, wallet.p2pk)
        val signedTx = wallet.prover.sign(uTx)
        ctx.sendTransaction(initTx)
        val txId = ctx.sendTransaction(signedTx).replace("\"", "")
        DepositResult(depositMutator.getAmountX, depositMutator.getAmountY, txId, lpBox.id.toString)
    }
  }

  /** @inheritdoc */
  override def checkDepositDex(lpId: String, depositInfo: DepositInfo, PDCache: PDCache): DepositResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val depositMutator = new DepositMutator(lp, liqState, depositInfo.liquidity)

        DepositResult(depositMutator.getAmountX, depositMutator.getAmountY, EMPTY_VALUE, lpBox.id.toString)
    }
  }

  /** @inheritdoc */
  override def redemptionDex(lpId: String, redemptionInfo: RedemptionInfo, PDCache: PDCache): RedemptionResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient
    val wallet = Globals.getNodeConfig.getNodeWallet
    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val boxLoader = new BoxLoader(ctx)
          .loadBoxes
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val redemptionNFT = Token(redemptionInfo.provisionNFT, 1)
        val redemptionMutator = new RedemptionMutator(lp, liqState, redemptionNFT.id.getBytes)
        val initInput = boxLoader.getInputs(Parameters.MinFee, redemptionNFT.id, 1).find(_.tokens.exists(_.id == redemptionNFT.id))
        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        val uTx = TxBuilder(ctx)
          .setInputs(lpBox.withMutator(redemptionMutator), initInput.get)
          .mutateOutputs
          .setInputs(redemptionMutator.getCtxLP.get, initInput.get)
          .addOutputs(Seq(feeOutput))
          .buildTx(0, wallet.p2pk, Seq(redemptionNFT))
        val signedTx = wallet.prover.sign(uTx, JavaHelpers.toJList(IndexedSeq(redemptionNFT.toErgo)))

        val txId = ctx.sendTransaction(signedTx).replace("\"", "")

        RedemptionResult(-redemptionMutator.getAmountX, -redemptionMutator.getAmountY, redemptionMutator.getLiqRemoved, txId)
    }
  }

  /** @inheritdoc */
  override def checkRedemptionDex(lpId: String, redemptionInfo: RedemptionInfo, PDCache: PDCache): RedemptionResult = {
    val liqState = currentLSCopy(PDCache)
    val client = Globals.getNodeConfig.getClient

    require(lpId == PDHelpers.getLPToken(client).toString, "Invalid LP Id given")
    client.execute {
      ctx =>
        val lpBox = InputUTXO(ctx.getBoxesById(liqState.utxoId).head)
        val lp = LiquidityPool(lpBox)
        val redemptionMutator = new RedemptionMutator(lp, liqState, Hex.decode(redemptionInfo.provisionNFT))

        RedemptionResult(-redemptionMutator.getAmountX, -redemptionMutator.getAmountY, redemptionMutator.getLiqRemoved, EMPTY_VALUE)
    }
  }

  private def currentLSCopy(PDCache: PDCache): LiquidityState = {
    val liqState = PDCache.getPD.get
    val dictCopy = liqState.dictionary.copy()
    dictCopy.prover.generateProof()
    liqState.copy(dictionary = dictCopy)
  }
}
