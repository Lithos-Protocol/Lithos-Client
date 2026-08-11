package support

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Proves the fake context before anything is built on it — specifically that the prover really does
 * expose EIP-3 addresses, since every member of `NodeWallet` reads them and a DLog-only prover would
 * make the whole thing throw on construction.
 */
class FakeNodeContextProbeSpec extends AnyFlatSpec with Matchers {

  "FakeNodeContext" should "build a NodeWallet with the requested number of EIP-3 addresses" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 3)
    wallet.addresses should have size 3
    wallet.p2pk shouldEqual wallet.addresses.head
  }

  it should "expose signable trees covering the master key and every index" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 3)
    // Master plus three indices, and index 0's tree is in there — that is what the wallet filter uses.
    wallet.signableTrees should have size 4
    wallet.signableTrees should contain(wallet.contract.ergoTreeHex)
  }

  it should "derive one reward script per address, distinct from the P2PK trees" in {
    // A coinbase pays `pk && HEIGHT > creationHeight + 720`, not a plain key, which is the whole
    // reason WalletManager tracks them separately.
    val (_, _, wallet) = FakeNodeContext(numAddresses = 3)
    wallet.rewardTrees should have size 3
    wallet.rewardTrees.keySet.intersect(wallet.signableTrees) shouldBe empty
  }

  it should "hand out a client that can be executed more than once" in {
    val (ctx, _, _) = FakeNodeContext()
    val first = ctx.getClient.execute(c => c.getHeight)
    val second = ctx.getClient.execute(c => c.getHeight)
    first shouldEqual second
    first should be > 0
  }
}
