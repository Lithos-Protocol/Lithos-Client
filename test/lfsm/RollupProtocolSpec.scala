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

  /**
   * A lender names one number — the whole fee added above the floor — and the split is derived from
   * it. These two are inverses, so a fee that round-trips through the chain and back comes out the
   * same number the lender typed.
   */
  "A priority fee" should "split a fifth to the finder and recover from that share" in {
    RollupProtocol.finderShare(3500000L) shouldEqual 700000L
    RollupProtocol.finderShare(0L) shouldEqual 0L
    RollupProtocol.priorityFeeOf(700000L) shouldEqual 3500000L
    RollupProtocol.priorityFeeOf(RollupProtocol.finderShare(3500000L)) shouldEqual 3500000L
    an[IllegalArgumentException] should be thrownBy RollupProtocol.finderShare(-1L)
    an[ArithmeticException] should be thrownBy RollupProtocol.priorityFeeOf(Long.MaxValue)
  }

  it should "put the whole fee on the box and clear the enforcer's own floor" in {
    val fee = 3500000L
    RollupProtocol.queuePrincipal(fee) shouldEqual CollateralParams.PRINCIPAL_FLOOR + fee
    RollupProtocol.feeChannel(fee) shouldEqual CollateralParams.DUST_BUDGET + 700000L
    withClue("the enforcer asks for floor + R4 + 4x the finder's share: ") {
      RollupProtocol.queuePrincipal(fee) should be >=
        (CollateralParams.BLOCK_REWARD - CollateralParams.FEE_CAP + RollupProtocol.feeChannel(fee) +
          RollupProtocol.finderShare(fee) * CollateralParams.POOL_MULTIPLE)
    }
  }

  /**
   * A fee that is not a multiple of five cannot pay the finder a whole fifth, so the remainder stays
   * with the pool. The box must still clear the enforcer's floor, which is what this pins.
   */
  it should "leave an indivisible remainder with the pool rather than short the enforcer" in {
    val fee = 1000003L
    RollupProtocol.finderShare(fee) shouldEqual 200000L
    RollupProtocol.queuePrincipal(fee) shouldEqual CollateralParams.PRINCIPAL_FLOOR + fee
    RollupProtocol.queuePrincipal(fee) should be >
      (CollateralParams.BLOCK_REWARD - CollateralParams.FEE_CAP + RollupProtocol.feeChannel(fee) +
        RollupProtocol.finderShare(fee) * CollateralParams.POOL_MULTIPLE)
  }

  it should "break even where the coinbase exactly repays the principal" in {
    RollupProtocol.breakEvenPriorityFee shouldEqual 85000000L
    RollupProtocol.netAtCoinbase(RollupProtocol.breakEvenPriorityFee) shouldEqual 0L
    RollupProtocol.netAtCoinbase(0L) shouldEqual
      CollateralParams.BLOCK_REWARD - CollateralParams.PRINCIPAL_FLOOR
    RollupProtocol.netAtCoinbase(RollupProtocol.breakEvenPriorityFee + 1L) should be < 0L
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
