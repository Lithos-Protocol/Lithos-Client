package stratum

import lfsm.LFSMHelpers
import lfsm.collateral.CollateralContract
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{Address, ErgoClient, ErgoId, ErgoProver, ErgoValue, JavaHelpers, Parameters}
import org.slf4j.{Logger, LoggerFactory}
import sigma.SigmaProp
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, CSigmaProp, ProveDHTuple, ProveDlog, SigmaBoolean, SigmaConjecture, SigmaLeaf, TrivialProp}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import scala.util.Try

class CollateralRetriever(client: ErgoClient, prover: ErgoProver) {
  private val logger: Logger = LoggerFactory.getLogger("CollateralRetriever")
  def getCollateral = {
    client.execute{
      ctx =>
        val payout   = RollupContracts.mkPayoutContract(ctx)
        // TODO: Change back fp token after first week of testnet
        val eval     = RollupContracts.mkEvalContract(ctx,
          LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes,
          LFSMHelpers.FP_TOKEN)
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
        if(utxos.size() == 0 || utxosWithLender.isEmpty)
          throw new CollateralNotFoundException("Could not find collateral utxos needed to create block candidate")
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
        (sTx.getId.replace("\"", ""), sTx.toJson(false, false), pkString)
    }
  }
}
