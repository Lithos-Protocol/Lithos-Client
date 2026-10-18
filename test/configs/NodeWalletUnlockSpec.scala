package configs

import node.NodeApi
import node.model.WalletStatus
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicLong
import scala.util.{Failure, Success}

class NodeWalletUnlockSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def status(initialized: Boolean = true, unlocked: Boolean) =
    Success(WalletStatus(initialized, unlocked, "", 0, None))

  "The node wallet" should "be unlocked with node.pass when it is locked, and left alone when it is not" in {
    // A node restart locks its wallet, and the node then refuses every lender-key derivation. Joins
    // were left to keys already derived until somebody unlocked the wallet by hand.
    val api = mock[NodeApi]
    when(api.walletStatus()).thenReturn(status(unlocked = false), status(unlocked = true))
    when(api.unlockWallet("pass")).thenReturn(Success(()))
    val unlock = new NodeWalletUnlock(api, "pass")
    unlock.ensure() shouldBe true
    unlock.ensure() shouldBe true
    verify(api, times(1)).unlockWallet("pass")
  }

  it should "not be asked again until the retry delay passes after a failed unlock" in {
    // An open quote endpoint asks on every request; a failed unlock must not become a loop of them.
    val api = mock[NodeApi]
    val clock = new AtomicLong(1000L)
    when(api.walletStatus()).thenReturn(status(unlocked = false))
    when(api.unlockWallet("pass")).thenReturn(Failure(new RuntimeException("wallet is busy")))
    val unlock = new NodeWalletUnlock(api, "pass", retryMs = 60000L, clock = () => clock.get())
    unlock.ensure() shouldBe false
    unlock.ensure() shouldBe false
    verify(api, times(1)).unlockWallet("pass")
    clock.addAndGet(60000L)
    unlock.ensure() shouldBe false
    verify(api, times(2)).unlockWallet("pass")
  }

  it should "report an uninitialized wallet as locked without sending the password" in {
    val api = mock[NodeApi]
    when(api.walletStatus()).thenReturn(status(initialized = false, unlocked = false))
    new NodeWalletUnlock(api, "pass").ensure() shouldBe false
    verify(api, never()).unlockWallet("pass")
  }
}
