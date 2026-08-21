package stratum

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, RollupContracts}
import mutations.{BoxLoader, NodeWallet, NotEnoughInputsException}
import node.{MutationConversions, NodeApi}
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoClientException, ErgoProver, ErgoValue, Parameters}
import org.ergoplatform.sdk.{ErgoId, JavaHelpers, SecretString}
import org.slf4j.{Logger, LoggerFactory}
import scorex.crypto.hash.Blake2b256
import sigma.{Colls, SigmaProp}
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, CSigmaProp, ProveDHTuple, ProveDlog, SigmaBoolean, SigmaConjecture, SigmaLeaf, TrivialProp}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap
import org.ergoplatform.appkit.scalaapi._

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.util.{Failure, Success, Try}

class CollateralRetriever(client: ErgoClient, prover: NodeWallet, nodeApi: NodeApi) {
  private val logger: Logger = LoggerFactory.getLogger("CollateralRetriever")
  def getCollateral: CollateralData = {
    client.execute{
      ctx =>

        val payout   = RollupContracts.mkPayoutContract(ctx)
        val eval     = RollupContracts.mkEvalContract(ctx,
          LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes,
          LFSMHelpers.getFPToken(ctx))
        val holding  = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
        val gate = CollateralContract.mkEmissionGateContract(ctx)

        val collatBoxes = nodeApi
          .unspentBoxesByTokenId(LFSMHelpers.COLLAT_TOKEN.toString(), Paging.all(500), SortDirection.Asc, MempoolOptions.ConfirmedOnly)
          .get
          .map(MutationConversions.IndexedBoxOps(_).toInputUTXO(ctx))
          .filter(_.contract.hashedPropBytesHex != gate.hashedPropBytesHex)

        val collat = collatBoxes.head
        val feeValue = collat.parseReg[Long](0)
        val lenderPK = collat.registers(1).getValue.asInstanceOf[SigmaProp]

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

        val rewards = collat.registers(2).getValue.asInstanceOf[(Long, sigma.Coll[(sigma.Coll[Byte], Long)])]
        val litBlockReward = rewards._1
        val founderSplit = rewards._2.toArray.map(p => (p._1.toArray, p._2)).toSeq
        val rollupHash = collat.parseReg[sigma.Coll[Byte]](4).toArray

        val heldLIT = collat.tokens.lift(1).map(_.amount).getOrElse(0L)
        val privRewards = founderSplit.map(_._2).sum
        val permitChange = heldLIT - (litBlockReward + privRewards)
        val finderLIT = litBlockReward / 25
        val poolLIT = litBlockReward - finderLIT
        val height = ctx.getHeight + 1

        if (!rollupHash.sameElements(holding.hashedPropBytes))
          logger.warn(s"Got collateral box with unknown holding hash ${Hex.toHexString(rollupHash)}")

        // Small boxes first, so what is left for the holding box is everything the fee channel did not
        // have to spend. Founder boxes carry a contract-imposed 1e6 floor; the other two only have to
        // clear the consensus per-byte minimum.
        val founderBoxes = founderSplit.map { case (hash, amount) =>
          val recipient = Seq(LFSMHelpers.FOUNDER_1, LFSMHelpers.FOUNDER_2, LFSMHelpers.FOUNDER_3)
            .find(_.hashedPropBytes.sameElements(hash))
            .getOrElse(throw new RuntimeException(s"unknown founder hash"))
          UTXO(recipient, 1000000L, Seq(Token(LFSMHelpers.LIT_ID, amount)))
        }
        val permitBox =
          if (permitChange > 0) Seq(UTXO(Contract.fromAddress(lenderAddress), 1000000L,
            Seq(Token(LFSMHelpers.LIT_ID, permitChange))))
          else Seq.empty[UTXO]
        val finderBox =
          if (finderLIT > 0) Seq(UTXO(prover.contract, 100000L, Seq(Token(LFSMHelpers.LIT_ID, finderLIT))))
          else Seq.empty[UTXO]
        val proofOfSpend = UTXO(gate, 150000L, Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)),
          Seq(ErgoValue.of(Colls.fromArray(Contract.fromAddress(lenderAddress).hashedPropBytes), scalaByteType)))
          .setCreationHeight(height)

        val trailing = founderBoxes ++ permitBox ++ finderBox ++ Seq(proofOfSpend)
        val holdingValue = collat.value - trailing.map(_.value).sum

        if (collat.value - holdingValue > feeValue)
          logger.info("  !! that exceeds the fee channel: holdingValid will reject this")

        val holdingBox = UTXO(holding, holdingValue,
          if (poolLIT > 0) Seq(Token(LFSMHelpers.LIT_ID, poolLIT)) else Seq.empty[Token],
          Seq(
            PlasmaMap.empty.ergoValue,                        // R4 empty NISP tree
            ErgoValue.of(0),                                  // R5 miner count
            ErgoValue.of(BigInt(0).bigInteger),               // R6 total score
            ErgoValue.of(height.toLong),                      // R7 period start — must equal HEIGHT
            ErgoValue.of(Colls.fromArray(Contract.fromAddress(lenderAddress).hashedPropBytes), scalaByteType)))
            // R8 the miner's hashed prop bytes

        val preHeader = ctx.createPreHeader()
          .height(height)
          .minerPk(lenderAddress.getPublicKeyGE)
          .build()

        val txB = TxBuilder(ctx)
        val uTx = txB
          .setInputs(collat)
          .setOutputs((holdingBox +: trailing):_*)
          .setPreHeader(preHeader)
          .buildTx(0, lenderAddress)
        val sTx = prover.sign(uTx)
        val utxBytes = uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign
        val collData = CollateralData(sTx.getId.replace("\"", ""), sTx.toJson(false, false), pkString, utxBytes, collat.bytes,
          collat.id.toString, lenderAddress.toString)
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

//      var emissions = InputUTXO(ctx.getCoveringBoxesFor(CollateralContract.mkEmissionsContract(ctx, collateral.hashedPropBytes).address(ctx),
//        Parameters.MinFee, IndexedSeq(Token(LFSMHelpers.EMISSION_NFT, 1L).toErgo).asJava).getBoxes.get(0))
      var emissions = InputUTXO(ctx.getBoxesById("asddasdas").head)
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
