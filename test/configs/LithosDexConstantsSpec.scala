package configs

import lithosdex.LDHelpers
import lithosdex.states.LDLiquidityState
import org.ergoplatform.appkit.NetworkType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

/**
 * The LithosDex constants, against the two places they are actually declared.
 *
 * The same failure `ConfigDefaultsSpec` exists for, one layer down. `LDHelpers`' own header says the
 * `.ergo` sources are the authority and its "In-script constants" block is a copy — but a copy kept
 * in step by a comment is not kept in step, and this pair drifted apart before. A mismatch here does
 * not fail a build and does not fail a contract property either, because the properties build their
 * fixtures from `LDHelpers` and would simply agree with themselves. It surfaces on chain, as
 * transactions this client believes are valid and the pool rejects.
 *
 * `LITHOS-CLIENT-RULES.MD` §3: a defect in a shipped constant reaches every miner on the release at
 * once and reads like a protocol failure. That is why this is a test rather than a review item.
 *
 * The second half covers the launch supply, which is NOT a contract constant — no `.ergo` source
 * mentions it. It is a genesis mint decision that cannot be revised afterwards, since the whole
 * supply goes into the pool box and anything left in a wallet is a forgeable provision. Three
 * separate places modelled it independently and disagreed by seven orders of magnitude.
 */
class LithosDexConstantsSpec extends AnyFlatSpec with Matchers {

  /** Every `val CONST_X = …` a LithosDex source declares, as written. */
  private def declared(source: String): Map[String, String] = {
    val text = {
      val src = Source.fromResource(s"lithosdex/$source")
      try src.mkString finally src.close()
    }
    val pattern = """^\s*val\s+(CONST_\w+)\s*=\s*(.+?)\s*(?://.*)?$""".r
    text.linesIterator.flatMap {
      case pattern(name, value) => Some(name -> value.trim)
      case _ => None
    }.toMap
  }

  private lazy val pool = declared("LD_LiquidityPool.ergo")
  private lazy val vault = declared("LD_FeeVault.ergo")
  private lazy val provision = declared("LD_Provision.ergo")

  /** All three sources together. A constant declared in two of them is checked against both. */
  private lazy val everywhere: Seq[(String, Map[String, String])] =
    Seq("LD_LiquidityPool.ergo" -> pool, "LD_FeeVault.ergo" -> vault, "LD_Provision.ergo" -> provision)

  /** `10000000L`, `10000`, `0x7fffffffffffffffL` — the forms ErgoScript writes a whole number in. */
  private def asLong(literal: String): Long = {
    val bare = literal.stripSuffix("L")
    if (bare.startsWith("0x")) java.lang.Long.parseUnsignedLong(bare.drop(2), 16)
    else bare.toLong
  }

  private def check(name: String, expected: Long): Unit = {
    val found = everywhere.collect { case (file, consts) if consts.contains(name) => file -> consts(name) }
    withClue(s"$name is declared in no LithosDex source; LDHelpers claims it mirrors one: ") {
      found should not be empty
    }
    found.foreach { case (file, literal) =>
      withClue(s"$name in $file is '$literal', LDHelpers says $expected: ") {
        asLong(literal) shouldEqual expected
      }
    }
  }

  // ─── the mirrors ──────────────────────────────────────────────────────────

  "LDHelpers" should "mirror every whole-number constant the contracts declare" in {
    check("CONST_LOCKED_LP", LDHelpers.LOCKED_LP)
    check("CONST_FEE_DENOM", LDHelpers.FEE_DENOM)
    check("CONST_MIN_RENT", LDHelpers.MIN_RENT)
    check("CONST_MIN_SUPPLY", LDHelpers.MIN_SUPPLY)
    check("CONST_PROVISION_MIN", LDHelpers.PROVISION_MIN)
    check("CONST_VAULT_MIN", LDHelpers.VAULT_MIN)
    check("CONST_REFRESH_AGE", LDHelpers.REFRESH_AGE.toLong)
    check("CONST_HEIGHT_SLACK", LDHelpers.HEIGHT_SLACK.toLong)
  }

  it should "mirror the accumulator scale, which is written as a product rather than a literal" in {
    // 1e27 does not fit a Long, so both contracts build it from three BigInt factors. Checked by
    // recomputing the factors rather than by string comparison, so reformatting the line is allowed
    // and changing a nine to an eight is not.
    val factors = """(\d+)L\.toBigInt""".r
    Seq("LD_LiquidityPool.ergo" -> pool, "LD_FeeVault.ergo" -> vault).foreach { case (file, consts) =>
      withClue(s"$file declares no CONST_SCALE: ")(consts.keySet should contain("CONST_SCALE"))
      val product = factors.findAllMatchIn(consts("CONST_SCALE"))
        .map(m => BigInt(m.group(1)))
        .foldLeft(BigInt(1))(_ * _)
      withClue(s"CONST_SCALE in $file is '${consts("CONST_SCALE")}': ") {
        product shouldEqual LDHelpers.SCALE
      }
    }
    LDHelpers.SCALE shouldEqual BigInt(10).pow(27)
  }

  it should "declare no LithosDex constant this file does not check" in {
    // The check that keeps this test honest. A constant added to a contract and copied into
    // LDHelpers by hand is exactly the pair that drifts, and it would be invisible here otherwise.
    val checked = Set(
      "CONST_LOCKED_LP", "CONST_FEE_DENOM", "CONST_MIN_RENT", "CONST_MIN_SUPPLY",
      "CONST_PROVISION_MIN", "CONST_VAULT_MIN", "CONST_REFRESH_AGE", "CONST_HEIGHT_SLACK",
      "CONST_SCALE")
    val all = everywhere.flatMap(_._2.keySet).toSet
    withClue("add it to this spec and to LDHelpers' in-script block: ")(all.diff(checked) shouldBe empty)
  }

  // ─── the launch mint ──────────────────────────────────────────────────────

  "The provision token supply" should "be one number everything derives from" in {
    // One deposit takes one token, so this is the ceiling on live provisions for the life of the
    // pool — and it cannot be raised later, because the entire supply is minted into the pool box at
    // genesis. When these disagreed, a contract property tested a pool with a million tokens while
    // the sandbox would have minted ten trillion.
    LDHelpers.PROV_TOKEN_SUPPLY shouldEqual 1000000000000000L

    LDLiquidityState.genesis(reservesX = 10000000000L, reservesY = 10000L, NetworkType.MAINNET)
      .provTokensLeft shouldEqual LDHelpers.PROV_TOKEN_SUPPLY
  }

  it should "leave room for the locked LP remainder above the issued supply" in {
    // R4 holds CONST_LOCKED_LP - supply, so a genesis supply at or above the ceiling underflows the
    // register and there is no locked remainder for a redemption floor to sit under.
    LDHelpers.GENESIS_SUPPLY should be < LDHelpers.LOCKED_LP
    LDHelpers.GENESIS_SUPPLY should be > LDHelpers.MIN_SUPPLY
  }
}
