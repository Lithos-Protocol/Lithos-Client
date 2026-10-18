package lfsm

import com.google.gson.JsonParser
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object DeploymentSpec {

  /** A deployment no network holds, one distinct id per role. */
  def fakeIds(network: NetworkType = NetworkType.TESTNET): DeploymentIds = DeploymentIds(
    lit = ErgoId.create("d1" * 32),
    emissionNft = ErgoId.create("d2" * 32),
    emConfigNft = ErgoId.create("d3" * 32),
    queueToken = ErgoId.create("d4" * 32),
    collatToken = ErgoId.create("d5" * 32),
    fpToken = ErgoId.create("d6" * 32),
    mdToken = ErgoId.create("d7" * 32),
    voteToken = ErgoId.create("d8" * 32),
    mdGenesisId = "d9" * 32,
    mdGenesisHeight = 42,
    fpControlAddress = DeploymentIds.constants(network).fpControlAddress)

  def descriptor(network: NetworkType = NetworkType.TESTNET): DeploymentDescriptor =
    DeploymentDescriptor(network, fakeIds(network),
      emissionBoxId = Some("e1" * 32), configBoxId = Some("e2" * 32), fpControlBoxId = Some("e3" * 32),
      deployerAddress = Some(DeploymentIds.constants(network).fpControlAddress.toString), height = Some(40))
}

/**
 * The deployment override as a value: the descriptor round trip, the refusals that keep a bad file
 * from being installed, and the getters answering from the override only for its own network.
 */
class DeploymentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  import DeploymentSpec._

  override def afterEach(): Unit = Deployment.clear()

  private def withKey(json: String, key: String, value: String): String = {
    val o = new JsonParser().parse(json).getAsJsonObject
    o.addProperty(key, value)
    o.toString
  }

  private def without(json: String, key: String): String = {
    val o = new JsonParser().parse(json).getAsJsonObject
    o.remove(key)
    o.toString
  }

  // ─── the constants ────────────────────────────────────────────────────────

  "With no override, the getters" should "answer the per-network constants they always did" in {
    LFSMHelpers.getLitId(NetworkType.MAINNET) shouldEqual LFSMHelpers.LIT_ID_MAINNET
    LFSMHelpers.getLitId(NetworkType.TESTNET) shouldEqual LFSMHelpers.LIT_ID_TESTNET
    LFSMHelpers.getEmissionNft(NetworkType.MAINNET) shouldEqual LFSMHelpers.EMISSION_NFT_MAINNET
    LFSMHelpers.getEmConfigNft(NetworkType.TESTNET) shouldEqual LFSMHelpers.EMCONFIG_NFT_TESTNET
    LFSMHelpers.getQueueToken(NetworkType.MAINNET) shouldEqual LFSMHelpers.QUEUE_TOKEN_MAINNET
    LFSMHelpers.getCollatToken(NetworkType.TESTNET) shouldEqual LFSMHelpers.COLLAT_TOKEN_TESTNET
    LFSMHelpers.getFPToken(NetworkType.MAINNET) shouldEqual LFSMHelpers.FP_TOKEN_MAINNET
    LFSMHelpers.getMDToken(NetworkType.TESTNET) shouldEqual LFSMHelpers.MD_TOKEN_TESTNET
    LFSMHelpers.getVoteToken(NetworkType.MAINNET) shouldEqual LFSMHelpers.VOTE_TOKEN_MAINNET
    LFSMHelpers.getMDGenesisId(NetworkType.MAINNET) shouldEqual LFSMHelpers.MD_GENESIS_ID_MAINNET
    LFSMHelpers.getMDGenesisHeight(NetworkType.TESTNET) shouldEqual LFSMHelpers.MD_GENESIS_HEIGHT_TESTNET
    LFSMHelpers.getFPControlAddress(NetworkType.TESTNET).toString shouldEqual LFSMHelpers.FP_CONTROL_TESTNET.toString
    LFSMHelpers.getFPControlAddress(NetworkType.MAINNET).getNetworkType shouldEqual NetworkType.MAINNET
  }

  // ─── the override ─────────────────────────────────────────────────────────

  "An installed override" should "answer every getter for its own network" in {
    val ids = fakeIds()
    Deployment.install(NetworkType.TESTNET, ids)
    val n = NetworkType.TESTNET
    LFSMHelpers.getLitId(n) shouldEqual ids.lit
    LFSMHelpers.getEmissionNft(n) shouldEqual ids.emissionNft
    LFSMHelpers.getEmConfigNft(n) shouldEqual ids.emConfigNft
    LFSMHelpers.getQueueToken(n) shouldEqual ids.queueToken
    LFSMHelpers.getCollatToken(n) shouldEqual ids.collatToken
    LFSMHelpers.getFPToken(n) shouldEqual ids.fpToken
    LFSMHelpers.getMDToken(n) shouldEqual ids.mdToken
    LFSMHelpers.getVoteToken(n) shouldEqual ids.voteToken
    LFSMHelpers.getMDGenesisId(n) shouldEqual ids.mdGenesisId
    LFSMHelpers.getMDGenesisHeight(n) shouldEqual ids.mdGenesisHeight
  }

  it should "leave the other network on its constants" in {
    Deployment.install(NetworkType.TESTNET, fakeIds())
    LFSMHelpers.getLitId(NetworkType.MAINNET) shouldEqual LFSMHelpers.LIT_ID_MAINNET
    LFSMHelpers.getCollatToken(NetworkType.MAINNET) shouldEqual LFSMHelpers.COLLAT_TOKEN_MAINNET
  }

  it should "refuse to be replaced by a different set while the JVM runs" in {
    Deployment.install(NetworkType.TESTNET, fakeIds())
    noException should be thrownBy Deployment.install(NetworkType.TESTNET, fakeIds())
    an[IllegalStateException] should be thrownBy
      Deployment.install(NetworkType.TESTNET, fakeIds().copy(lit = ErgoId.create("aa" * 32)))
    an[IllegalStateException] should be thrownBy Deployment.install(NetworkType.MAINNET, fakeIds(NetworkType.MAINNET))
  }

  // ─── the descriptor ───────────────────────────────────────────────────────

  "A descriptor" should "survive a round trip through its JSON" in {
    val d = descriptor()
    val back = DeploymentDescriptor.parse(d.toJson)
    back.isRight shouldBe true
    val r = back.toOption.get
    r.network shouldEqual d.network
    r.ids.fingerprint shouldEqual d.ids.fingerprint
    r.emissionBoxId shouldEqual d.emissionBoxId
    r.configBoxId shouldEqual d.configBoxId
    r.fpControlBoxId shouldEqual d.fpControlBoxId
    r.deployerAddress shouldEqual d.deployerAddress
    r.height shouldEqual d.height
  }

  it should "carry the keys other tools read from the descriptor" in {
    val o = new JsonParser().parse(descriptor().toJson).getAsJsonObject
    Seq("collatToken", "litId", "emissionBoxId", "emissionNft", "network")
      .foreach(k => withClue(s"$k: ")(o.has(k) shouldBe true))
    o.get("collatToken").getAsString shouldEqual "d5" * 32
  }

  it should "load without the informational box ids" in {
    val bare = Seq("emissionBoxId", "configBoxId", "fpControlBoxId", "deployerAddress", "height")
      .foldLeft(descriptor().toJson)(without)
    DeploymentDescriptor.parse(bare).isRight shouldBe true
  }

  // ─── refusals ─────────────────────────────────────────────────────────────

  "A descriptor" should "be refused when an id is not 64 hex characters" in {
    val short = withKey(descriptor().toJson, "collatToken", "d5" * 31)
    DeploymentDescriptor.parse(short).left.toOption.get.exists(_.contains("collatToken")) shouldBe true
    val notHex = withKey(descriptor().toJson, "litId", "zz" * 32)
    DeploymentDescriptor.parse(notHex).left.toOption.get.exists(_.contains("litId")) shouldBe true
  }

  it should "list every problem at once" in {
    val bad = withKey(withKey(descriptor().toJson, "litId", "x"), "mdToken", "y")
    val problems = DeploymentDescriptor.parse(bad).left.toOption.get
    problems.exists(_.contains("litId")) shouldBe true
    problems.exists(_.contains("mdToken")) shouldBe true
  }

  it should "be refused when a key is missing" in {
    DeploymentDescriptor.parse(without(descriptor().toJson, "voteToken")).isLeft shouldBe true
    DeploymentDescriptor.parse(without(descriptor().toJson, "mdGenesisHeight")).isLeft shouldBe true
    DeploymentDescriptor.parse(without(descriptor().toJson, "network")).isLeft shouldBe true
  }

  it should "be refused when two roles name one token" in {
    val dup = withKey(descriptor().toJson, "queueToken", "d5" * 32)
    DeploymentDescriptor.parse(dup).left.toOption.get.exists(_.contains("same token")) shouldBe true
  }

  it should "be refused when the FP control address belongs to the other network" in {
    val wrong = withKey(descriptor().toJson, "fpControlAddress",
      DeploymentIds.constants(NetworkType.MAINNET).fpControlAddress.toString)
    DeploymentDescriptor.parse(wrong).left.toOption.get.exists(_.contains("fpControlAddress")) shouldBe true
  }

  it should "be refused when it is not JSON" in {
    DeploymentDescriptor.parse("{ not json").isLeft shouldBe true
    DeploymentDescriptor.parse("[1, 2]").isLeft shouldBe true
  }
}
