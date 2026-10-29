package configs

import lfsm.LFSMHelpers
import lithosdex.LDHelpers
import org.ergoplatform.appkit.NetworkType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object VerifiedRegistrySpec {

  def resourceText(path: String): String = {
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    try new String(stream.readAllBytes(), "UTF-8") finally stream.close()
  }

  def shipped(network: NetworkType): Either[Seq[String], VerifiedRegistry] =
    VerifiedRegistry.parse(network, resourceText(VerifiedRegistry.TokensResource),
      resourceText(VerifiedRegistry.PoolsResource))

  /** The registry the mainnet client ships, for specs that need a real one. */
  lazy val shippedMainnet: VerifiedRegistry =
    shipped(NetworkType.MAINNET).fold(p => throw new IllegalStateException(p.mkString("; ")), identity)
}

/**
 * The verified token and pool lists. Every entry is a shipped constant that marks a token or pool safe for
 * every miner on the release, so the parser refuses anything malformed, and a list that does not parse
 * marks nothing but LIT and ERG:LIT.
 */
class VerifiedRegistrySpec extends AnyFlatSpec with Matchers {
  import VerifiedRegistrySpec._

  private val Main = NetworkType.MAINNET
  private val tokenA = "a1" * 32
  private val tokenB = "b2" * 32
  private val poolNft = "c3" * 32

  private def listJson(entries: String*): String = s"""{"mainnet": [${entries.mkString(",")}], "testnet": []}"""
  private def token(id: String, extra: String = ""): String =
    s"""{"tokenId": "$id", "name": "Token", "ticker": "TKN", "decimals": 2$extra}"""

  private def problems(tokens: String, pools: String = listJson()): Seq[String] =
    VerifiedRegistry.parse(Main, tokens, pools).left.getOrElse(Seq.empty)

  "The shipped lists" should "parse with no problems on both networks" in {
    Seq(NetworkType.MAINNET, NetworkType.TESTNET).foreach { network =>
      withClue(s"$network: ") {
        shipped(network).left.getOrElse(Seq.empty) shouldBe empty
      }
    }
  }

  it should "mark LIT and ERG:LIT verified from the protocol constants" in {
    Seq(NetworkType.MAINNET, NetworkType.TESTNET).foreach { network =>
      val registry = shipped(network).toOption.get
      val litId = LFSMHelpers.getLitId(network).toString
      registry.token(litId).map(t => (t.ticker, t.decimals)) shouldBe Some(("LIT", 9))
      val pool = registry.pool(LDHelpers.getPoolNFT(network).toString).get
      pool.kind shouldBe VerifiedPool.Kind.N2T
      pool.tokens shouldBe Seq(litId)
    }
  }

  it should "be what forNetwork serves" in {
    VerifiedRegistry.forNetwork(Main).tokens shouldBe shippedMainnet.tokens
    VerifiedRegistry.forNetwork(Main).pools shouldBe shippedMainnet.pools
  }

  "A well-formed list" should "add its tokens and pools beside the built-in ones" in {
    val registry = VerifiedRegistry.parse(Main, listJson(token(tokenA), token(tokenB)),
      listJson(s"""{"poolNft": "$poolNft", "kind": "t2t", "tokens": ["$tokenA", "$tokenB"]}""")).toOption.get
    registry.token(tokenA.toUpperCase).map(_.name) shouldBe Some("Token")
    registry.pool(poolNft).map(_.kind) shouldBe Some(VerifiedPool.Kind.T2T)
    registry.tokens.size shouldBe 3
  }

  "A token entry" should "be refused for an id that is not 64 lowercase hex characters" in {
    problems(listJson(token(tokenA.toUpperCase))).mkString should include("not 64 lowercase hex")
    problems(listJson(token("abc"))).mkString should include("not 64 lowercase hex")
  }

  it should "be refused when it repeats a token, the built-in LIT included" in {
    problems(listJson(token(tokenA), token(tokenA))).mkString should include(s"token $tokenA is listed more than once")
    val lit = LFSMHelpers.getLitId(Main).toString
    problems(listJson(token(lit))).mkString should include(s"token $lit is listed more than once")
  }

  it should "be refused for a field the parser does not know, so a misspelt one is not silently dropped" in {
    problems(listJson(token(tokenA, extra = """, "decimal": 2"""))).mkString should include("unknown fields decimal")
  }

  it should "be refused for decimals out of range, a blank name or a ticker with a space" in {
    problems(listJson(token(tokenA).replace("\"decimals\": 2", "\"decimals\": 19"))).mkString should include("decimals")
    problems(listJson(token(tokenA).replace("\"Token\"", "\" \""))).mkString should include("name")
    problems(listJson(token(tokenA).replace("\"TKN\"", "\"T KN\""))).mkString should include("ticker")
  }

  "A pool entry" should "be refused when its token count does not match its kind" in {
    problems(listJson(token(tokenA)),
      listJson(s"""{"poolNft": "$poolNft", "kind": "n2t", "tokens": ["$tokenA", "$tokenA"]}""")).mkString should
      include("must name 1 token")
  }

  it should "be refused when it names a token that is not verified" in {
    problems(listJson(),
      listJson(s"""{"poolNft": "$poolNft", "kind": "n2t", "tokens": ["$tokenA"]}""")).mkString should
      include("not a verified token")
  }

  it should "be refused for an unknown kind or a repeated pool" in {
    problems(listJson(token(tokenA)),
      listJson(s"""{"poolNft": "$poolNft", "kind": "x2y", "tokens": ["$tokenA"]}""")).mkString should include("kind")
    val entry = s"""{"poolNft": "$poolNft", "kind": "n2t", "tokens": ["$tokenA"]}"""
    problems(listJson(token(tokenA)), listJson(entry, entry)).mkString should include("listed more than once")
  }

  "A file" should "be refused when it is not JSON or has no list for the network" in {
    problems("{").mkString should include("is not JSON")
    problems("""{"testnet": []}""").mkString should include("no mainnet array")
    problems("[]").mkString should include("not a JSON object")
  }

  it should "report every problem at once rather than the first" in {
    problems(listJson(token("abc"), token(tokenA, extra = """, "x": 1"""))).size should be >= 2
  }
}
