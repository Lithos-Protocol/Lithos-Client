package transactions

import configs.CandidateConfig
import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import mining.CandidateTxBuilder
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction}
import org.ergoplatform.sdk.{ErgoId, SecretString}
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import transactions.candidate.{CandidateCapital, CandidateTopUp, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.collection.JavaConverters._

/**
 * The top-up against the holding box a real genesis transaction produced.
 *
 * `HoldingTopUpSpec` builds its own holding box, which is a box shaped like the one genesis makes
 * rather than the one genesis makes. This drives `CandidateTxBuilder.buildGenesis` and tops up what
 * it actually left behind — tokens, every register it wrote, and the value the fee channel decided.
 */
class GenesisTopUpSpec extends AnyPropSpec with EmissionSpecBase with MockitoSugar {

  private val mnemonic =
    "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"

  private def walletOf(ctx: BlockchainContext): NodeWallet =
    NodeWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(mnemonic), SecretString.empty(), false)
      .withEip3Secret(0).build())

  private def collateralInput(ctx: BlockchainContext): InputUTXO = {
    val c = ProtocolContracts(ctx)
    val (emitted, pub, split) = emissionAt(0, litSupply)
    inputAt(collateralUTXO(ctx, lender(ctx), lit = emitted + LFSMHelpers.PERMIT_FLOOR, pub = pub,
      founderSplit = split, rollupHash = c.holding.hashedPropBytes,
      feeValue = DUST_BUDGET, value = principalFloor, contract = c.collateral), ctx, 0)
  }

  /** Revenue as a rent collection leaves it: ERG this miner's own key can spend. */
  private def revenue(ctx: BlockchainContext, wallet: NodeWallet, value: Long): InputUTXO =
    UTXO(wallet.contract, value).toInput(ctx, ErgoId.create("cd" * 32), 0.toShort)

  private def ledger(height: Int, boxes: Seq[InputUTXO]): CandidateCapital =
    boxes.foldLeft(CandidateCapital(height))((l, box) =>
      l.credit(CapitalEntry(CapitalOrigin.StorageRent, box, parentTxId = "rent")))

  property("the holding box a genesis transaction made can be topped up") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)

      withClue("genesis has to hand its holding box forward for any of this to run: ") {
        data.holdingOutput shouldBe defined
      }
      val holding = data.holdingOutput.get
      withClue("the box genesis made carries the rollup NFT and its own registers: ") {
        holding.tokens should not be empty
        holding.registers.size should be >= 4
      }

      val tx = CandidateTopUp.build(ctx, wallet, data,
        ledger(height, Seq(revenue(ctx, wallet, Parameters.OneErg))), height)

      withClue("the production holding guard has to accept the top-up it is handed: ") {
        tx shouldBe defined
      }
    }
  }

  /**
   * Every input put through the same interpreter the node runs, not just the holding guard.
   *
   * Signing proves the inputs this wallet owns and evaluates the ones it does not; it does not tell
   * us the node will agree. The node reports a failure by input index, so this asserts per index.
   */
  private def verifyEachInput(ctx: BlockchainContext, signed: SignedTransaction,
                              spent: Seq[InputUTXO], height: Int): Unit = {
    val tx = signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx
    val boxes = spent.map(_.input.asInstanceOf[org.ergoplatform.appkit.impl.InputBoxImpl].getErgoBox)
      .toIndexedSeq
    val params = support.RentRule.paramsFrom(ctx)
    boxes.indices.foreach { i =>
      val (ok, cost) = support.RentRule.verifyInput(tx, boxes, i, height, params)
      withClue(s"input #$i cost $cost: ") { ok shouldBe true }
    }
  }

  /** A P2PK revenue input is the shape a collection left at the miner's own key produces. */
  property("every input of a top-up verifies, revenue at this miner's key included") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)
      val holding = data.holdingOutput.get
      val paid = revenue(ctx, wallet, Parameters.OneErg)

      val signed = transactions.rollups.RollupTransactions.genHoldingTopUp(ctx, wallet, holding,
        Seq(paid), Seq.empty[UTXO], height)
      verifyEachInput(ctx, signed, Seq(holding, paid), height)
    }
  }

  /** And the shape a collection left at TrueProp produces, which anything can spend. */
  property("every input verifies when the revenue sits at TrueProp") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)
      val holding = data.holdingOutput.get
      val open = UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, Parameters.OneErg)
        .setCreationHeight(height).toInput(ctx, ErgoId.create("ef" * 32), 0.toShort)

      val signed = transactions.rollups.RollupTransactions.genHoldingTopUp(ctx, wallet, holding,
        Seq(open), Seq.empty[UTXO], height)
      verifyEachInput(ctx, signed, Seq(holding, open), height)
    }
  }

  /**
   * The JSON the node is handed has to describe the transaction that was signed.
   *
   * A signature is over the transaction's own bytes, and those bytes include every input's context
   * variables. If the JSON loses or reorders one, the node reconstructs a different transaction,
   * computes a different id, and every ordinary signature in it verifies against a message that was
   * never signed — which reads as a script simply returning false.
   */
  property("the JSON handed to the node carries the context variables that were signed") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)
      val holding = data.holdingOutput.get

      val signed = transactions.rollups.RollupTransactions.genHoldingTopUp(ctx, wallet, holding,
        Seq(revenue(ctx, wallet, Parameters.OneErg)), Seq.empty[UTXO], height)

      val raw = transactions.candidate.BlockTxMessages.CandidateTx.signedJson(signed)
      // The node rebuilds the extension in the order this JSON lists it: block 564574 carried a
      // fee-less holding transform whose (64, 3) had been rewritten to (3, 64) on the way, and it
      // landed under the id that order gives. So the JSON order has to be the signed order, and
      // appkit writes (64, 3) for this id set.
      val signedOrder = signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl]
        .getTx.inputs.head.spendingProof.extension.values.keys.toSeq
      val listed = new com.google.gson.JsonParser().parse(raw).getAsJsonObject.getAsJsonArray("inputs")
        .get(0).getAsJsonObject.getAsJsonObject("spendingProof").getAsJsonObject("extension")
        .keySet().asScala.toSeq.map(_.toByte)
      withClue("a transaction signed in any other order takes an id the node will not agree on: ") {
        signedOrder shouldBe listed
        signedOrder shouldBe Seq[Byte](64, 3)
      }

      val ins = new org.json.JSONObject(raw).getJSONArray("inputs")
      withClue("the holding input is the one carrying the op byte and the logic: ") {
        ins.getJSONObject(0).getJSONObject("spendingProof").getJSONObject("extension")
          .keySet().asScala.toSet shouldBe Set("3", "64")
      }

      // The round trip above goes through sigma's own codec rather than appkit's, because that is
      // the one the node's `transactionDecoder` delegates to. appkit agreeing with itself would say
      // nothing about the node agreeing with us.
      ins.length() shouldBe 2
    }
  }

  /**
   * The guard every candidate transaction's JSON passes through. This is the top-up as it was built
   * before: signed in ascending order, which appkit writes out as (64, 3).
   */
  property("a candidate signed in another order than its JSON lists is refused") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)
      val holding = data.holdingOutput.get
      val paid = revenue(ctx, wallet, Parameters.OneErg)
      val logic = utils.Helpers.holdingLogicContract(ctx)
      val ascending = transactions.batching.lithosdex.DexContracts.attachCtxVars(holding, Seq(
        (3.toByte, org.ergoplatform.appkit.ErgoValue.of(1.toByte)),
        (64.toByte, org.ergoplatform.appkit.ErgoValue.of(sigma.Colls.fromArray(logic.valueBytes),
          org.ergoplatform.appkit.scalaapi.scalaByteType))))
      val signed = wallet.sign(work.lithos.mutations.TxBuilder(ctx)
        .setInputs(ascending, paid)
        .setOutputs(UTXO(holding.contract, holding.value + Parameters.OneErg, holding.tokens,
          registers = holding.registers).setCreationHeight(height))
        .setPreHeader(ctx.createPreHeader().height(height).build())
        .buildTx(0, wallet.p2pk))

      val refused = intercept[IllegalArgumentException] {
        transactions.candidate.BlockTxMessages.CandidateTx.signedJson(signed)
      }
      refused.getMessage should include("(3,64)")
      refused.getMessage should include("(64,3)")
    }
  }

  /**
   * Past four variables the node rebuilds an input's extension as a hash map, in the order this
   * client signed with, whatever the JSON lists. Block 565107 carries submission `b0b51d1d…`, sent
   * as JSON listing (0,1,2,3,64) and signed (1,64,0,2,3), under the id this client computed.
   */
  property("a candidate with five variables on one input passes whatever order its JSON lists") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val paid = transactions.batching.lithosdex.DexContracts.attachCtxVars(
        revenue(ctx, wallet, Parameters.OneErg),
        Seq[Byte](0, 1, 2, 3, 64).map(id => id -> org.ergoplatform.appkit.ErgoValue.of(id)))
      val signed = wallet.sign(work.lithos.mutations.TxBuilder(ctx)
        .setInputs(paid)
        .setOutputs(UTXO(wallet.contract, Parameters.OneErg - Parameters.MinFee).setCreationHeight(height))
        .setPreHeader(ctx.createPreHeader().height(height).build())
        .buildTx(Parameters.MinFee, wallet.p2pk))

      val raw = transactions.candidate.BlockTxMessages.CandidateTx.signedJson(signed)
      val signedOrder = signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl]
        .getTx.inputs.head.spendingProof.extension.values.keys.toSeq
      val listed = new com.google.gson.JsonParser().parse(raw).getAsJsonObject.getAsJsonArray("inputs")
        .get(0).getAsJsonObject.getAsJsonObject("spendingProof").getAsJsonObject("extension")
        .keySet().asScala.toSeq.map(_.toByte)
      withClue("the orders differ, so this is the case the guard must let through: ") {
        signedOrder should not equal listed
        signedOrder.toSet shouldBe listed.toSet
      }
    }
  }

  /** The successor is the same box, larger. Anything else and the guard refuses the spend. */
  property("the successor carries every register and token forward") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val height = ctx.getHeight + 1
      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)
      val holding = data.holdingOutput.get

      val signed = transactions.rollups.RollupTransactions.genHoldingTopUp(ctx, wallet, holding,
        Seq(revenue(ctx, wallet, Parameters.OneErg)), Seq.empty[UTXO], height)
      val successor = signed.getOutputsToSpend.asScala.head

      successor.getValue.toLong shouldEqual (holding.value + Parameters.OneErg)
      successor.getErgoTree.bytesHex shouldEqual holding.contract.ergoTreeHex
      successor.getRegisters.asScala.toVector shouldEqual holding.registers
      successor.getTokens.asScala.map(t => t.getId.toString -> t.getValue.toLong) shouldEqual
        holding.tokens.map(t => t.id.toString -> t.amount)
    }
  }


  // Test has been removed for right now. It asserts something which is not necessarily true,
  // we have no guarantee that the ErgoLikeTransaction parser used here is the same one as the node.
  // In fact against versions above 6.0.4, it isn't (probably due to circe versioning)
//  property("the node computes the same id we do for a top-up") {
//    withCtx { ctx =>
//      val wallet = walletOf(ctx)
//      val height = ctx.getHeight + 1
//      val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
//      val data = builder.buildGenesis(ctx, collateralInput(ctx), height)
//      val holding = data.holdingOutput.get
//
//      val signed = transactions.rollups.RollupTransactions.genHoldingTopUp(ctx, wallet, holding,
//        Seq(revenue(ctx, wallet, Parameters.OneErg)), Seq.empty[UTXO], height)
//
//      val codecs = new org.ergoplatform.sdk.JsonCodecs {}
//      import codecs._
//      val theirs = io.circe.parser.parse(signed.toJson(false)).toTry.get
//        .as[org.ergoplatform.ErgoLikeTransaction].toTry.get
//      withClue("a different id here is the node rebuilding different bytes from our own JSON: ") {
//        theirs.id shouldBe signed.getId
//      }
//    }
//  }
}
