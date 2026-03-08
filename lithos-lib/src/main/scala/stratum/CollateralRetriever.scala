package stratum

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, RollupContracts}
import mutations.{BoxLoader, NodeWallet, NotEnoughInputsException}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoId, ErgoProver, ErgoValue, JavaHelpers, Parameters, SecretString}
import org.slf4j.{Logger, LoggerFactory}
import sigma.SigmaProp
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, CSigmaProp, ProveDHTuple, ProveDlog, SigmaBoolean, SigmaConjecture, SigmaLeaf, TrivialProp}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}
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
        logger.info(s"Found ${utxos.size()} collateral utxos")
        val utxosWithLender = JavaHelpers.toIndexedSeq(utxos).filter{
          i => Try(InputUTXO(i).registers(1).getValue.asInstanceOf[SigmaProp]).isSuccess &&
            InputUTXO(i).value >= currentBlockReward
        }
        logger.info(s"Found ${utxosWithLender.size} collateral utxos with value >= ${currentBlockReward}")
        if(utxos.size() == 0 || utxosWithLender.isEmpty) {
          val trySelfCollat = mkCollateral(ctx, prover)
          trySelfCollat match {
            case Failure(e) =>
              e match {
                case noInputs: NotEnoughInputsException =>
                  logger.warn("Could not self-collateralize due to not having enough inputs")
                case _ =>
                  logger.error(s"Could not self-collateralize due to: ${e.getMessage}")
              }
            case Success(selfCollatId) =>
              logger.info(s"Successfully sent tx ${selfCollatId} to self-collateralize")
          }
          throw new CollateralNotFoundException("Could not find collateral utxos needed to create block candidate")
        }
        val collateralInput = InputUTXO(utxosWithLender.head)

        val emptyTree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        val holdingOutput = UTXO(holding, currentBlockReward,
          registers = Seq(
            emptyTree.ergoValue,
            ErgoValue.of(0),
            ErgoValue.of(BigInt(0).bigInteger),
            ErgoValue.of(ctx.getHeight.toLong)
          ))


        val lenderPK = collateralInput.registers(1).getValue.asInstanceOf[SigmaProp]
        val lenderBoolean = lenderPK match {
          case CSigmaProp(sigmaTree) => sigmaTree
          case _ => throw new InvalidSigmaBooleanException("Found invalid SigmaBoolean on collateral utxo")
        }
        val lenderAddress = Address.fromErgoTree(ErgoTree.fromSigmaBoolean(lenderBoolean), ctx.getNetworkType)
        logger.info(s"Got collateral utxo ${collateralInput.id} from lender ${lenderAddress}")
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
          .setPreHeader(ctx.createPreHeader().minerPk(lenderAddress.getPublicKeyGE).build())
          .buildTx(0, lenderAddress)
        val sTx = prover.sign(uTx)
        val utxBytes = uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign
        val collData = CollateralData(sTx.getId.replace("\"", ""), sTx.toJson(false, false), pkString, utxBytes, collateralInput.bytes)
        logger.info(s"Generated $collData with tx size: ${utxBytes.length} and input size: ${collateralInput.bytes.length}")
        collData
    }
  }

  def mkCollateral(ctx: BlockchainContext, wallet: NodeWallet): Try[String] = {
    Try {
      val blockReward = CollateralContract.coinsToIssue(ctx.getHeight)
      val inputs = new BoxLoader(ctx).loadBoxes.getInputs(blockReward + Parameters.MinFee * 2)

      val payout = RollupContracts.mkPayoutContract(ctx)

      val eval = RollupContracts.mkEvalContract(ctx,
        LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes,
        LFSMHelpers.getFPToken(ctx))
      val holding = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
      val collateral: Contract = CollateralContract.mkTestnetCollatContract(ctx, holding.hashedPropBytes)


      val outputs = Seq(UTXO(collateral, blockReward, registers = Seq(
        ErgoValue.of(0L),
        ErgoValue.of(wallet.contract.sigmaBoolean.get)
      )))
      val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee * 2)
      val uTx = TxBuilder(ctx)
        .setInputs(inputs: _*)
        .setOutputs((outputs ++ Seq(feeOutput)): _*)
        .buildTx(0, wallet.p2pk)

      val sTx = prover.sign(uTx)
      val txId = ctx.sendTransaction(sTx)
      txId
    }
  }
}
