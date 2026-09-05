package lfsm

import lfsm.states.RollupInfoState
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.Colls
import work.lithos.mutations.Token

/**
 * The one place the rollup's register layout and its arithmetic are defined off-chain.
 *
 * Everything here mirrors a condition the contracts enforce, so a disagreement is a transaction
 * this client would sign and the chain would reject, or a chain transition it would fail to follow.
 */
class RollupProtocolSpec extends AnyFlatSpec with Matchers {

  private def collLong(values: Long*): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(values.toArray), scalaLongType)

  private val nft = ErgoId.create("aa" * 32)
  private val lit = ErgoId.create("bb" * 32)

  // ─── R7 ───────────────────────────────────────────────────────────────────

  "A rollup state" should "round-trip exactly three longs" in {
    val state = RollupInfoState.holding(120L, 100L, 7000000L)
    val decoded = RollupInfoState.fromErgoValue(state.ergoValue, LFSMPhase.HOLDING)
    decoded shouldEqual state
    decoded.periodStart shouldEqual 120L
    decoded.genesisBlockHeight shouldEqual 100L
    decoded.totalBond shouldEqual 7000000L
  }

  it should "reject a sequence that is not exactly three long" in {
    an[IllegalArgumentException] should be thrownBy
      RollupInfoState.fromValues(LFSMPhase.HOLDING, Seq(1L, 2L))
    an[IllegalArgumentException] should be thrownBy
      RollupInfoState.fromValues(LFSMPhase.HOLDING, Seq(1L, 2L, 3L, 4L))
  }

  it should "reject the register type the previous layout used" in {
    an[Exception] should be thrownBy
      RollupInfoState.fromErgoValue(ErgoValue.of(100L), LFSMPhase.HOLDING)
  }

  it should "reject a collection whose elements are not long" in {
    val bytes = ErgoValue.of(Colls.fromArray(Array[Byte](1, 2, 3)), scalaByteType)
    an[Exception] should be thrownBy RollupInfoState.fromErgoValue(bytes, LFSMPhase.HOLDING)
  }

  it should "decode a three-long collection built outside this codec" in {
    val decoded = RollupInfoState.fromErgoValue(collLong(5L, 6L, 7L), LFSMPhase.EVAL)
    decoded shouldEqual RollupInfoState.evaluation(5L, 6L, 7L)
  }

  /**
   * The reason the phase travels with the values: slot 0 is a height before payout and a reward
   * after it, and a plausible-looking wrong answer is worse than a failure.
   */
  it should "refuse to read a payout reward off a holding state" in {
    an[IllegalArgumentException] should be thrownBy RollupInfoState.holding(1L, 2L, 3L).totalErgReward
    an[IllegalArgumentException] should be thrownBy RollupInfoState.holding(1L, 2L, 3L).totalLitReward
  }

  it should "refuse to read a height off a payout state" in {
    an[IllegalArgumentException] should be thrownBy RollupInfoState.payout(1L, 2L, 3L).periodStart
    an[IllegalArgumentException] should be thrownBy RollupInfoState.payout(1L, 2L, 3L).genesisBlockHeight
  }

  it should "expose the bond ledger in every phase" in {
    RollupInfoState.holding(1L, 2L, 42L).totalBond shouldEqual 42L
    RollupInfoState.evaluation(1L, 2L, 42L).totalBond shouldEqual 42L
    RollupInfoState.payout(1L, 2L, 42L).totalBond shouldEqual 42L
  }

  // ─── bonds ────────────────────────────────────────────────────────────────

  "A submission bond" should "be the floor for any score the floor dominates" in {
    RollupProtocol.bondForScore(1L) shouldEqual LFSMHelpers.MIN_ENTRY_BOND
    RollupProtocol.bondForScore(LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR) shouldEqual
      LFSMHelpers.MIN_ENTRY_BOND
  }

  it should "become proportional one score unit past the floor" in {
    val atFloor = LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR
    RollupProtocol.bondForScore(atFloor + LFSMHelpers.BOND_DIVISOR) shouldEqual
      LFSMHelpers.MIN_ENTRY_BOND + 1L
  }

  it should "use the same truncating division the contracts do" in {
    RollupProtocol.bondForScore(1000000000L) shouldEqual 40000000L
    RollupProtocol.bondForScore(1000000009L) shouldEqual 40000000L
  }

  it should "reject a score no valid NISP could carry" in {
    an[IllegalArgumentException] should be thrownBy RollupProtocol.bondForScore(0L)
    an[IllegalArgumentException] should be thrownBy RollupProtocol.bondForScore(-1L)
  }

  "A bond ledger" should "refuse to overflow rather than wrap" in {
    an[ArithmeticException] should be thrownBy
      RollupProtocol.collectBond(Long.MaxValue, LFSMHelpers.MIN_ENTRY_BOND)
  }

  it should "refuse to go insolvent" in {
    RollupProtocol.releaseBond(5L, 5L) shouldEqual 0L
    an[IllegalArgumentException] should be thrownBy RollupProtocol.releaseBond(5L, 6L)
  }

  "The distributable reward" should "be the box less the bonds it owes back" in {
    RollupProtocol.distributableReward(3000000000L, 4000000L) shouldEqual 2996000000L
    an[IllegalArgumentException] should be thrownBy RollupProtocol.distributableReward(1L, 2L)
  }

  // ─── the collateral bid ───────────────────────────────────────────────────

  "A finder fee" should "be zero for a box created at the dust budget" in {
    RollupProtocol.finderFee(CollateralParams.DUST_BUDGET) shouldEqual 0L
  }

  it should "be whatever the fee channel exceeds the dust budget by" in {
    RollupProtocol.finderFee(CollateralParams.DUST_BUDGET + 700000L) shouldEqual 700000L
  }

  it should "reject a fee channel below the dust budget" in {
    an[IllegalArgumentException] should be thrownBy
      RollupProtocol.finderFee(CollateralParams.DUST_BUDGET - 1L)
  }

  it should "reject a hostile R4 rather than wrapping" in {
    an[ArithmeticException] should be thrownBy RollupProtocol.finderFee(Long.MinValue)
  }

  "The pool premium" should "be four times the finder fee" in {
    RollupProtocol.poolBonus(700000L) shouldEqual 2800000L
    RollupProtocol.poolBonus(0L) shouldEqual 0L
    an[ArithmeticException] should be thrownBy RollupProtocol.poolBonus(Long.MaxValue)
  }

  // ─── tokens ───────────────────────────────────────────────────────────────

  "Token access" should "read the NFT at index 0 and LIT at index 1" in {
    val tokens = Seq(Token(nft, 1L), Token(lit, 500L))
    RollupProtocol.rollupNFT(tokens).id shouldEqual nft
    RollupProtocol.litToken(tokens).map(_.amount) shouldEqual Some(500L)
    RollupProtocol.carriesNFT(tokens, nft) shouldBe true
  }

  it should "reject a missing NFT and a multiple one" in {
    an[IllegalArgumentException] should be thrownBy RollupProtocol.rollupNFT(Seq.empty[Token])
    an[IllegalArgumentException] should be thrownBy RollupProtocol.rollupNFT(Seq(Token(nft, 2L)))
    RollupProtocol.carriesNFT(Seq(Token(nft, 2L)), nft) shouldBe false
    RollupProtocol.carriesNFT(Seq(Token(lit, 1L), Token(nft, 1L)), nft) shouldBe false
  }

  /** LIT at index 0 would be read as the NFT, which is what makes `tokens.head` unusable now. */
  it should "not see LIT that sits where the NFT belongs" in {
    RollupProtocol.litToken(Seq(Token(lit, 500L))) shouldBe empty
  }

  it should "report no LIT for a box that carries only its NFT" in {
    RollupProtocol.litToken(Seq(Token(nft, 1L))) shouldBe empty
  }
}
