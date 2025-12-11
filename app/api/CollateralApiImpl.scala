package api

import configs.NodeConfig
import lfsm.LFSMHelpers
import lfsm.collateral.CollateralContract
import lfsm.rollup.RollupContracts
import model.ApiError
import model.CollateralInfo
import model.CollateralUTXO
import model.SuccessfulTransaction
import mutations.BoxLoader
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoValue, JavaHelpers, Parameters, SecretString}
import play.api.Configuration
import sigma.SigmaProp
import sigma.ast.ErgoTree
import utils.Helpers
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

/**
  * Provides a default implementation for [[CollateralApi]].
  */
class CollateralApiImpl extends CollateralApi {
  /**
    * @inheritdoc
    */
  override def createCollateralUTXO(collateralInfo: CollateralInfo, config: Configuration): SuccessfulTransaction = {
    val nodeConfig = new NodeConfig(config)
    nodeConfig.getClient.execute {
      ctx =>
        if(collateralInfo.fee > LFSMHelpers.COLLAT_MAX_FEE)
          throw new IllegalArgumentException(s"Cannot create collateral UTXO with fee over ${LFSMHelpers.COLLAT_MAX_FEE}")
        val coinsToIssue = CollateralContract.coinsToIssue(ctx.getHeight)
        if(collateralInfo.reward < coinsToIssue)
          throw new IllegalArgumentException(s"Cannot create collateral UTXO with reward less" +
            s" than current block reward ${coinsToIssue}")
        val inputs = new BoxLoader(ctx)
          .loadBoxes
          .getInputs((collateralInfo.reward * collateralInfo.numUtxos) + Parameters.MinFee)

        val prover = nodeConfig.prover
        val collateral = Helpers.collateralContract(ctx)
        val outputs = Array.fill(collateralInfo.numUtxos)(UTXO(collateral, collateralInfo.reward, registers = Seq(
          ErgoValue.of(collateralInfo.fee),
          ErgoValue.of(prover.getAddress.getPublicKey)
        )))
        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        val uTx = TxBuilder(ctx)
          .setInputs(inputs: _*)
          .setOutputs((outputs ++ Seq(feeOutput)): _*)
          .buildTx(0, prover.getAddress)

        val sTx = prover.sign(uTx)
        val txId = ctx.sendTransaction(sTx)

        SuccessfulTransaction(txId.replace("\"", ""), ctx.getHeight)
    }
  }

  /**
    * @inheritdoc
    */
  override def getAllCollateralInfo(limit: Option[Int], offset: Option[Int], config: Configuration): List[CollateralUTXO] = {
    val nodeConfig = new NodeConfig(config)

    nodeConfig.getClient.execute{
      ctx =>

        val page = ApiHelper.handlePagination(offset, limit)
        JavaHelpers.toIndexedSeq(ctx.getUnspentBoxesFor(Helpers.collateralContract(ctx).address(ctx), page._1, page._2))
          .map(i => parseCollateralUTXO(ctx, InputUTXO(i))).toList
    }
  }

  /**
    * @inheritdoc
    */
  override def getLocalCollateralInfo(limit: Option[Int], offset: Option[Int], config: Configuration): List[CollateralUTXO] = {
    // TODO: Implement better logic
    val nodeConfig = new NodeConfig(config)
    getAllCollateralInfo(limit, offset, config).filter(c => c.lender == nodeConfig.prover.getAddress.toString)
  }

  def parseCollateralUTXO(ctx: BlockchainContext, i: InputUTXO) = {
    CollateralUTXO(i.id.toString, i.value,
      Address.fromErgoTree(ErgoTree.fromProposition(i.registers(1).getValue.asInstanceOf[SigmaProp]),
        ctx.getNetworkType).toString,
      i.registers(0).getValue.asInstanceOf[Long],
      i.toFullInput.getCreationHeight
    )
  }
}
