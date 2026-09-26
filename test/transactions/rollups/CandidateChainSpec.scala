package transactions.rollups

import lfsm.LFSMPhase
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting}
import transactions.candidate.CandidateBundle
import transactions.engine.execution.RollupExecution
import transactions.rollups.TransactionMessages.RollupTxType._
import work.lithos.mutations.{InputUTXO, TxBuilder, UTXO}

import scala.collection.JavaConverters._

/**
 * How candidate transactions chain inside this miner's own block.
 *
 * Two rules live here. A pending transform is rebuilt fee-less rather than carried, but only while it
 * is the rollup's sole unconfirmed spend and nothing in the mempool builds on it; past that it is
 * someone's work and is carried. And transactions funded from one pinned box chain through each
 * other's change, sharing one bundle so the package takes all of them or none.
 */
class CandidateChainSpec extends AnyFlatSpec with Matchers {

  "A pending transform" should "be rebuilt when it is the only spend and nothing builds on it" in {
    RollupExecution.replaceableTransform(LFSMPhase.HOLDING, LFSMPhase.EVAL, 1, _ => true, outputsSpent = false) shouldBe
      Some(HoldingTransform)
    RollupExecution.replaceableTransform(LFSMPhase.EVAL, LFSMPhase.PAYOUT, 1, _ => true, outputsSpent = false) shouldBe
      Some(EvalTransform)
  }

  it should "be carried when anything in the mempool spends one of its outputs" in {
    // Another miner's payout on the transform's box, or a send of this client's own that took its
    // change: replacing the transform would drop that work from the block
    RollupExecution.replaceableTransform(LFSMPhase.EVAL, LFSMPhase.PAYOUT, 1, _ => true, outputsSpent = true) shouldBe None
  }

  it should "be carried, without reading the mempool, when it is not valid in this block" in {
    var read = false
    def spent: Boolean = { read = true; false }
    RollupExecution.replaceableTransform(LFSMPhase.EVAL, LFSMPhase.PAYOUT, 1, _ => false, spent) shouldBe None
    read shouldBe false
  }

  it should "be carried when more is chained behind it" in {
    RollupExecution.replaceableTransform(LFSMPhase.EVAL, LFSMPhase.PAYOUT, 2, _ => true, outputsSpent = false) shouldBe None
  }

  "A pending spend that stays in its phase" should "never be replaced, nor the mempool read to decide" in {
    // A submission or a fraud proof is someone's claim or someone's reward, not open pool work
    var read = false
    def spent: Boolean = { read = true; false }
    RollupExecution.replaceableTransform(LFSMPhase.HOLDING, LFSMPhase.HOLDING, 1, _ => true, spent) shouldBe None
    RollupExecution.replaceableTransform(LFSMPhase.EVAL, LFSMPhase.EVAL, 1, _ => true, spent) shouldBe None
    read shouldBe false
  }

  private def tx(id: String, inputs: String*): CandidateTx =
    CandidateTx(id, "{}", CandidateTx.FraudProof, inputs.toSet, leaf = id)

  "A pinned chain" should "take the next funded transaction after the ones it spends from" in {
    val first = CandidateBundle(Vector(tx("fp1", "pin")))
    val second = CandidateBundle(Vector(tx("fp2", "change-of-fp1")))
    RollupExecution.extendChain(first, second).members.map(_.id) shouldBe Seq("fp1", "fp2")
  }

  it should "carry a mempool ancestor once when two links share it" in {
    val parent = CandidateTx("parent", "{}", CandidateTx.MempoolAncestor, leaf = "parent")
    val first = CandidateBundle(Vector(parent, tx("fp1", "pin")),
      Seq(ChainFromMempool("parent"), IncludeExisting("parent")))
    val second = CandidateBundle(Vector(parent, tx("fp2", "change-of-fp1")),
      Seq(ChainFromMempool("parent"), IncludeExisting("parent")))
    val chain = RollupExecution.extendChain(first, second)
    chain.members.map(_.id) shouldBe Seq("parent", "fp1", "fp2")
    chain.interactions shouldBe Seq(ChainFromMempool("parent"), IncludeExisting("parent"))
    chain.carriesItsParents shouldBe true
  }

  private lazy val fake = FakeNodeContext()

  private def walletBox(ctx: BlockchainContext, value: Long, seed: String): InputUTXO =
    UTXO(fake._3.contract, value).toInput(ctx, ErgoId.create(seed * 32), 0.toShort)

  "The change a funded transaction returns" should "be its last output returning the funding box whole" in {
    // A fraud proof pays its reward to the funding box's own script ahead of the change, and the
    // reward can equal the pin's value, so the last matching output is the change
    fake._1.getClient.execute { ctx =>
      val pin = walletBox(ctx, Parameters.OneErg / 20, "0a")
      val other = walletBox(ctx, Parameters.OneErg * 3 / 10, "0b")
      val signed = fake._3.sign(TxBuilder(ctx)
        .setInputs(pin, other)
        .setOutputs(UTXO(fake._3.contract, Parameters.OneErg / 20), UTXO(fake._3.contract, Parameters.OneErg / 4))
        .buildTx(0, fake._3.p2pk))
      val outputs = signed.getOutputsToSpend.asScala.toSeq
      outputs.map(_.getValue.toLong) shouldBe Seq(Parameters.OneErg / 20, Parameters.OneErg / 4, Parameters.OneErg / 20)

      RollupExecution.changeOf(signed, pin).map(_.id.toString) shouldBe Some(outputs(2).getId.toString)
    }
  }

  it should "be absent when no output returns the funding box whole" in {
    fake._1.getClient.execute { ctx =>
      val pin = walletBox(ctx, Parameters.OneErg / 20, "0c")
      val signed = fake._3.sign(TxBuilder(ctx)
        .setInputs(pin)
        .setOutputs(UTXO(fake._3.contract, Parameters.OneErg / 20 - Parameters.MinFee), UTXO.feeBox(Parameters.MinFee))
        .buildTx(0, fake._3.p2pk))
      RollupExecution.changeOf(signed, pin) shouldBe None
    }
  }

  "A funded submission's top-up" should "leave room for the change box a token-bearing wallet input needs" in {
    // The failure from the log: a 0.0046 ERG box carrying 20 LIT covered fees and bond exactly, and
    // the LIT then had no ERG to leave in
    fake._1.getClient.execute { ctx =>
      val lit = work.lithos.mutations.Token("7b728ca02a23085f1f7093e949535938c55307ab1b61e848008201c5109bd18b",
        20000000000L)
      val withLit = UTXO(fake._3.contract, 4600000L, Seq(lit)).toInput(ctx, ErgoId.create("0c" * 32), 0.toShort)
      val plain = walletBox(ctx, 4600000L, "0d")
      RollupExecution.submissionShortfall(Seq(withLit), fees = 2000000L, bond = 2600000L) shouldBe UTXO.MIN_CHANGE
      withClue("without tokens any sub-minimum change folds into the fee output: ") {
        RollupExecution.submissionShortfall(Seq(plain), fees = 2000000L, bond = 2600000L) shouldBe 0L
      }
      RollupExecution.submissionShortfall(Seq(plain), fees = 2000000L, bond = 3000000L) shouldBe 400000L
    }
  }
}
