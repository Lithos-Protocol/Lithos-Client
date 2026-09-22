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
   * ===The priority fee===
   *
   * A lender names ONE number: the ERG added to their collateral box above the floor. The contract
   * splits it — a fifth to whichever miner spends the box, the rest into the pool's holding box —
   * but that split is the protocol's business, not the lender's. Everything a lender sets, is
   * quoted, or is charged is denominated in this total.
   *
   * The helpers below are the only place the split is computed. Anything building or pricing a Join
   * uses them; [[finderFee]] above is the inverse, for reading what a box on chain already offers.
   */

  /** The least a priority fee may be, so the finder's fifth is worth more than the dust it costs. */
  val MinPriorityFee: Long = 1000000L

  /** The finder's share of a priority fee. The remainder is added to the pool. */
  def finderShare(priorityFee: Long): Long = {
    require(priorityFee >= 0L, s"a priority fee is never negative; found $priorityFee")
    priorityFee / (1L + CollateralParams.POOL_MULTIPLE)
  }

  /** The fee channel a queue box posts for this priority fee. This is the box's R4. */
  def feeChannel(priorityFee: Long): Long =
    Math.addExact(CollateralParams.DUST_BUDGET, finderShare(priorityFee))

  /**
   * The value a queue box carries for this priority fee: the floor plus the whole fee.
   *
   * The enforcer's own floor is `BLOCK_REWARD - FEE_CAP + R4 + 4 * finderShare`, which this always
   * clears — a fee that is not a multiple of five leaves its remainder in the holding box rather
   * than short-changing the check.
   */
  def queuePrincipal(priorityFee: Long): Long = {
    require(priorityFee >= 0L, s"a priority fee is never negative; found $priorityFee")
    Math.addExact(CollateralParams.PRINCIPAL_FLOOR, priorityFee)
  }

  /**
   * The largest priority fee the coinbase alone repays, which is the whole spread a lender earns:
   * `BLOCK_REWARD - PRINCIPAL_FLOOR`, or 0.085 ERG.
   *
   * Not a contract rule — the enforcer accepts any non-negative fee. Past this a lender recovers
   * the difference only from the transaction fees of the block they are mined against.
   */
  val breakEvenPriorityFee: Long =
    CollateralParams.BLOCK_REWARD - CollateralParams.PRINCIPAL_FLOOR

  /** What the coinbase leaves the lender at this fee, before the mined block's own fees. Signed. */
  def netAtCoinbase(priorityFee: Long): Long =
    Math.subtractExact(CollateralParams.BLOCK_REWARD, queuePrincipal(priorityFee))

  /** The priority fee a box on chain is offering, inferred from the finder's share it names. */
  def priorityFeeOf(finderShare: Long): Long =
    Math.multiplyExact(finderShare, 1L + CollateralParams.POOL_MULTIPLE)

  /**
   * The bid named by a queue or collateral box's R4, read from its serialized register.
   *
   * `None` for an absent, malformed, or below-floor register. Every box the enforcer accepted has a
   * well-formed one, so a `None` here is a box that should not exist — readers reporting a
   * distribution count it as unreadable rather than throwing, since one anomalous box must not blank
   * an entire inventory.
   */
  def finderFeeOf(register: Option[String]): Option[Long] =
    register.flatMap(hex => scala.util.Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue).toOption)
      .collect { case channel: java.lang.Long => channel.longValue() }
      .filter(_ >= CollateralParams.DUST_BUDGET)
      .map(_ - CollateralParams.DUST_BUDGET)

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
