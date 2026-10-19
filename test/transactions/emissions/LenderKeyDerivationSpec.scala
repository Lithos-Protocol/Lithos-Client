package transactions.emissions

import configs.EmissionConfig
import node.NodeApi
import node.model.DerivedNextKey
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.engine.wallet.{FundingAllocation, FundingSource}
import work.lithos.mutations.{InputUTXO, Token}

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Success

class LenderKeyDerivationSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  /** Key derivation never funds anything, so this never runs. */
  private object NoFunding extends FundingSource {
    def reserve(value: Long, tokens: Seq[Token]): FundingAllocation = throw new IllegalStateException("funding asked")
    def reserveCovering(value: Long): FundingAllocation = throw new IllegalStateException("funding asked")
    def reserveKnown(inputs: Seq[InputUTXO]): FundingAllocation = throw new IllegalStateException("funding asked")
    def giveBack(boxes: Seq[InputUTXO]): Unit = ()
  }

  /** A node wallet holding `held` of the prover's addresses, deriving the next ones in order. */
  private def keys(held: Int, maxLenderKeys: Int, unlocks: Boolean) = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = maxLenderKeys)
    val addresses = wallet.addresses.map(_.toString)
    val derived = new AtomicInteger(held)
    when(api.walletAddresses()).thenAnswer(_ => Success(addresses.take(derived.get)))
    when(api.deriveNextKey()).thenAnswer { _ =>
      val i = derived.getAndIncrement()
      Success(DerivedNextKey(s"m/44'/429'/0'/0/$i", addresses(i)))
    }
    val unlockAsked = new AtomicInteger(0)
    val txs = new EmissionTransactions(wallet, api, EmissionConfig.Default.copy(maxLenderKeys = maxLenderKeys),
      NoFunding, unlockWallet = () => { unlockAsked.incrementAndGet(); unlocks })
    (ctx, api, txs, unlockAsked)
  }

  "A join short of free keys" should "unlock the node wallet before deriving more" in {
    // The node derives only while its wallet is unlocked, which a restart undoes. Never unlocked,
    // every derivation failed and joins were held to the keys already derived.
    val (ctx, api, txs, unlockAsked) = keys(held = 1, maxLenderKeys = 4, unlocks = true)
    ctx.getClient.execute(c => txs.freeLenderKeys(c, Set.empty, 3)) should have size 3
    unlockAsked.get shouldBe 1
    verify(api, times(2)).deriveNextKey()
  }

  it should "derive nothing when the wallet cannot be unlocked" in {
    val (ctx, api, txs, _) = keys(held = 1, maxLenderKeys = 4, unlocks = false)
    ctx.getClient.execute(c => txs.freeLenderKeys(c, Set.empty, 3)) should have size 1
    verify(api, never()).deriveNextKey()
  }

  "Provisioning" should "derive keys up to maxLenderKeys once, so a quote counts them as free" in {
    val (ctx, api, txs, _) = keys(held = 1, maxLenderKeys = 4, unlocks = true)
    ctx.getClient.execute(c => txs.provisionLenderKeys(c)) shouldBe true
    verify(api, times(3)).deriveNextKey()
    ctx.getClient.execute(c => txs.provisionLenderKeys(c)) shouldBe true
    verify(api, times(3)).deriveNextKey()
  }
}
