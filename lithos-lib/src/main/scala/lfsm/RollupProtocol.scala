package lfsm

import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.Token

/**
 * The arithmetic and token layout the rollup and collateral contracts enforce, mirrored once so
 * builders, synchronization and tests all price the same transaction the same way.
 *
 * Every operation here throws on a value no contract would have accepted. Callers reducing chain
 * data are expected to catch that and fault the rollup; callers building a transaction are expected
 * to let it abort the build rather than sign something that will be rejected.
 */
object RollupProtocol {

  /** Token index 0 of every rollup box, minted from the collateral box it was created out of. */
  val NFTIndex: Int = 0

  /** Token index 1, present only while the rollup still holds LIT to distribute. */
  val LITIndex: Int = 1

  /**
   * The refundable bond a submission of this score posts. Holding collects it, Evaluation slashes
   * it to a prover, Payout refunds it, all from this one formula.
   */
  def bondForScore(score: Long): Long = {
    require(score > 0L, s"a NISP's claimed score is positive; found $score")
    val proportional = score / LFSMHelpers.BOND_DIVISOR
    if (proportional > LFSMHelpers.MIN_ENTRY_BOND) proportional else LFSMHelpers.MIN_ENTRY_BOND
  }

  /** The bond ledger after admitting one submission. Overflow means the transaction is unsignable. */
  def collectBond(totalBond: Long, entryBond: Long): Long = {
    require(totalBond >= 0L, s"a bond ledger is never negative; found $totalBond")
    Math.addExact(totalBond, entryBond)
  }

  /** The bond ledger after a slash or a refund. It may reach zero but never passes it. */
  def releaseBond(totalBond: Long, released: Long): Long = {
    val next = Math.subtractExact(totalBond, released)
    require(next >= 0L, s"releasing $released from a $totalBond ledger would leave it insolvent")
    next
  }

  /**
   * What a payout box may hand out pro rata: the box less the deposits it still owes back. Bonds
   * are refunded whole to the miner who posted them, so they never enter a reward share.
   */
  def distributableReward(boxValue: Long, totalBond: Long): Long = {
    val reward = Math.subtractExact(boxValue, totalBond)
    require(reward >= 0L, s"a box holding $boxValue cannot owe $totalBond in bonds")
    reward
  }

  /**
   * What the genesis transaction's finder may keep out of a collateral box's fee channel. Zero for
   * a box created at the dust budget, which is every box this client creates.
   */
  def finderFee(feeValue: Long): Long = {
    val fee = Math.subtractExact(feeValue, CollateralParams.DUST_BUDGET)
    require(fee >= 0L, s"a collateral fee channel is at least the dust budget; found $feeValue")
    fee
  }

  /**
   * The premium a bidding lender must add to the holding box on top of the finder's own fee. The
   * enforcer demands both at Join, so a box offering `f` to the finder carries `5f` in total.
   */
  def poolBonus(finderFee: Long): Long = {
    require(finderFee >= 0L, s"a finder fee is never negative; found $finderFee")
    Math.multiplyExact(finderFee, CollateralParams.POOL_MULTIPLE)
  }

  /** The rollup's own NFT. Its id is the box id of the collateral box the rollup was created from. */
  def rollupNFT(tokens: Seq[Token]): Token = {
    val nft = tokens.lift(NFTIndex).getOrElse(
      throw new IllegalArgumentException("a rollup box carries its NFT at token index 0"))
    require(nft.amount == 1L, s"a rollup NFT is a single token; found ${nft.amount} of ${nft.id}")
    nft
  }

  /** Whether these tokens are a rollup created from this collateral box, and nothing else. */
  def carriesNFT(tokens: Seq[Token], collateralBoxId: ErgoId): Boolean =
    tokens.lift(NFTIndex).exists(t => t.id == collateralBoxId && t.amount == 1L)

  /** The LIT still held for distribution. Absent once a payout has handed out the last of it. */
  def litToken(tokens: Seq[Token]): Option[Token] = tokens.lift(LITIndex).filter(_.amount > 0L)
}
