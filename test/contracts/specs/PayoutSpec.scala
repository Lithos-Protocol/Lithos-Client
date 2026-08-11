package contracts.specs

import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

/**
 * Payout.ergo — one property per named condition in the contract.
 *
 * Path 1 (rewards paid leave more than the min change value behind): the box recreates itself with
 * the paid miners removed from the tree.
 * Path 2 (otherwise): the remaining miners are paid and the box is consumed.
 */
class PayoutSpec extends AnyPropSpec with RollupSpecBase {

  private case class Miner(prover: ErgoProver, score: Long) {
    def contract: Contract = contractOf(prover)
    def key: Array[Byte] = contract.hashedPropBytes
    def nisp: Array[Byte] = nispBytes(score, 8000)
  }

  private def miners(ctx: BlockchainContext): Seq[Miner] = Seq(
    Miner(proverWith(ctx, BigInteger.valueOf(11L)), 3000L),
    Miner(proverWith(ctx, BigInteger.valueOf(22L)), 5000L),
    Miner(proverWith(ctx, BigInteger.valueOf(33L)), 2000L)
  )

  private def reward(score: Long, totalScore: Long, totalReward: Long): Long =
    (BigInt(totalReward) * BigInt(score) / BigInt(totalScore)).toLong

  /**
   * Payout box holding all of `all`, with a lookup+removal proof covering `toPay`.
   * Both proofs are generated against the same input digest, which is what the contract verifies.
   */
  private def payout(ctx: BlockchainContext,
                     all: Seq[Miner],
                     toPay: Seq[Miner],
                     tokens: Seq[Token] = Seq.empty[Token],
                     tokenReward: Option[Long] = None,
                     value: Long = boxValue,
                     inTreeEntries: Option[Seq[Miner]] = None) = {
    val totalScore = all.map(_.score).sum
    val tree = treeWith(inTreeEntries.getOrElse(all).map(m => m.key -> m.nisp))
    val inTree = tree.ergoValue

    val keys = toPay.map(_.key)
    val lookup = tree.lookUp(keys: _*)
    val removal = tree.delete(keys: _*)
    val outTree = tree.ergoValue

    val base = Seq(
      inTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger), ErgoValue.of(boxValue)
    )
    val regs = tokenReward.map(t => base :+ ErgoValue.of(t)).getOrElse(base)
    val box = UTXO(payoutContract(ctx), value, tokens, regs)

    val in = box.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        ContextVar.of(0.toByte, ErgoValue.of(
          Colls.fromArray(keys.map(k => Colls.fromArray(k)).toArray), ErgoType.collType(scalaByteType))),
        ContextVar.of(1.toByte, lookup.proof.ergoValue),
        ContextVar.of(2.toByte, removal.proof.ergoValue)
      )
    (in, box, outTree, totalScore)
  }

  private def minerBox(m: Miner, value: Long, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(m.contract, value, tokens)

  // ─── path 1: partial payout, box recreated ────────────────────────────────

  /** Pays the first miner only, leaving a large remainder so path 1 is taken. */
  private def partial(ctx: BlockchainContext, tokens: Seq[Token] = Seq.empty[Token]) = {
    val all = miners(ctx)
    val paid = Seq(all.head)
    val (in, box, outTree, totalScore) = payout(ctx, all, paid, tokens)
    val amount = reward(paid.head.score, totalScore, boxValue)
    val successor = UTXO(payoutContract(ctx), boxValue - amount, tokens, Seq(
      outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger), ErgoValue.of(boxValue)
    ))
    (all.head.prover, in, successor, minerBox(paid.head, amount), amount, all, totalScore, outTree)
  }

  property("partial: pays a subset and recreates the box with the payee removed") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(successor, payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects a payout of the wrong amount (minersRewarded)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, amount, all, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor, minerBox(all.head, amount + Parameters.OneErg)), prover.getAddress))
    }
  }

  property("partial: rejects a payout to the wrong recipient (minersRewarded)") {
    withCtx { ctx =>
      val (prover, in, successor, _, amount, all, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor, minerBox(all(1), amount)), prover.getAddress))
    }
  }

  property("partial: rejects a successor tree that does not match the removal (treeUpdated)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      val other = treeWith(Seq(scorex.crypto.hash.Blake2b256.hash("nope") -> nispBytes(1L, 16)))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor.withReg(0, other.ergoValue), payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects a successor that keeps the paid-out value (usedReward)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor.addValue(Parameters.OneErg), payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects a successor that is not the payout contract (validUTXO)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor.setContract(Contract.SIGMA_TRUE), payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects a change to the total reward (sameTotal)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor.withReg(3, ErgoValue.of(boxValue + 1L)), payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects a change to the total score (sameScore)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor.withReg(2, ErgoValue.of(BigInt(1L).bigInteger)), payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects a change to the miner count (sameMiners)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor.withReg(1, ErgoValue.of(99)), payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects when the payout box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val (prover, in, successor, payeeBox, _, _, _, _) = partial(ctx)
      rejects(prover, build(ctx, Seq(fundingInput(ctx, prover), in), Seq(successor, payeeBox), prover.getAddress))
    }
  }

  property("partial: rejects paying a miner that is not in the tree") {
    withCtx { ctx =>
      val all = miners(ctx)
      val stranger = Miner(proverWith(ctx, BigInteger.valueOf(44L)), 1000L)
      val prover = all.head.prover
      val totalScore = all.map(_.score).sum
      val tree = treeWith(all.map(m => m.key -> m.nisp))
      val inTree = tree.ergoValue
      val keys = Seq(stranger.key)
      val lookup = tree.lookUp(keys: _*)
      val removal = tree.delete(keys: _*)

      val in = UTXO(payoutContract(ctx), boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger), ErgoValue.of(boxValue)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          ContextVar.of(0.toByte, ErgoValue.of(
            Colls.fromArray(keys.map(k => Colls.fromArray(k)).toArray), ErgoType.collType(scalaByteType))),
          ContextVar.of(1.toByte, lookup.proof.ergoValue),
          ContextVar.of(2.toByte, removal.proof.ergoValue)
        )
      val successor = UTXO(payoutContract(ctx), boxValue - Parameters.OneErg, Seq.empty[Token], Seq(
        tree.ergoValue, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger), ErgoValue.of(boxValue)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(successor, minerBox(stranger, Parameters.OneErg)), prover.getAddress))
    }
  }

  // ─── path 1 with tokens ───────────────────────────────────────────────────

  /** Token total snapshotted into R8 by the eval transform; the denominator base for every share. */
  private val tokenTotal = 10000L

  private def withTokens(ctx: BlockchainContext, paidIdx: Int) = {
    val held = Token(fpTokenId, tokenTotal)
    val all = miners(ctx)
    val paid = all(paidIdx)
    val (in, _, outTree, totalScore) = payout(ctx, all, Seq(paid), Seq(held), Some(tokenTotal))
    val ergAmount = reward(paid.score, totalScore, boxValue)
    val tokAmount = reward(paid.score, totalScore, tokenTotal)
    val successor = UTXO(payoutContract(ctx), boxValue - ergAmount,
      Seq(Token(fpTokenId, tokenTotal - tokAmount)), Seq(
        outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger),
        ErgoValue.of(boxValue), ErgoValue.of(tokenTotal)
      ))
    (all, paid, in, successor, ergAmount, tokAmount, totalScore, outTree)
  }

  property("partial: pays the LIT share alongside ERG (tokensRewarded)") {
    withCtx { ctx =>
      val (_, paid, in, successor, ergAmount, tokAmount, _, _) = withTokens(ctx, 0)
      accepts(paid.prover, build(ctx, Seq(in, fundingInput(ctx, paid.prover)),
        Seq(successor, minerBox(paid, ergAmount, Seq(Token(fpTokenId, tokAmount)))), paid.prover.getAddress))
    }
  }

  property("partial: rejects a token payout of the wrong amount (tokensRewarded)") {
    withCtx { ctx =>
      val (_, paid, in, successor, ergAmount, tokAmount, _, _) = withTokens(ctx, 0)
      rejects(paid.prover, build(ctx, Seq(in, fundingInput(ctx, paid.prover)),
        Seq(successor, minerBox(paid, ergAmount, Seq(Token(fpTokenId, tokAmount + 1L)))), paid.prover.getAddress))
    }
  }

  property("partial: rejects a change to the token reward snapshot (sameTokenReward)") {
    withCtx { ctx =>
      val (_, paid, in, successor, ergAmount, tokAmount, _, _) = withTokens(ctx, 0)
      rejects(paid.prover, build(ctx, Seq(in, fundingInput(ctx, paid.prover)),
        Seq(successor.withReg(4, ErgoValue.of(tokenTotal - tokAmount)),
          minerBox(paid, ergAmount, Seq(Token(fpTokenId, tokAmount)))), paid.prover.getAddress))
    }
  }

  /**
   * The point of the R8 snapshot: a miner paid second must receive the same share they would have
   * received first. Against the live token balance the second payee would be short-changed.
   */
  property("partial: the LIT share is proportional to score regardless of payout order") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val first = all.head
      val second = all(1)

      val firstShare = reward(first.score, totalScore, tokenTotal)
      val secondShare = reward(second.score, totalScore, tokenTotal)
      val remainingTokens = tokenTotal - firstShare

      // Second stage: the successor left by paying `first`, now paying `second`.
      val stageTwoErg = boxValue - reward(first.score, totalScore, boxValue)
      val (in, _, outTree, _) = payout(ctx, all, Seq(second),
        tokens = Seq(Token(fpTokenId, remainingTokens)),
        tokenReward = Some(tokenTotal),
        value = stageTwoErg,
        inTreeEntries = Some(all.tail))

      val ergAmount = reward(second.score, totalScore, boxValue)
      val successor = UTXO(payoutContract(ctx), stageTwoErg - ergAmount,
        Seq(Token(fpTokenId, remainingTokens - secondShare)), Seq(
          outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger),
          ErgoValue.of(boxValue), ErgoValue.of(tokenTotal)
        ))

      // Fair share against the original total, not the depleted balance.
      secondShare shouldBe 5000L
      reward(second.score, totalScore, remainingTokens) should be < secondShare

      accepts(second.prover, build(ctx, Seq(in, fundingInput(ctx, second.prover)),
        Seq(successor, minerBox(second, ergAmount, Seq(Token(fpTokenId, secondShare)))), second.prover.getAddress))
    }
  }

  property("partial: a box carrying tokens but no R8 snapshot pays no LIT at all") {
    withCtx { ctx =>
      val held = Token(fpTokenId, tokenTotal)
      val all = miners(ctx)
      val paid = all.head
      val (in, _, outTree, totalScore) = payout(ctx, all, Seq(paid), Seq(held), tokenReward = None)
      val ergAmount = reward(paid.score, totalScore, boxValue)
      val successor = UTXO(payoutContract(ctx), boxValue - ergAmount, Seq(held), Seq(
        outTree, ErgoValue.of(all.size), ErgoValue.of(BigInt(totalScore).bigInteger), ErgoValue.of(boxValue)
      ))
      accepts(paid.prover, build(ctx, Seq(in, fundingInput(ctx, paid.prover)),
        Seq(successor, minerBox(paid, ergAmount)), paid.prover.getAddress))
    }
  }

  // ─── path 2: final payout, box consumed ───────────────────────────────────

  /** Pays every miner, which consumes the whole reward and drops into path 2. */
  private def finalPayout(ctx: BlockchainContext) = {
    val all = miners(ctx)
    val (in, _, _, totalScore) = payout(ctx, all, all)
    val amounts = all.map(m => reward(m.score, totalScore, boxValue))
    (all, in, amounts, totalScore)
  }

  property("final: pays every remaining miner and consumes the box") {
    withCtx { ctx =>
      val (all, in, amounts, _) = finalPayout(ctx)
      val prover = all.head.prover
      val outs = all.zip(amounts).map { case (m, a) => minerBox(m, a) }
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), outs, prover.getAddress))
    }
  }

  property("final: rejects underpaying a miner (minersRewarded)") {
    withCtx { ctx =>
      val (all, in, amounts, _) = finalPayout(ctx)
      val prover = all.head.prover
      val outs = all.zip(amounts).map { case (m, a) => minerBox(m, a) }
      val short = outs.head.subValue(Parameters.OneErg) +: outs.tail
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), short, prover.getAddress))
    }
  }

  property("final: accepts overpaying a miner, since the check is a lower bound") {
    withCtx { ctx =>
      val (all, in, amounts, _) = finalPayout(ctx)
      val prover = all.head.prover
      val outs = all.zip(amounts).map { case (m, a) => minerBox(m, a) }
      val over = outs.head.addValue(Parameters.OneErg) +: outs.tail
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover, 3L * Parameters.OneErg)), over, prover.getAddress))
    }
  }

  property("final: rejects paying the wrong recipient (minersRewarded)") {
    withCtx { ctx =>
      val (all, in, amounts, _) = finalPayout(ctx)
      val prover = all.head.prover
      val stranger = Miner(proverWith(ctx, BigInteger.valueOf(55L)), 1L)
      val outs = minerBox(stranger, amounts.head) +: all.tail.zip(amounts.tail).map { case (m, a) => minerBox(m, a) }
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), outs, prover.getAddress))
    }
  }

  property("final: pays every remaining miner their full LIT share from the R8 snapshot") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val (in, _, _, _) = payout(ctx, all, all, Seq(Token(fpTokenId, tokenTotal)), Some(tokenTotal))
      val prover = all.head.prover
      val outs = all.map { m =>
        minerBox(m, reward(m.score, totalScore, boxValue),
          Seq(Token(fpTokenId, reward(m.score, totalScore, tokenTotal))))
      }
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), outs, prover.getAddress))
    }
  }

  property("final: rejects shorting a miner's LIT share (tokensRewarded)") {
    withCtx { ctx =>
      val all = miners(ctx)
      val totalScore = all.map(_.score).sum
      val (in, _, _, _) = payout(ctx, all, all, Seq(Token(fpTokenId, tokenTotal)), Some(tokenTotal))
      val prover = all.head.prover
      val outs = all.map { m =>
        minerBox(m, reward(m.score, totalScore, boxValue),
          Seq(Token(fpTokenId, reward(m.score, totalScore, tokenTotal))))
      }
      val shorted = outs.head.removeToken(Token(fpTokenId, 1L)) +: outs.tail
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), shorted,
        prover.getAddress, burn = Seq(Token(fpTokenId, 1L))))
    }
  }

  property("final: rejects when the payout box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val (all, in, amounts, _) = finalPayout(ctx)
      val prover = all.head.prover
      val outs = all.zip(amounts).map { case (m, a) => minerBox(m, a) }
      rejects(prover, build(ctx, Seq(fundingInput(ctx, prover), in), outs, prover.getAddress))
    }
  }

  // ─── empty tree ───────────────────────────────────────────────────────────

  property("empty tree: the box can only recreate itself, so its value is locked (analysis 16.6)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val tree = treeWith(Seq.empty)
      val inTree = tree.ergoValue
      val noKeys = ErgoValue.of(Colls.fromArray(Array.empty[sigma.Coll[Byte]]), ErgoType.collType(scalaByteType))
      val lookup = tree.lookUp()
      val removal = tree.delete()

      def boxWith(vars: Seq[ContextVar]): InputUTXO =
        UTXO(payoutContract(ctx), boxValue, Seq.empty[Token], Seq(
          inTree, ErgoValue.of(0), ErgoValue.of(BigInt(1L).bigInteger), ErgoValue.of(boxValue)
        )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort).setCtxVars(vars: _*)

      val vars = Seq(
        ContextVar.of(0.toByte, noKeys),
        ContextVar.of(1.toByte, lookup.proof.ergoValue),
        ContextVar.of(2.toByte, removal.proof.ergoValue)
      )

      val unchanged = UTXO(payoutContract(ctx), boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(0), ErgoValue.of(BigInt(1L).bigInteger), ErgoValue.of(boxValue)
      ))
      accepts(prover, build(ctx, Seq(boxWith(vars), fundingInput(ctx, prover)), Seq(unchanged), prover.getAddress))

      val drained = UTXO(payoutContract(ctx), Parameters.MinFee, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(0), ErgoValue.of(BigInt(1L).bigInteger), ErgoValue.of(boxValue)
      ))
      rejects(prover, build(ctx, Seq(boxWith(vars), fundingInput(ctx, prover)),
        Seq(drained, UTXO(contractOf(prover), boxValue - Parameters.MinFee)), prover.getAddress))
    }
  }
}
