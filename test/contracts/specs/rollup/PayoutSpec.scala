package contracts.specs.rollup

import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.math.BigInteger

/**
 * Payout.ergo — one property per named condition in the contract.
 *
 * Path 1 (`currentMiners == 0`): the drain. A rollup that received no submissions owes nobody, so the
 * box may be swept — by any transaction that carries no miner-fee output and burns the rollup NFT.
 * Path 2 (rewards paid leave more than the min change value behind): the box recreates itself with
 * the paid miners removed from the tree.
 * Path 3 (otherwise): the remaining miners are paid and the box is consumed.
 *
 * The box holds two different pots. R7 slot 0 is the block reward, which is divided by score; the
 * rest of its value is the submitters' refundable bonds, which are returned to their own owners. Each
 * miner is therefore paid a pro-rata share plus their own deposit back.
 */
class PayoutSpec extends AnyPropSpec with RollupSpecBase {

  /** Belongs to nothing in the protocol; a real token id would collide with a proposition token. */
  private val rollupNft: ErgoId = ErgoId.create("33" * 32)
  private def nft: Token = Token(rollupNft, 1L)

  private case class Miner(prover: ErgoProver, score: Long) {
    def contract: Contract = contractOf(prover)
    def key: Array[Byte] = contract.hashedPropBytes
    def nisp: Array[Byte] = nispBytes(score, 8000)
    def bond: Long = bondFor(score)
  }

  private def miners(ctx: BlockchainContext): Seq[Miner] = Seq(
    Miner(proverWith(ctx, BigInteger.valueOf(11L)), 3000L),
    Miner(proverWith(ctx, BigInteger.valueOf(22L)), 5000L),
    Miner(proverWith(ctx, BigInteger.valueOf(33L)), 2000L)
  )

  private def bondsOf(ms: Seq[Miner]): Long = ms.map(_.bond).sum

  private def reward(score: Long, totalScore: Long, totalReward: Long): Long =
    (BigInt(totalReward) * BigInt(score) / BigInt(totalScore)).toLong

  /** What one miner is owed: their share of the reward, plus the bond they posted. */
  private def owed(m: Miner, totalScore: Long): Long = reward(m.score, totalScore, boxValue) + m.bond

  private case class Pot(in: InputUTXO,
                         box: UTXO,
                         outTree: ErgoValue[_],
                         totalScore: Long,
                         bondsHeld: Long,
                         value: Long,
                         litSnapshot: Long,
                         litHeld: Long,
                         tokens: Seq[Token],
                         minerCount: Int)

  /**
   * Payout box holding `inTreeEntries`, with a lookup+removal proof covering `toPay`. Both proofs are
   * generated against the same input digest, which is what the contract verifies.
   */
  private def payout(ctx: BlockchainContext,
                     all: Seq[Miner],
                     toPay: Seq[Miner],
                     litSnapshot: Long = 0L,
                     litHeld: Long = -1L,
                     value: Long = -1L,
                     inTreeEntries: Option[Seq[Miner]] = None,
                     entrySize: Int = 40): Pot = {
    val entries = inTreeEntries.getOrElse(all)
    val totalScore = all.map(_.score).sum
    val bondsHeld = bondsOf(entries)
    val heldLit = if (litHeld < 0L) litSnapshot else litHeld
    val tree = treeWith(entries.map(m => m.key ->
      (nisp.NispCommitment.fromPayload(m.nisp).bytes ++ Array[Byte](0)).take(entrySize)))
    val inTree = tree.ergoValue

    val keys = toPay.map(_.key)
    val lookup = tree.lookUp(keys: _*)
    val removal = tree.delete(keys: _*)
    val outTree = tree.ergoValue

    val tokens = if (heldLit > 0L) Seq(nft, Token(fpTokenId, heldLit)) else Seq(nft)
    val boxVal = if (value < 0L) boxValue + bondsHeld else value
    val box = UTXO(payoutContract(ctx), boxVal, tokens, Seq(
      inTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger),
      stateReg(boxValue, litSnapshot, bondsHeld)
    ))

    val in = box.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        ContextVar.of(0.toByte, ErgoValue.of(
          Colls.fromArray(keys.map(k => Colls.fromArray(k)).toArray), ErgoType.collType(scalaByteType))),
        ContextVar.of(1.toByte, lookup.proof.ergoValue),
        ContextVar.of(2.toByte, removal.proof.ergoValue)
      )
    Pot(in, box, outTree, totalScore, bondsHeld, boxVal, litSnapshot, heldLit, tokens, all.size)
  }

  private def minerBox(m: Miner, value: Long, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(m.contract, value, tokens)

  // ─── path 2: partial payout, box recreated ────────────────────────────────

  private case class Partial(prover: ErgoProver,
                             pot: Pot,
                             successor: UTXO,
                             payeeBox: UTXO,
                             amount: Long,
                             all: Seq[Miner],
                             paid: Miner)

  /** Pays the first miner only, leaving a large remainder so the partial path is taken. */
  private def partial(ctx: BlockchainContext, litSnapshot: Long = 0L, litHeld: Long = -1L,
                       entrySize: Int = 40): Partial = {
    val all = miners(ctx)
    val paid = all.head
    val pot = payout(ctx, all, Seq(paid), litSnapshot, litHeld, entrySize = entrySize)
    val amount = owed(paid, pot.totalScore)
    val litShare = if (pot.litSnapshot > 0L) reward(paid.score, pot.totalScore, pot.litSnapshot) else 0L
    val nextLit = pot.litHeld - litShare
    val nextTokens = if (nextLit > 0L) Seq(nft, Token(fpTokenId, nextLit)) else Seq(nft)

    val successor = UTXO(payoutContract(ctx), pot.value - amount, nextTokens, Seq(
      pot.outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(pot.totalScore).bigInteger),
      stateReg(boxValue, pot.litSnapshot, pot.bondsHeld - paid.bond)
    ))
    val payeeTokens = if (litShare > 0L) Seq(Token(fpTokenId, litShare)) else Seq.empty[Token]
    Partial(paid.prover, pot, successor, minerBox(paid, amount, payeeTokens), amount, all, paid)
  }

  private def partialTx(ctx: BlockchainContext, p: Partial,
                        successor: UTXO = null, payee: UTXO = null,
                        inputs: Seq[InputUTXO] = null): UnsignedTransaction =
    build(ctx,
      if (inputs == null) Seq(p.pot.in, fundingInput(ctx, p.prover)) else inputs,
      Seq(if (successor == null) p.successor else successor,
        if (payee == null) p.payeeBox else payee),
      p.prover.getAddress)

  property("partial: pays a subset and recreates the box with the payee removed") {
    withCtx { ctx =>
      val p = partial(ctx)
      accepts(p.prover, partialTx(ctx, p))
    }
  }

  property("compact entries: rejects short and oversized leaves before reading their scores") {
    withCtx { ctx =>
      Seq(7, 39, 41).foreach { size =>
        val p = partial(ctx, entrySize = size)
        val result = scala.util.Try(p.prover.sign(partialTx(ctx, p)))
        result.failed.get.getMessage should include("Script reduced to false")
      }
    }
  }

  property("partial: rejects a payout of the wrong amount (minersRewarded)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p, payee = minerBox(p.paid, p.amount + Parameters.OneErg)))
    }
  }

  property("partial: rejects a payout to the wrong recipient (minersRewarded)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p, payee = minerBox(p.all(1), p.amount)))
    }
  }

  property("partial: rejects a successor tree that does not match the removal (treeUpdated)") {
    withCtx { ctx =>
      val p = partial(ctx)
      val other = commitmentTree(Seq(scorex.crypto.hash.Blake2b256.hash("nope") -> nispBytes(1L, 16)))
      rejects(p.prover, partialTx(ctx, p, successor = p.successor.withReg(0, other.ergoValue)))
    }
  }

  property("partial: rejects a successor that keeps the paid-out value (usedReward)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p, successor = p.successor.addValue(Parameters.OneErg)))
    }
  }

  property("partial: rejects a successor that is not the payout contract (validUTXO)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p, successor = p.successor.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("partial: rejects a change to the total reward (sameTotal)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejectsAtSigning(p.prover, partialTx(ctx, p, successor = p.successor.withReg(3,
        stateReg(boxValue + 1L, p.pot.litSnapshot, p.pot.bondsHeld - p.paid.bond))))
    }
  }

  property("partial: rejects a change to the total score (sameScore)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p,
        successor = p.successor.withReg(2, ErgoValue.of(BigInt(1L).bigInteger))))
    }
  }

  property("partial: rejects a change to the miner count (sameMiners)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p, successor = p.successor.withReg(1, ErgoValue.of(99))))
    }
  }

  property("partial: rejects when the payout box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejects(p.prover, partialTx(ctx, p,
        inputs = Seq(fundingInput(ctx, p.prover), p.pot.in)))
    }
  }

  property("partial: rejects dropping the rollup NFT from the successor") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejectsAtSigning(p.prover, build(ctx, Seq(p.pot.in, fundingInput(ctx, p.prover)),
        Seq(UTXO(p.successor.contract, p.successor.value, Seq.empty[Token], p.successor.registers),
          p.payeeBox), p.prover.getAddress, burn = Seq(nft)))
    }
  }

  property("partial: rejects paying a miner that is not in the tree") {
    withCtx { ctx =>
      val all = miners(ctx)
      val stranger = Miner(proverWith(ctx, BigInteger.valueOf(44L)), 1000L)
      val pot = payout(ctx, all, Seq(stranger))
      val successor = UTXO(payoutContract(ctx), pot.value - Parameters.OneErg, pot.tokens, Seq(
        pot.outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(pot.totalScore).bigInteger),
        stateReg(boxValue, 0L, pot.bondsHeld)
      ))
      rejects(all.head.prover, build(ctx, Seq(pot.in, fundingInput(ctx, all.head.prover)),
        Seq(successor, minerBox(stranger, Parameters.OneErg)), all.head.prover.getAddress))
    }
  }

  // ─── the bond refund (usedBond) ───────────────────────────────────────────
  //
  // A miner's payment is their reward share plus their own bond, and the successor's ledger has to
  // fall by that bond and no more. Both halves matter: too little and the box keeps a deposit it no
  // longer owes, too much and a later payee finds their refund already spent.

  property("bond: the payment is the reward share plus the miner's own bond") {
    withCtx { ctx =>
      val p = partial(ctx)
      p.amount shouldBe (reward(p.paid.score, p.pot.totalScore, boxValue) + p.paid.bond)
      p.paid.bond should be >= lfsm.LFSMHelpers.MIN_ENTRY_BOND
      accepts(p.prover, partialTx(ctx, p))
    }
  }

  property("bond: rejects a payment that omits the miner's bond (minersRewarded)") {
    withCtx { ctx =>
      val p = partial(ctx)
      val shortPay = p.amount - p.paid.bond
      rejectsAtSigning(p.prover, partialTx(ctx, p,
        successor = p.successor.addValue(p.paid.bond),
        payee = minerBox(p.paid, shortPay)))
    }
  }

  property("bond: rejects a successor that keeps the refunded bond in its ledger (usedBond)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejectsAtSigning(p.prover, partialTx(ctx, p, successor = p.successor.withReg(3,
        stateReg(boxValue, p.pot.litSnapshot, p.pot.bondsHeld))))
    }
  }

  /**
   * The shape that would let one payee walk off with the others' deposits: the ledger drops to zero
   * while only one bond actually left the box.
   */
  property("bond: rejects a successor that sheds more bond than it refunded (usedBond)") {
    withCtx { ctx =>
      val p = partial(ctx)
      rejectsAtSigning(p.prover, partialTx(ctx, p,
        successor = p.successor.withReg(3, stateReg(boxValue, p.pot.litSnapshot, 0L))))
    }
  }

  /**
   * The reward slot is the block reward alone, so a miner's share is computed off it and never off
   * the box value, which also holds everyone else's deposits.
   */
  property("bond: a miner's reward share is unaffected by how much bond the box holds") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val share = reward(all.head.score, totalScore, boxValue)
      val potValue = boxValue + bondsOf(all)
      reward(all.head.score, totalScore, potValue) should be > share

      val p = partial(ctx)
      p.amount - p.paid.bond shouldBe share
      accepts(p.prover, partialTx(ctx, p))
    }
  }

  // ─── path 2 with LIT ──────────────────────────────────────────────────────

  private val litTotal = 10000L

  property("partial: pays the LIT share alongside ERG (LITRewarded)") {
    withCtx { ctx =>
      val p = partial(ctx, litSnapshot = litTotal)
      accepts(p.prover, partialTx(ctx, p))
    }
  }

  property("partial: rejects a token payout of the wrong amount (LITRewarded)") {
    withCtx { ctx =>
      val p = partial(ctx, litSnapshot = litTotal)
      val share = reward(p.paid.score, p.pot.totalScore, litTotal)
      rejects(p.prover, partialTx(ctx, p,
        payee = minerBox(p.paid, p.amount, Seq(Token(fpTokenId, share + 1L)))))
    }
  }

  property("partial: rejects a change to the LIT reward snapshot (sameLITReward)") {
    withCtx { ctx =>
      val p = partial(ctx, litSnapshot = litTotal)
      val share = reward(p.paid.score, p.pot.totalScore, litTotal)
      rejectsAtSigning(p.prover, partialTx(ctx, p, successor = p.successor.withReg(3,
        stateReg(boxValue, litTotal - share, p.pot.bondsHeld - p.paid.bond))))
    }
  }

  /**
   * The point of the snapshot: a miner paid second must receive the same share they would have
   * received first. Against the live token balance the second payee would be short-changed.
   */
  property("partial: the LIT share is proportional to score regardless of payout order") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val first = all.head
      val second = all(1)

      val firstShare = reward(first.score, totalScore, litTotal)
      val secondShare = reward(second.score, totalScore, litTotal)
      val remainingLit = litTotal - firstShare
      val stageTwoErg = boxValue + bondsOf(all) - owed(first, totalScore)

      val pot = payout(ctx, all, Seq(second), litSnapshot = litTotal, litHeld = remainingLit,
        value = stageTwoErg, inTreeEntries = Some(all.tail))

      val amount = owed(second, totalScore)
      val successor = UTXO(payoutContract(ctx), stageTwoErg - amount,
        Seq(nft, Token(fpTokenId, remainingLit - secondShare)), Seq(
          pot.outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger),
          stateReg(boxValue, litTotal, pot.bondsHeld - second.bond)
        ))

      // Fair share against the original total, not the depleted balance.
      secondShare shouldBe 5000L
      reward(second.score, totalScore, remainingLit) should be < secondShare

      accepts(second.prover, build(ctx, Seq(pot.in, fundingInput(ctx, second.prover)),
        Seq(successor, minerBox(second, amount, Seq(Token(fpTokenId, secondShare)))),
        second.prover.getAddress))
    }
  }

  property("partial: a box holding LIT with a zero snapshot pays none of it out") {
    withCtx { ctx =>
      val p = partial(ctx, litSnapshot = 0L, litHeld = litTotal)
      accepts(p.prover, partialTx(ctx, p))
    }
  }

  // ─── path 3: final payout, box consumed ───────────────────────────────────

  private case class Final(all: Seq[Miner], pot: Pot, outs: Seq[UTXO], prover: ErgoProver)

  /** Pays every miner, which consumes the whole reward and drops into the final path. */
  private def finalPayout(ctx: BlockchainContext, litSnapshot: Long = 0L): Final = {
    val all = miners(ctx)
    val pot = payout(ctx, all, all, litSnapshot)
    val outs = all.map { m =>
      val litShare = if (litSnapshot > 0L) reward(m.score, pot.totalScore, litSnapshot) else 0L
      minerBox(m, owed(m, pot.totalScore),
        if (litShare > 0L) Seq(Token(fpTokenId, litShare)) else Seq.empty[Token])
    }
    Final(all, pot, outs, all.head.prover)
  }

  private def finalTx(ctx: BlockchainContext, f: Final,
                      outs: Seq[UTXO] = null,
                      inputs: Seq[InputUTXO] = null,
                      burn: Seq[Token] = null): UnsignedTransaction =
    build(ctx,
      if (inputs == null) Seq(f.pot.in, fundingInput(ctx, f.prover)) else inputs,
      if (outs == null) f.outs else outs,
      f.prover.getAddress,
      burn = if (burn == null) Seq(nft) else burn)

  property("final: pays every remaining miner and consumes the box") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      accepts(f.prover, finalTx(ctx, f))
    }
  }

  property("final: rejects underpaying a miner (minersRewarded)") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      rejects(f.prover, finalTx(ctx, f, outs = f.outs.head.subValue(Parameters.OneErg) +: f.outs.tail))
    }
  }

  property("final: accepts overpaying a miner, since the check is a lower bound") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      accepts(f.prover, build(ctx,
        Seq(f.pot.in, fundingInput(ctx, f.prover, 3L * Parameters.OneErg)),
        f.outs.head.addValue(Parameters.OneErg) +: f.outs.tail,
        f.prover.getAddress, burn = Seq(nft)))
    }
  }

  property("final: rejects paying the wrong recipient (minersRewarded)") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      val stranger = Miner(proverWith(ctx, BigInteger.valueOf(55L)), 1L)
      rejects(f.prover, finalTx(ctx, f,
        outs = minerBox(stranger, f.outs.head.value) +: f.outs.tail))
    }
  }

  property("final: pays every remaining miner their full LIT share from the snapshot") {
    withCtx { ctx =>
      val f = finalPayout(ctx, litSnapshot = litTotal)
      accepts(f.prover, finalTx(ctx, f))
    }
  }

  property("final: rejects shorting a miner's LIT share (LITRewarded)") {
    withCtx { ctx =>
      val f = finalPayout(ctx, litSnapshot = litTotal)
      rejects(f.prover, finalTx(ctx, f,
        outs = f.outs.head.removeToken(Token(fpTokenId, 1L)) +: f.outs.tail,
        burn = Seq(nft, Token(fpTokenId, 1L))))
    }
  }

  property("final: rejects when the payout box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      rejects(f.prover, finalTx(ctx, f, inputs = Seq(fundingInput(ctx, f.prover), f.pot.in)))
    }
  }

  property("final: rejects a spend that does not burn the rollup NFT (nftBurned)") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      val keepsNft = f.outs.head.setTokens(nft) +: f.outs.tail
      rejectsAtSigning(f.prover, build(ctx, Seq(f.pot.in, fundingInput(ctx, f.prover)),
        keepsNft, f.prover.getAddress))
    }
  }

  /**
   * The condition that stops a spend paying a cheap tail of the tree and walking off with the deposits
   * of everyone it left behind. The box is consumed here, so every bond it still owes has to leave
   * with it.
   */
  property("final: rejects consuming the box while other miners' bonds are unrefunded (allBondsRefunded)") {
    withCtx { ctx =>
      val all = miners(ctx)
      val paid = Seq(all.head)
      // Value trimmed so that paying one miner drops the leftover under the partial path's threshold,
      // which is the only way to reach the final path without naming everyone.
      val trimmed = owed(all.head, all.map(_.score).sum) + 500000L
      val pot = payout(ctx, all, paid, value = trimmed)
      rejectsAtSigning(all.head.prover, build(ctx, Seq(pot.in, fundingInput(ctx, all.head.prover)),
        Seq(minerBox(all.head, owed(all.head, pot.totalScore))),
        all.head.prover.getAddress, burn = Seq(nft)))
    }
  }

  // ─── path 1: the empty-tree drain ─────────────────────────────────────────
  //
  // A rollup that received no submissions reaches payout with R5 at zero and an empty tree, so it owes
  // nobody. The drain lets anyone sweep it, gated on three conditions: the transaction carries no
  // miner-fee output, the rollup NFT is burnt, and the bond ledger is empty.
  //
  // The fee gate is an *incentive* barrier, not an authorisation check. The contract pins nothing
  // about where the value goes and nobody has to sign. What it does is make the sweep worthless to
  // relay — a fee-less transaction pays a block producer nothing through the normal channel — so in
  // practice the value reaches whoever produces the block, or whoever pays them out of band. Several
  // properties below pin the omissions deliberately, so that reading them together describes the real
  // guarantee rather than the stronger one the contract comment suggests.

  private case class Drain(ctx: BlockchainContext, in: InputUTXO, sweeper: ErgoProver, value: Long)

  /**
   * A payout box that received no submissions. No context variables are attached: the drain path
   * reaches none of them, and the `getOrElse` defaults on CTX_KEY_DATA and the two proofs are what
   * make that safe.
   */
  private def drainable(ctx: BlockchainContext,
                        minerCount: Int = 0,
                        entries: Seq[Miner] = Seq.empty[Miner],
                        totalScore: Long = 0L,
                        lit: Long = 0L,
                        bondHeld: Long = 0L,
                        value: Long = boxValue): Drain = {
    val tree = commitmentTree(entries.map(m => m.key -> m.nisp))
    val tokens = if (lit > 0L) Seq(nft, Token(fpTokenId, lit)) else Seq(nft)
    val box = UTXO(payoutContract(ctx), value, tokens, Seq(
      tree.ergoValue,
      ErgoValue.of(minerCount),
      ErgoValue.of(BigInt(totalScore).bigInteger),
      stateReg(boxValue, lit, bondHeld)
    ))
    Drain(ctx, box.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort),
      proverWith(ctx, BigInteger.valueOf(77L)), value)
  }

  /**
   * The sweep. It carries no fee, which is the condition under test, so `TxBuilder` has nothing to
   * fold a sub-minimum change amount into — the outputs have to sum to exactly the input total or the
   * build throws before the contract is ever consulted.
   */
  private def sweep(d: Drain)(outputs: Seq[UTXO],
                              inputs: Seq[InputUTXO] = Seq(d.in),
                              fee: Long = 0L,
                              burn: Seq[Token] = Seq(nft)): UnsignedTransaction =
    TxBuilder(d.ctx).setInputs(inputs: _*).setOutputs(outputs: _*)
      .buildTx(fee, d.sweeper.getAddress, burn)

  /** An explicit fee-proposition output, so a negative can put it at a chosen index. */
  private def feeBox: UTXO = UTXO(Contract.FEE_720, Parameters.MinFee)

  property("drain: an empty payout box is swept by a transaction that pays no miner fee") {
    withCtx { ctx =>
      val d = drainable(ctx)
      accepts(d.sweeper, sweep(d)(Seq(UTXO(contractOf(d.sweeper), d.value))))
    }
  }

  /**
   * The recreate-itself spend is still permitted — the drain constrains no output at all — but it is
   * no longer the only thing permitted, which is what stops an unsubmitted rollup locking its value.
   */
  property("drain: the value is not locked to a self-recreating successor") {
    withCtx { ctx =>
      val d = drainable(ctx)
      val stranger = proverWith(ctx, BigInteger.valueOf(88L))
      accepts(d.sweeper, sweep(d)(Seq(UTXO(contractOf(stranger), d.value))))
    }
  }

  property("drain: rejects a sweep carrying a miner-fee output (noFeeOutput)") {
    withCtx { ctx =>
      val d = drainable(ctx)
      rejectsAtSigning(d.sweeper, sweep(d)(
        Seq(UTXO(contractOf(d.sweeper), d.value - Parameters.MinFee), feeBox)))
    }
  }

  /** `OUTPUTS.exists` scans the whole collection, so the position of the fee output cannot matter. */
  property("drain: rejects a fee output at any position, not just the last (noFeeOutput)") {
    withCtx { ctx =>
      val d = drainable(ctx)
      rejectsAtSigning(d.sweeper, sweep(d)(
        Seq(feeBox, UTXO(contractOf(d.sweeper), d.value - Parameters.MinFee))))
    }
  }

  /**
   * The cross-check that matters for the constant itself: `CONST_FEE_HASH` is injected from
   * `Contract.FEE_720`, and this is the one property that runs it against the fee output appkit
   * actually emits rather than one the spec built. If the two ever diverge, the drain silently stops
   * being gated and this is what says so.
   */
  property("drain: the constant matches the fee output appkit itself builds (noFeeOutput)") {
    withCtx { ctx =>
      val d = drainable(ctx)
      rejectsAtSigning(d.sweeper, build(ctx, Seq(d.in, fundingInput(ctx, d.sweeper)),
        Seq(UTXO(contractOf(d.sweeper), d.value)), d.sweeper.getAddress, burn = Seq(nft)))
    }
  }

  property("drain: rejects when the payout box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val d = drainable(ctx)
      val funding = fundingInput(ctx, d.sweeper)
      rejectsAtSigning(d.sweeper, sweep(d)(
        Seq(UTXO(contractOf(d.sweeper), d.value + funding.value)),
        inputs = Seq(funding, d.in)))
    }
  }

  property("drain: rejects a sweep that does not burn the rollup NFT (nftBurned)") {
    withCtx { ctx =>
      val d = drainable(ctx)
      rejectsAtSigning(d.sweeper, sweep(d)(
        Seq(UTXO(contractOf(d.sweeper), d.value, Seq(nft))), burn = Seq.empty[Token]))
    }
  }

  /**
   * An empty rollup owes nobody a refund, so a nonzero ledger means the box is not really empty and
   * the sweep would be walking off with someone's deposit.
   */
  property("drain: rejects a sweep of a box that still owes bonds (noBondLiability)") {
    withCtx { ctx =>
      val d = drainable(ctx, bondHeld = lfsm.LFSMHelpers.MIN_ENTRY_BOND)
      rejectsAtSigning(d.sweeper, sweep(d)(Seq(UTXO(contractOf(d.sweeper), d.value))))
    }
  }

  /** The LIT of an unsubmitted rollup leaves with the sweeper — nothing returns it to the pool. */
  property("drain: sweeps the box's LIT along with its ERG") {
    withCtx { ctx =>
      val d = drainable(ctx, lit = litTotal)
      accepts(d.sweeper, sweep(d)(
        Seq(UTXO(contractOf(d.sweeper), d.value, Seq(Token(fpTokenId, litTotal))))))
    }
  }

  /**
   * Nothing in the drain path authenticates the spender, so the incentive argument in the contract
   * comment is off-chain reasoning rather than an enforced condition. Pinned deliberately: a reader
   * comparing the comment to the contract should find this property rather than assume a miner check
   * exists somewhere.
   */
  property("drain: the sweeper is not authenticated and need not be the block producer") {
    withCtx { ctx =>
      val d = drainable(ctx)
      val outsider = proverWith(ctx, BigInteger.valueOf(99L))
      accepts(outsider, sweep(d)(Seq(UTXO(contractOf(outsider), d.value))))
    }
  }

  /** R5 is the whole gate, so a box that still owes miners cannot take this path however it is built. */
  property("drain: a box that still owes miners cannot be swept (currentMiners)") {
    withCtx { ctx =>
      val all = miners(ctx)
      val d = drainable(ctx, minerCount = all.size, entries = all,
        totalScore = all.map(_.score).sum, bondHeld = bondsOf(all))
      rejectsAtSigning(d.sweeper, sweep(d)(Seq(UTXO(contractOf(d.sweeper), d.value))))
    }
  }

  /**
   * The one state the drain does not reach. The partial path removes miners from the tree but holds R5
   * constant, so after a payout the tree is shorter than R5 says — and a box emptied that way keeps a
   * nonzero R5 and stays outside the drain.
   */
  property("drain: a box emptied by payouts keeps its miner count and stays outside the drain") {
    withCtx { ctx =>
      val all = miners(ctx)
      val d = drainable(ctx, minerCount = all.size, entries = Seq.empty,
        totalScore = all.map(_.score).sum)
      rejectsAtSigning(d.sweeper, sweep(d)(Seq(UTXO(contractOf(d.sweeper), d.value))))
    }
  }

  // ─── the dust bound ───────────────────────────────────────────────────────
  //
  // The partial path only recreates the box while more than 1e6 nanoERG is left, so a rollup whose
  // truncation deficit ever exceeded that would recreate itself with an empty tree and lock. The
  // deficit is under one nanoERG per miner, so it takes over a million submitters to reach. The bonds
  // do not change that: each one is collected and refunded by the same formula, so they cancel out of
  // the leftover entirely.

  property("dust: paying every miner leaves only the truncation deficit, bonds cancelling exactly") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val potValue = boxValue + bondsOf(all)
      val paidOut = all.map(m => owed(m, totalScore)).sum
      val leftover = potValue - paidOut

      leftover shouldBe (boxValue - all.map(m => reward(m.score, totalScore, boxValue)).sum)
      leftover should be < all.size.toLong
      leftover should be < 1000000L
    }
  }

  /**
   * The routing that keeps the box spendable when miners are settled one at a time. Every step but
   * the last leaves at least one unpaid bond behind, and the bond floor is above the partial path's
   * threshold, so the partial path stays open until exactly one miner remains.
   */
  property("dust: settling one miner at a time stays on the partial path until the last") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val leftovers = all.indices.map { i =>
        val paid = all.take(i + 1)
        boxValue + bondsOf(all) - paid.map(m => owed(m, totalScore)).sum
      }
      leftovers.init.foreach(_ should be > 1000000L)
      leftovers.last should be < 1000000L
      lfsm.LFSMHelpers.MIN_ENTRY_BOND should be > 1000000L
    }
  }

  /** Paying everyone in one transaction ends on the final path, which consumes the box. */
  property("dust: paying every miner ends on the final path, so the emptied-tree state is never produced") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      accepts(f.prover, finalTx(ctx, f))
    }
  }
  // ─── every named miner must actually get an output ────────────────────────
  //
  // `zip` stops at the shorter side, so a spend naming more miners than it produces outputs for used
  // to leave the surplus miners unchecked. On the final path nothing else pins where the box's value
  // goes, which made it a way to pay one miner and keep the rest of the rewards and bonds.

  property("final: rejects naming every miner but emitting one output (allMinersPaid)") {
    withCtx { ctx =>
      val f = finalPayout(ctx)
      // No fee and no funding input, so OUTPUTS holds exactly this one box and nothing else lands
      // inside the slice to be checked against the miners left out.
      val only = UTXO(f.all.head.contract, f.pot.value)
      rejectsAtSigning(f.prover, TxBuilder(ctx).setInputs(f.pot.in).setOutputs(only)
        .buildTx(0L, f.prover.getAddress, Seq(nft)))
    }
  }

  property("partial: rejects naming two miners while paying only one (allMinersPaid)") {
    withCtx { ctx =>
      val all = miners(ctx)
      val named = all.take(2)
      val pot = payout(ctx, all, named)
      val owedBoth = named.map(m => owed(m, pot.totalScore)).sum
      val successor = UTXO(payoutContract(ctx), pot.value - owedBoth, pot.tokens, Seq(
        pot.outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(pot.totalScore).bigInteger),
        stateReg(boxValue, 0L, pot.bondsHeld - bondsOf(named))
      ))
      // Only the first named miner gets a box, and the successor still gives up both payments.
      val paid = minerBox(named.head, owed(named.head, pot.totalScore) + owed(named(1), pot.totalScore))
      rejectsAtSigning(named.head.prover, TxBuilder(ctx).setInputs(pot.in)
        .setOutputs(successor, paid).buildTx(0L, named.head.prover.getAddress))
    }
  }
}
