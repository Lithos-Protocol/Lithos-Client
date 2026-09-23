package transactions.batching.lithosdex

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

/**
 * Deployment verification against two real mainnet deployments: the canonical ERG:LIT pool, and a second pool
 * minted by someone else through the same genesis. The trees are the live boxes' ErgoTrees, saved from
 * the chain rather than rebuilt here, so a compile that drifted from what the chain holds fails.
 */
class LDDeploymentsSpec extends AnyFlatSpec with Matchers {

  private val Mainnet = NetworkType.MAINNET

  private def fixture(name: String): String = {
    val src = Source.fromResource(s"lithosdex-deployments/$name")
    try src.mkString.trim finally src.close()
  }

  private val LitPoolNft = "c0b21ac51481a5d242809d9b1e189a8baf3803fe347ca01833c1aeb380e6eb29"
  private val LitVaultNft = "7f45bcbf13b9925c3eef35ff247ae46b80db2e2703ce2a8bfddf294e1e60c2d7"
  private val LitProvToken = "b279187c993465c666cb17646fb87325dd7d03de6ac16dfac9c50eaea71f2fc6"
  private val Lit = "c1980d829988229516430a47a5eca376060b6ce859616db0936e78ab25cb6de7"
  private val LitGuardHash = "a089b9b54b29d7fc71b096c87fce0616ae83cbd5c163ac153e2e1f9d87a8d3c5"

  private val SecondPoolNft = "77ebb7ac1a9386d6cc5b64fffa52602bea574b0bb454fb856188ae54acb84894"
  private val SecondVaultNft = "9cec4df2183f6047edf61ec9bb60649c22362e17c074fac7c41d75c5229129f0"
  private val SecondProvToken = "27bc91eaf7e72d2a17cf6e73de349262c87bea7894121963b4100a644a08da25"
  private val SecondToken = "fffe6122886e3b0ab9b72b401b39bf8d3f13580c1335a41d91d19deb8038ccd4"
  // Derived by hand from the provision logic, outside this client, when the pool was first checked
  private val SecondGuardTree =
    "1b31010e205c8dfb9ff13352181a87617d8f3f29d041bb0327b4a2cd521e5e6efc61313d00ea02d193cbe4e3400e7300d40840"
  private val SecondGuardHash = "15e9919219319b00dd92fa593e5a2f7beb75e876be8154f9b1b6e9dc9415a04b"

  private val litPool = fixture("erg-lit-pool.hex")
  private val litVault = fixture("erg-lit-vault.hex")
  private val secondPool = fixture("erg-second-pool.hex")
  private val secondVault = fixture("erg-second-vault.hex")

  private val litAssets = Seq(LitPoolNft -> 1L, Lit -> 145230319426812L, LitProvToken -> 999999999999994L)
  private val secondAssets = Seq(SecondPoolNft -> 1L, SecondToken -> 100000000L, SecondProvToken -> 1000000000000000L)

  "LDDeployments" should "scan the template under the hash the node's indexer keys it by" in {
    // The hash a public indexed node answered with both live pools
    LDDeployments.poolTemplateHash(Mainnet) shouldBe
      "76fb608217788427bc0ee0c4f7319982b618d98857ba5c1c8753b2c12ee4c139"
  }

  it should "accept the canonical pool and compile its vault to the live vault's tree" in {
    val contracts = LDDeployments.verify(Mainnet, litPool, litAssets).getOrElse(fail("ERG:LIT was refused"))
    contracts.vaultNFT.toString shouldBe LitVaultNft
    contracts.provToken.toString shouldBe LitProvToken
    contracts.feeVault.ergoTreeHex shouldBe litVault
  }

  it should "accept a deployment someone else minted, down to its guard" in {
    val contracts = LDDeployments.verify(Mainnet, secondPool, secondAssets).getOrElse(fail("the second deployment was refused"))
    contracts.poolNFT.toString shouldBe SecondPoolNft
    contracts.vaultNFT.toString shouldBe SecondVaultNft
    contracts.feeVault.ergoTreeHex shouldBe secondVault
    contracts.provisionGuard.ergoTreeHex shouldBe SecondGuardTree
    contracts.provisionGuard.hashedPropBytesHex shouldBe SecondGuardHash
  }

  it should "refuse a pool that names another deployment's guard" in {
    // Same template, same vault, but provisions would be checked against ERG:LIT's guard
    val forged = secondPool.replace(SecondGuardHash, LitGuardHash)
    forged should not be secondPool
    LDDeployments.verify(Mainnet, forged, secondAssets) shouldBe None
  }

  it should "refuse a pool whose NFT is not held as a single unit" in {
    LDDeployments.verify(Mainnet, secondPool, (SecondPoolNft -> 2L) +: secondAssets.tail) shouldBe None
  }

  it should "leave the provision token to the vault check, which the live vault fails" in {
    // The pool's tree never names its provision token, so another token in slot 2 compiles the same pool
    // tree. Only the vault is compiled with it, which is why discover() also requires the vault to stand.
    val other = "11" * 32
    val contracts = LDDeployments.verify(Mainnet, secondPool, Seq(SecondPoolNft -> 1L, SecondToken -> 1L, other -> 1L))
      .getOrElse(fail("the pool tree alone does not depend on the provision token"))
    contracts.feeVault.ergoTreeHex should not be secondVault
  }

  "DexContracts" should "hand each builder the deployment its box's own ids belong to" in {
    val second = LDDeployments.verify(Mainnet, secondPool, secondAssets).get
    DexContracts.register(second)

    DexContracts.forPool(Mainnet, ErgoId.create(SecondPoolNft)).liquidityPool.ergoTreeHex shouldBe secondPool
    DexContracts.forVault(Mainnet, ErgoId.create(SecondVaultNft)).feeVault.ergoTreeHex shouldBe secondVault
    DexContracts.forProvToken(Mainnet, ErgoId.create(SecondProvToken)).provisionGuard.ergoTreeHex shouldBe SecondGuardTree
    DexContracts.forPool(Mainnet, ErgoId.create(LitPoolNft)).liquidityPool.ergoTreeHex shouldBe litPool
  }

  it should "refuse an id no verified deployment holds, rather than lend it the canonical contracts" in {
    an[IllegalStateException] should be thrownBy DexContracts.forPool(Mainnet, ErgoId.create("22" * 32))
    an[IllegalStateException] should be thrownBy DexContracts.forProvToken(Mainnet, ErgoId.create("22" * 32))
  }
}
