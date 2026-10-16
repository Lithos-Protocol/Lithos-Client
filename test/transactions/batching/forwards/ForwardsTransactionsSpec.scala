package transactions.batching.forwards

import forwards.contracts.{DFTerms, DifficultyForwardContracts}
import forwards.{DFHelpers, DFMath}
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.scalatest.propspec.AnyPropSpec
import sigma.crypto.CryptoConstants
import sigma.data.{COR, ProveDlog}
import work.lithos.mutations.{Contract, Token, UTXO}

import java.math.BigInteger
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * The client's recordings and settlements. Every builder signs what it builds, which runs DF_Accumulator and
 * DF_Position on it, so a step coming back at all is the contracts accepting it. What is asserted here is the
 * client's own choices: which epoch it records and when it declines, where the rewards go, which fee is paid
 * out of what, and the order a run settles in.
 */
class ForwardsTransactionsSpec extends AnyPropSpec with ForwardsFixtures {

  import _root_.contracts.specs.forwards.DFSpecBase._

  private val BALANCE: Long = 100L * LIT
  private val M: Long = NOTIONAL / 5
  private def lit(amount: Long): Token = Token(litId, amount)

  /** A context whose last block is `lastBlock`, with a wallet built the way the client builds its own. */
  private def atLastBlock[A](lastBlock: Int = fixtureLastBlock, nBits: Long = fixtureNBits)
                            (f: (BlockchainContext, NodeWallet) => A): A = {
    val client = lastBlockClient(lastBlock, nBits)
    val wallet = walletOf(client)
    client.execute(ctx => f(ctx, wallet))
  }

  private def ringOf(lastEpoch: Int)(f: Int => Long): IndexedSeq[Long] =
    (0 until RING_SIZE).map(i => f(lastEpoch - (lastEpoch - i) % RING_SIZE))

  private def moved(percent: Long): Long = STRIKE + STRIKE * percent / 100

  /** An accumulator last recorded at `lastEpoch`, holding `percent` over the strike inside `windows`, the strike elsewhere. */
  private def accHolding(ctx: BlockchainContext, lastEpoch: Int, percent: Long, windows: DFTerms*): DFAccState = {
    def inside(e: Int) = windows.exists(t => e >= t.windowStart && e < t.windowStart + t.windowLength)
    accAt(ctx, AccState(lastEpoch, ringOf(lastEpoch)(e => if (inside(e)) moved(percent) else STRIKE), BALANCE))
  }

  /** A window of 8 that ended 13 epochs before the stock fixture's. */
  private val ended: DFTerms = terms(fixtureEpoch - 20, 8)

  private def outputs(step: DFStep) = step.signed.getOutputsToSpend.asScala.toVector
  private def tokensOf(index: Int, step: DFStep): Seq[Token] = outputs(step)(index).getTokens.asScala.map(Token.ergo).toSeq
  private def treeOf(index: Int, step: DFStep): String = Contract(outputs(step)(index).getErgoTree).ergoTreeHex

  // ─── which epoch a signature can record ───────────────────────────────────

  property("recordingAt: the tip's epoch, with the difficulty of the header a signature reads") {
    atLastBlock() { (ctx, _) =>
      ForwardsTransactions.recordingAt(ctx) shouldBe Some(DFRecording(fixtureEpoch, difficultyOf(fixtureNBits)))
    }
  }

  property("recordingAt: none while the tip is an epoch's first block, where a recording of it cannot sign") {
    // The last block closes epoch 964, so the tip above it opens 965. Appkit signs with headers(0) one below the
    // tip, in 964, while the node validates the next block with the tip, in 965: no recording satisfies both
    atLastBlock(965 * 128) { (ctx, wallet) =>
      ctx.getHeight shouldBe 965 * 128 + 1
      ForwardsTransactions.recordingAt(ctx) shouldBe None
      val acc = accAt(ctx, AccState(964, flatRing(STRIKE), BALANCE))
      an[Exception] should be thrownBy ForwardsTransactions.record(ctx, wallet, contracts, acc,
        DFRecording(965, difficultyOf(fixtureNBits)), Seq(walletBox(ctx, wallet, UTXO.MIN_CHANGE)), 0L)
    }
  }

  property("recordingAt: one block on, the new epoch records with its own difficulty, and signs") {
    val nBits = nBitsFor(3L * STRIKE)
    atLastBlock(965 * 128 + 1, nBits) { (ctx, wallet) =>
      val recording = ForwardsTransactions.recordingAt(ctx).get
      recording shouldBe DFRecording(965, difficultyOf(nBits))
      val acc = accAt(ctx, AccState(964, flatRing(STRIKE), BALANCE))
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, recording,
        Seq(walletBox(ctx, wallet, UTXO.MIN_CHANGE)), 0L)
      step.next.ring(965 % RING_SIZE) shouldBe difficultyOf(nBits)
    }
  }

  // ─── a recording on its own ───────────────────────────────────────────────

  property("record: fee-less, the change box takes the reward and the funding box whole, and pays no fee") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accAt(ctx, AccState(fixtureEpoch - 3, traceableRing(fixtureEpoch - 3, 1000000L), BALANCE))
      val recording = ForwardsTransactions.recordingAt(ctx).get
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, recording,
        Seq(walletBox(ctx, wallet, UTXO.MIN_CHANGE)), 0L)

      step.recorded shouldBe true
      step.next shouldBe step.next.copy(lastEpoch = fixtureEpoch,
        ring = DFMath.recordedRing(acc.ring, acc.lastEpoch, fixtureEpoch, recording.difficulty), lit = BALANCE - LIT)
      outputs(step) should have size 2
      outputs(step)(1).getValue shouldBe UTXO.MIN_CHANGE
      tokensOf(1, step) shouldBe Seq(lit(LIT))
      treeOf(1, step) shouldBe wallet.contract.ergoTreeHex
      withClue("the successor reads back as the accumulator: ") {
        DFBoxes.accumulator(nodeBox(ctx, step.next.box.toUTXO, 0), contracts).map(_.lastEpoch) shouldBe Some(fixtureEpoch)
      }
    }
  }

  property("record: a broadcast pays its fee out of the funding, and keeps the change above the minimum") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accAt(ctx, AccState(fixtureEpoch - 1, flatRing(STRIKE), BALANCE))
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, ForwardsTransactions.recordingAt(ctx).get,
        Seq(walletBox(ctx, wallet, Parameters.MinFee + UTXO.MIN_CHANGE)), Parameters.MinFee)
      outputs(step).map(_.getValue) shouldBe Vector(acc.box.value, Parameters.MinFee, UTXO.MIN_CHANGE)
      treeOf(1, step) shouldBe Contract.FEE.ergoTreeHex
      tokensOf(2, step) shouldBe Seq(lit(LIT))
    }
  }

  property("record: with under 1 LIT left, takes all of it and leaves the accumulator with its NFT alone") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accAt(ctx, AccState(fixtureEpoch - 1, flatRing(STRIKE), LIT / 4))
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, ForwardsTransactions.recordingAt(ctx).get,
        Seq(walletBox(ctx, wallet, UTXO.MIN_CHANGE)), 0L)
      step.next.lit shouldBe 0L
      tokensOf(0, step) shouldBe Seq(Token(accNFT, 1L))
      tokensOf(1, step) shouldBe Seq(lit(LIT / 4))
    }
  }

  property("record: refuses an epoch the accumulator already holds") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accAt(ctx, AccState(fixtureEpoch, flatRing(STRIKE), BALANCE))
      an[IllegalArgumentException] should be thrownBy ForwardsTransactions.record(ctx, wallet, contracts, acc,
        ForwardsTransactions.recordingAt(ctx).get, Seq(walletBox(ctx, wallet, UTXO.MIN_CHANGE)), 0L)
    }
  }

  // ─── settling one position ────────────────────────────────────────────────

  property("settle: fee-less, pays each side, deposits 2 LIT, and keeps 1 LIT and the ERG left over") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accHolding(ctx, fixtureEpoch, 8, ended)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), ended, 1)
      val step = ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, box, None, 0L)

      val payout = NOTIONAL * 8 / 100
      step.recorded shouldBe false
      step.next.lit shouldBe BALANCE + DFHelpers.ACC_DEPOSIT
      outputs(step).map(_.getValue) shouldBe
        Vector(acc.box.value, ForwardsTransactions.PayoutErg, ForwardsTransactions.PayoutErg, 2000000L)
      Seq(1, 2, 3).map(tokensOf(_, step)) shouldBe Seq(Seq(lit(M + payout)), Seq(lit(M - payout)), Seq(lit(LIT)))
      Seq(1, 2, 3).map(treeOf(_, step)) shouldBe
        Seq(contractOf(maker(ctx)), contractOf(taker(ctx)), wallet.contract).map(_.ergoTreeHex)
    }
  }

  property("settle: recording in the same spend takes both rewards, and the balance rises by 1 LIT net") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accHolding(ctx, fixtureEpoch - 1, 8, ended)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), ended, 1)
      val step = ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, box,
        ForwardsTransactions.recordingAt(ctx), 0L)
      step.recorded shouldBe true
      step.next.lastEpoch shouldBe fixtureEpoch
      step.next.lit shouldBe BALANCE + DFHelpers.ACC_DEPOSIT - DFHelpers.RECORD_REWARD
      tokensOf(3, step) shouldBe Seq(lit(2 * LIT))
    }
  }

  property("settle: a broadcast pays its fee out of the position's own ERG") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accHolding(ctx, fixtureEpoch, 8, ended)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), ended, 1)
      val step = ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, box, None, Parameters.MinFee)
      outputs(step).map(_.getValue) shouldBe Vector(acc.box.value, ForwardsTransactions.PayoutErg,
        ForwardsTransactions.PayoutErg, UTXO.MIN_CHANGE, Parameters.MinFee)
      treeOf(4, step) shouldBe Contract.FEE.ergoTreeHex
    }
  }

  property("settle: when the long side is owed nothing, the reward takes output 1 and the short side output 2") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accHolding(ctx, fixtureEpoch, -30, ended)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), ended, 1)
      val step = ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, box, None, 0L)
      outputs(step) should have size 3
      treeOf(1, step) shouldBe wallet.contract.ergoTreeHex
      tokensOf(2, step) shouldBe Seq(lit(2 * M))
      treeOf(2, step) shouldBe contractOf(taker(ctx)).ergoTreeHex
    }
  }

  property("settle: a window whose first epoch has left the ring refunds both margins") {
    atLastBlock() { (ctx, wallet) =>
      val gone = terms(fixtureEpoch - RING_SIZE, 8)
      val acc = accHolding(ctx, fixtureEpoch, 15, gone)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), gone, 1)
      val step = ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, box, None, 0L)
      Seq(1, 2).map(tokensOf(_, step)) shouldBe Seq(Seq(lit(M)), Seq(lit(M)))
    }
  }

  property("settle: refuses a window the accumulator has not recorded to its end") {
    atLastBlock() { (ctx, wallet) =>
      val acc = accHolding(ctx, fixtureEpoch - 1, 8)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), terms(fixtureEpoch - 7, 8), 1)
      an[IllegalArgumentException] should be thrownBy
        ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, box, None, 0L)
    }
  }

  // ─── a chained run ────────────────────────────────────────────────────────

  property("settleAll: the first settlement carries the recording, and the next spends its successor") {
    atLastBlock() { (ctx, wallet) =>
      val second = terms(fixtureEpoch - 30, 4)
      val acc = accHolding(ctx, fixtureEpoch - 1, 8, ended, second)
      val run = ForwardsTransactions.settleAll(ctx, wallet, contracts, acc,
        Seq(positionAt(ctx, maker(ctx), taker(ctx), ended, 1), positionAt(ctx, taker(ctx), maker(ctx), second, 2)),
        ForwardsTransactions.recordingAt(ctx), 0L, 10, 1.minute.fromNow)

      run.steps.map(_.recorded) shouldBe Vector(true, false)
      run.recordingLeft shouldBe None
      run.unbuildable shouldBe empty
      run.steps(1).spent.box.id.toString shouldBe run.steps(0).next.box.id.toString
      run.steps(1).signed.getSignedInputs.get(0).getId.toString shouldBe run.steps(0).next.box.id.toString
      run.last.get.next.lit shouldBe BALANCE + 2 * DFHelpers.ACC_DEPOSIT - DFHelpers.RECORD_REWARD
    }
  }

  property("settleAll: a window ending at the epoch being recorded settles only in the spend that records it") {
    atLastBlock() { (ctx, wallet) =>
      val current = terms(fixtureEpoch - 7, 8)
      val acc = accHolding(ctx, fixtureEpoch - 1, 8, current)
      val position = Seq(positionAt(ctx, maker(ctx), taker(ctx), current, 1))
      val unrecorded = ForwardsTransactions.settleAll(ctx, wallet, contracts, acc, position, None, 0L, 10, 1.minute.fromNow)
      unrecorded.steps shouldBe empty
      unrecorded.unbuildable shouldBe empty
      val recorded = ForwardsTransactions.settleAll(ctx, wallet, contracts, acc, position,
        ForwardsTransactions.recordingAt(ctx), 0L, 10, 1.minute.fromNow)
      recorded.steps.map(_.recorded) shouldBe Vector(true)
    }
  }

  property("settleAll: a position the recording would refund settles first, without it, and is paid out") {
    atLastBlock() { (ctx, wallet) =>
      // 127 epochs after its start: whole now, overwritten once the tip's epoch is recorded
      val fragile = terms(fixtureEpoch - RING_SIZE, 8)
      val acc = accHolding(ctx, fixtureEpoch - 1, 15, ended, fragile)
      val run = ForwardsTransactions.settleAll(ctx, wallet, contracts, acc,
        Seq(positionAt(ctx, maker(ctx), taker(ctx), ended, 1), positionAt(ctx, maker(ctx), taker(ctx), fragile, 2)),
        ForwardsTransactions.recordingAt(ctx), 0L, 10, 1.minute.fromNow)

      run.steps.map(_.position.get.terms) shouldBe Vector(fragile, ended)
      run.steps.map(_.recorded) shouldBe Vector(false, true)
      tokensOf(1, run.steps.head) shouldBe Seq(lit(M + NOTIONAL * 15 / 100))
    }
  }

  property("settleAll: an unbuildable position is passed over, and the next settles on the same accumulator") {
    atLastBlock() { (ctx, wallet) =>
      // An 80-key OR as the long key makes its payout box 2,800 bytes, more than 0.001 ERG covers
      val g = CryptoConstants.dlogGroup
      val manyKeys = COR((1 to 80).map(i => ProveDlog(g.exponentiate(g.generator, BigInteger.valueOf(1000L + i)))))
      val acc = accHolding(ctx, fixtureEpoch, 8, ended)
      val badBox = nodeBox(ctx, UTXO(contracts.position, POSITION_ERG, Seq(lit(DFMath.positionLit(NOTIONAL))),
        DifficultyForwardContracts.positionRegisters(manyKeys, keyOf(taker(ctx)), ended)), 1, "cd" * 32)
      val bad = DFBoxes.position(badBox, contracts).get -> DFBoxes.input(badBox, ctx).get
      val good = positionAt(ctx, maker(ctx), taker(ctx), terms(fixtureEpoch - 19, 8), 2)
      val run = ForwardsTransactions.settleAll(ctx, wallet, contracts, acc, Seq(bad, good), None, 0L, 10,
        1.minute.fromNow)

      run.unbuildable shouldBe Vector(badBox.boxId)
      run.steps.map(_.position.get.boxId) shouldBe Vector(good._1.boxId)
      run.steps.head.spent.box.id.toString shouldBe acc.box.id.toString
    }
  }

  property("settleAll: stops at its limit and says positions were left") {
    atLastBlock() { (ctx, wallet) =>
      val second = terms(fixtureEpoch - 30, 4)
      val acc = accHolding(ctx, fixtureEpoch, 8, ended, second)
      val run = ForwardsTransactions.settleAll(ctx, wallet, contracts, acc,
        Seq(positionAt(ctx, maker(ctx), taker(ctx), ended, 1), positionAt(ctx, maker(ctx), taker(ctx), second, 2)),
        None, 0L, 1, 1.minute.fromNow)
      run.steps should have size 1
      run.cutShort shouldBe true
      withClue("the oldest window goes first: ") { run.steps.head.position.get.terms shouldBe second }
    }
  }
}
