package stratum

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, RollupContracts}
import mutations.{BoxLoader, NodeWallet, NotEnoughInputsException}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoClientException, ErgoId, ErgoProver, ErgoValue, JavaHelpers, Parameters, SecretString}
import org.slf4j.{Logger, LoggerFactory}
import scorex.crypto.hash.Blake2b256
import sigma.SigmaProp
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, CSigmaProp, ProveDHTuple, ProveDlog, SigmaBoolean, SigmaConjecture, SigmaLeaf, TrivialProp}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import scala.util.{Failure, Success, Try}

class CollateralRetriever(client: ErgoClient, prover: NodeWallet) {
  private val logger: Logger = LoggerFactory.getLogger("CollateralRetriever")
  def getCollateral: CollateralData = {
    client.execute{
      ctx =>
        val payout   = RollupContracts.mkPayoutContract(ctx)
        val eval     = RollupContracts.mkEvalContract(ctx,
          LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes,
          LFSMHelpers.getFPToken(ctx))
        val holding  = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
        val collateral = CollateralContract.mkTestnetCollatContract(ctx, holding.hashedPropBytes)
        val utxos = ctx.getUnspentBoxesFor(collateral.address(ctx.getNetworkType), 0, 10)
        //val utxos = JavaHelpers.toJList(ctx.getBoxesById("ce00d6e0f4fd3390c0519bb0d24824e1e6b06978163f4845a7cf2c1c7d8f1b65").toIndexedSeq)

        val currentBlockReward = CollateralContract.coinsToIssue(ctx.getHeight)
        //logger.info(s"Found ${utxos.size()} collateral utxos")
        val utxosWithLender = JavaHelpers.toIndexedSeq(utxos).filter{
          i => Try(InputUTXO(i).registers(1).getValue.asInstanceOf[SigmaProp]).isSuccess &&
            InputUTXO(i).value >= currentBlockReward
        }
        //logger.info(s"Found ${utxosWithLender.size} collateral utxos with value >= ${currentBlockReward}")
        if(utxos.size() == 0 || utxosWithLender.isEmpty) {
          throw new CollateralNotFoundException("Could not find collateral utxos needed to create block candidate")
        }
        val collateralInput = InputUTXO(utxosWithLender.head)

        val emptyTree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        val lenderPK = collateralInput.registers(1).getValue.asInstanceOf[SigmaProp]
        val holdingOutput = UTXO(holding, currentBlockReward, collateralInput.tokens,
          registers = Seq(
            emptyTree.ergoValue,
            ErgoValue.of(0),
            ErgoValue.of(BigInt(0).bigInteger),
            ErgoValue.of(ctx.getHeight.toLong+1L),
            ErgoValue.of(prover.contract.hashedPropBytes)
          ))



        val lenderBoolean = lenderPK match {
          case CSigmaProp(sigmaTree) => sigmaTree
          case _ => throw new InvalidSigmaBooleanException("Found invalid SigmaBoolean on collateral utxo")
        }
        val lenderAddress = Address.fromErgoTree(ErgoTree.fromSigmaBoolean(lenderBoolean), ctx.getNetworkType)

        // TODO: Filter utxos by valid SigmaBoolean existence, we only check for SigmaProp
        val pkString = lenderBoolean match {
          case leaf: SigmaLeaf =>
            leaf match {
              case ProveDlog(value) =>
                Hex.toHexString(GroupElementSerializer.toBytes(value))
              case _ => throw new InvalidSigmaBooleanException("Found invalid SigmaBoolean on collateral utxo")
            }
          case _ => throw new InvalidSigmaBooleanException("Found invalid SigmaBoolean on collateral utxo")
        }

        val txB = TxBuilder(ctx)
        val uTx = txB
          .setInputs(collateralInput)
          .setOutputs(holdingOutput)
          .setPreHeader(ctx.createPreHeader().height(ctx.getHeight+1).minerPk(lenderAddress.getPublicKeyGE).build())
          .buildTx(0, lenderAddress)
        val sTx = prover.sign(uTx)
        val utxBytes = uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign
        val collData = CollateralData(sTx.getId.replace("\"", ""), sTx.toJson(false, false), pkString, utxBytes, collateralInput.bytes,
          collateralInput.id.toString, lenderAddress.toString)
       // logger.info(s"Generated $collData with tx size: ${utxBytes.length} and input size: ${collateralInput.bytes.length}")
        collData
    }
  }
  def checkCollateral(inputRetriever: (Long) => Seq[InputUTXO], numBoxes: Int): Unit = {
    client.execute{
      ctx =>
        val payout   = RollupContracts.mkPayoutContract(ctx)
        val eval     = RollupContracts.mkEvalContract(ctx,
          LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes,
          LFSMHelpers.getFPToken(ctx))
        val holding  = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
        val collateral = CollateralContract.mkTestnetCollatContract(ctx, holding.hashedPropBytes)
        val utxos = ctx.getUnspentBoxesFor(collateral.address(ctx.getNetworkType), 0, 10)

        val currentBlockReward = CollateralContract.coinsToIssue(ctx.getHeight)

        val utxosWithLender = JavaHelpers.toIndexedSeq(utxos).filter{
          i => Try(InputUTXO(i).registers(1).getValue.asInstanceOf[SigmaProp]).isSuccess &&
            InputUTXO(i).value >= currentBlockReward
        }
        logger.info(s"Found ${utxosWithLender.size} collateral utxos with value >= ${currentBlockReward}")
        if(utxos.size() < 8 || utxosWithLender.size < 8) {
          logger.info(s"Attempting $numBoxes self-collateralization(s) to create more collateral UTXOs")
          val trySelfCollat = mkCollateral(ctx, prover, inputRetriever, numBoxes)
          trySelfCollat match {
            case Failure(e) =>
              e match {
                case _: NotEnoughInputsException =>
                  logger.warn("Could not self-collateralize due to not having enough inputs")
                case ds: ErgoClientException if ds.getMessage.contains("Double spending attempt") =>
                  logger.warn("Could not self-collateralize due to double spending attempt")
                case _ =>
                  logger.error(s"Could not self-collateralize due to: ${e.getMessage}")
              }
            case Success(selfCollats) =>
              logger.info(s"Successfully sent ${selfCollats.size} txs to self-collateralize")
          }

        }else{
          logger.info("Avoiding self-collateralization due to at least 5 collateral UTXOs existing")
        }
    }

  }
  def mkCollateral(ctx: BlockchainContext, wallet: NodeWallet, inputRetriever: (Long) => Seq[InputUTXO], numBoxes: Int): Try[Seq[String]] = {
    Try {

      val payout = RollupContracts.mkPayoutContract(ctx)
      val eval = RollupContracts.mkEvalContract(ctx,
        LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes,
        LFSMHelpers.getFPToken(ctx))
      val holding = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
      val collateral: Contract = CollateralContract.mkTestnetCollatContract(ctx, holding.hashedPropBytes)

      var emissions = InputUTXO(ctx.getCoveringBoxesFor(CollateralContract.mkEmissionsContract(ctx, collateral.hashedPropBytes).address(ctx),
        Parameters.MinFee, JavaHelpers.toJList(IndexedSeq(Token(LFSMHelpers.EMISSION_NFT, 1L).toErgo))).getBoxes.get(0))
      val blockReward = CollateralContract.coinsToIssue(ctx.getHeight)
      var lastBox: Option[InputUTXO] = None
      val txs = for(i <- 0 until numBoxes) yield {
        val inputs = {
          if(lastBox.isDefined)
            Seq(lastBox.get)
          else
            inputRetriever(blockReward + Parameters.MinFee * 2)
        }
        val totalInputs = Seq(emissions.withMutator(CollateralContract.emissionMutator(collateral, wallet.contract, 0L))) ++ inputs

        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee * 2)
        val uTx = TxBuilder(ctx)
          .setInputs(totalInputs: _*)
          .mutateOutputs
          .addOutputs(Seq(feeOutput))
          .buildTx(0, wallet.p2pk)

        val sTx = prover.sign(uTx)
        val txId = ctx.sendTransaction(sTx)
        emissions = InputUTXO(sTx.getOutputsToSpend.get(0))
        if(sTx.getOutputs.size() > 3){
          val changeBox = InputUTXO(sTx.getOutputsToSpend.get(3))
          if(changeBox.value > blockReward + Parameters.MinFee * 2){
            lastBox = Some(changeBox)
          }
        }
        logger.info(s"Sent tx ${txId} to self-collateralize")
        txId
      }
      txs
    }
  }
}
