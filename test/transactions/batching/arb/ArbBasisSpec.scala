package transactions.batching.arb

import node.MutationConversions._
import org.ergoplatform.appkit.SignedTransaction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{ErgoDexFixtures, LDNodeFixtures}
import transactions.batching.ergodex.ErgoDexPool
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBundle
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.collection.JavaConverters._

/**
 * The basis the arb stage reads from the DEX batchers' bundles: what they spend, and what they leave unspent,
 * decoded from the signed JSON a candidate carries, with box ids as the chain will know them.
 */
class ArbBasisSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  /** Two legs through one real ErgoDEX pool, the second spending the first's pool successor and carry. */
  private def chain(): (SignedTransaction, SignedTransaction) = nodeContext.getClient.execute { ctx =>
    val poolBox = ErgoDexFixtures.Sell.poolBox.toInputUTXO(ctx)
    val pool = ErgoDexArbPool(ErgoDexPool.parse(ErgoDexFixtures.Sell.poolBox).get)
    val carry = LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, 3000000000L), 2, "ab" * 32).toInputUTXO(ctx)
    val sell = pool.swap(1000000000L, xIn = true).get
    val first = ArbLeg.build(ctx, wallet, sell, poolBox, Seq(carry), wallet.contract, 1871500)
    val nextPool = InputUTXO(first.getOutputsToSpend.get(0))
    val nextCarry = InputUTXO(first.getOutputsToSpend.get(1))
    val buy = sell.after.withBox(nextPool.id.toString).swap(sell.amountOut, xIn = false).get
    (first, ArbLeg.build(ctx, wallet, buy, nextPool, Seq(nextCarry), wallet.contract, 1871500))
  }

  private def outputIds(tx: SignedTransaction): Seq[String] = tx.getOutputsToSpend.asScala.map(_.getId.toString)

  "A basis" should "name every box the bundles spend, and the outputs none of them spends" in {
    val (first, second) = chain()
    val basis = ArbBasis.of(Seq(CandidateBundle(Vector(CandidateTx.signed(first, "dex-batch"),
      CandidateTx.signed(second, "dex-batch")))))
    basis.spent shouldBe CandidateTx.inputIds(first) ++ CandidateTx.inputIds(second)
    basis.txIds shouldBe Set(CandidateTx.txId(first), CandidateTx.txId(second))
    // The first leg's outputs are both spent by the second, so only the second's are left
    basis.created.map(_.box.boxId) shouldBe outputIds(second)
    basis.created.map(_.txId).distinct shouldBe Seq(CandidateTx.txId(second))
    basis.created.head.box.value shouldBe second.getOutputsToSpend.get(0).getValue
    basis.created.head.box.ergoTree shouldBe ErgoDexFixtures.Sell.poolBox.ergoTree
  }

  it should "count a transaction two bundles carry once" in {
    val (first, _) = chain()
    val tx = CandidateTx.signed(first, "dex-batch")
    val basis = ArbBasis.of(Seq(CandidateBundle(Vector(tx)), CandidateBundle(Vector(tx))))
    basis.created.map(_.box.boxId) shouldBe outputIds(first)
  }

  it should "offer no output of a body that does not decode, but still count its inputs as spent" in {
    val (first, _) = chain()
    val broken = CandidateTx.signed(first, "dex-batch").copy(json = "{not json")
    val basis = ArbBasis.of(Seq(CandidateBundle(Vector(broken))))
    basis.created shouldBe empty
    basis.spent shouldBe CandidateTx.inputIds(first)
  }

  it should "be empty for no bundles" in {
    ArbBasis.of(Seq.empty) shouldBe ArbBasis.Empty
  }
}
