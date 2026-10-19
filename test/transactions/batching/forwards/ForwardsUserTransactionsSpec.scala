package transactions.batching.forwards

import forwards.contracts.{DFTerms, DifficultyForwardContracts}
import forwards.{DFHelpers, DFMath}
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction}
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._

/**
 * The transactions a user asks for: creating, filling and cancelling an offer, and settling by hand. Every
 * spend of an offer or a position is signed, which runs DF_Offer or DF_Position on what the client built, so a
 * transaction coming back at all is the contract accepting it. What is asserted is the client's own choices:
 * which side each key lands on, what the wallet is asked for, where leftovers go, and when a fill is refused
 * because the node would refuse it.
 */
class ForwardsUserTransactionsSpec extends AnyPropSpec with ForwardsFixtures {

  import _root_.contracts.specs.forwards.DFSpecBase._

  private val BALANCE: Long = 100L * LIT
  private def lit(amount: Long): Token = Token(litId, amount)

  private def atLastBlock[A](lastBlock: Int = fixtureLastBlock)(f: (BlockchainContext, NodeWallet) => A): A = {
    val client = lastBlockClient(lastBlock)
    val wallet = walletOf(client)
    client.execute(ctx => f(ctx, wallet))
  }

  /** A wallet box holding `litHeld` LIT beside `value` nanoERG, as the engine reserves for funding. */
  private def walletLit(ctx: BlockchainContext, wallet: NodeWallet, litHeld: Long, value: Long = SLACK,
                        index: Int = 7): InputUTXO =
    inputAt(UTXO(wallet.contract, value, Seq(lit(litHeld))), ctx, index, "ef" * 32)

  /** An offer as the node reports it, made by `DFSpecBase`'s maker unless registers say otherwise. */
  private def offerAt(ctx: BlockchainContext, utxo: UTXO, index: Int = 0): (DFOffer, InputUTXO) = {
    val box = nodeBox(ctx, utxo, index, "aa" * 32)
    DFBoxes.offer(box, contracts).get -> DFBoxes.input(box, ctx).get
  }

  /** An offer whose maker is the client's own wallet. */
  private def ownOffer(ctx: BlockchainContext, wallet: NodeWallet, t: DFTerms, expiry: Int,
                       value: Long = ForwardsUserTransactions.OfferErg): UTXO =
    UTXO(contracts.offer, value, Seq(lit(DFMath.sideLit(t.notional))),
      DifficultyForwardContracts.offerRegisters(wallet.p2pk.getPublicKey, true, t, expiry))

  private def outputs(signed: SignedTransaction) = signed.getOutputsToSpend.asScala.toVector
  private def tokensOf(signed: SignedTransaction, index: Int): Seq[Token] =
    outputs(signed)(index).getTokens.asScala.map(Token.ergo).toSeq
  private def treeOf(signed: SignedTransaction, index: Int): String = Contract(outputs(signed)(index).getErgoTree).ergoTreeHex

  /** Builds `plan` on `funding` and signs it with the client's wallet, as the API does. */
  private def signed(wallet: NodeWallet, plan: DFPlan, funding: InputUTXO*): SignedTransaction =
    wallet.sign(plan.build(funding))

  private val window: DFTerms = terms(fixtureEpoch + DFHelpers.MIN_LEAD, 8)

  // ─── timing ───────────────────────────────────────────────────────────────

  property("timing: the node reads the tip's epoch and a signature here the epoch of the block below it") {
    atLastBlock() { (ctx, _) =>
      ForwardsUserTransactions.timing(ctx) shouldBe
        Some(DFFillTiming(ctx.getHeight, DFMath.epochOf(ctx.getHeight), DFMath.epochOf(ctx.getHeight - 1)))
    }
    // The last block closes epoch 964, so the tip opens 965
    atLastBlock(965 * 128) { (ctx, _) =>
      ForwardsUserTransactions.timing(ctx) shouldBe Some(DFFillTiming(965 * 128 + 1, 965, 964))
    }
  }

  property("defaults: an offer expires at the end of the epoch the next block is in, and fills stop in time") {
    ForwardsUserTransactions.defaultExpiry(965 * 128 + 1) shouldBe 966 * 128
    // At an epoch's last block the next block opens a new epoch, so the offer lasts that whole epoch
    ForwardsUserTransactions.defaultExpiry(965 * 128) shouldBe 966 * 128
    // A window starting at 970 can be filled while the block before the fill is in epoch 968 or earlier
    ForwardsUserTransactions.lastFillHeight(970) shouldBe 969 * 128 + 1
    DFMath.epochOf(ForwardsUserTransactions.lastFillHeight(970) - 1) shouldBe 970 - DFHelpers.MIN_LEAD
  }

  // ─── reading an offer ─────────────────────────────────────────────────────

  property("offer: reads its terms, side, expiry and maker, and pays the maker to its key's own script") {
    withCtx { ctx =>
      val (offer, _) = offerAt(ctx, offerUTXO(ctx, makerLong = false, window, 500))
      offer.terms shouldBe window
      offer.makerLong shouldBe false
      offer.expiry shouldBe 500
      offer.lit shouldBe DFMath.sideLit(NOTIONAL)
      offer.funded shouldBe true
      offer.maker shouldBe contractOf(maker(ctx))
    }
  }

  property("offer: an offer short of the maker's side still reads, as unfunded, so its maker can cancel it") {
    withCtx { ctx =>
      val (offer, _) = offerAt(ctx, offerUTXO(ctx, makerLong = true, window, 500, lit = DFMath.sideLit(NOTIONAL) - 1))
      offer.funded shouldBe false
      offerAt(ctx, offerUTXO(ctx, true, window, 500).copy(tokens = Seq.empty))._1.lit shouldBe 0L
    }
  }

  property("offer: refuses another script, terms no fill accepts, or registers of the wrong type") {
    withCtx { ctx =>
      def read(utxo: UTXO) = DFBoxes.offer(nodeBox(ctx, utxo, 0), contracts)
      val genuine = offerUTXO(ctx, makerLong = true, window, 500)
      read(offerUTXO(ctx, true, window, 500, contract = contracts.position)) shouldBe None
      Seq(terms(fixtureEpoch, 0), terms(fixtureEpoch, DFHelpers.MAX_WINDOW + 1), terms(fixtureEpoch, 8, strike = 0L),
        terms(fixtureEpoch, 8, notional = 4L)).foreach(t => withClue(s"$t: ")(read(offerUTXO(ctx, true, t, 500)) shouldBe None))
      read(genuine.withReg(1, org.ergoplatform.appkit.ErgoValue.of(1))) shouldBe None
      read(genuine.withReg(5, org.ergoplatform.appkit.ErgoValue.of(500L))) shouldBe None
      read(genuine.withReg(0, org.ergoplatform.appkit.ErgoValue.of(contractOf(maker(ctx)).propBytes))) shouldBe None
    }
  }

  // ─── create ───────────────────────────────────────────────────────────────

  property("create: an offer at the wallet's key holding its side, which a taker can then fill") {
    atLastBlock() { (ctx, wallet) =>
      val expiry = ForwardsUserTransactions.defaultExpiry(ctx.getHeight)
      val plan = ForwardsUserTransactions.create(ctx, wallet, contracts, makerLong = true, window, expiry)
      plan.value shouldBe ForwardsUserTransactions.OfferErg + Parameters.MinFee + UTXO.MIN_CHANGE
      plan.tokens shouldBe Seq(lit(DFMath.sideLit(NOTIONAL)))

      val created = signed(wallet, plan, walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL) + 3 * LIT))
      outputs(created)(0).getValue shouldBe ForwardsUserTransactions.OfferErg
      val (offer, offerBox) = offerAt(ctx, InputUTXO(outputs(created)(0)).toUTXO)
      offer shouldBe offer.copy(maker = wallet.contract, makerLong = true, terms = window, expiry = expiry,
        lit = DFMath.sideLit(NOTIONAL))
      withClue("the LIT the offer does not take comes back as change: ") {
        outputs(created).drop(1).map(_.getTokens.asScala.map(Token.ergo).toSeq).filter(_.nonEmpty) shouldBe
          Vector(Seq(lit(3 * LIT)))
      }

      // A fill of it signs, which runs DF_Offer on the offer the client created
      val fill = ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, offerBox)
      noException should be thrownBy signed(wallet, fill, walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL), index = 8))
    }
  }

  property("create: refuses terms no fill accepts, and an expiry no block after the offer's could fill") {
    atLastBlock() { (ctx, wallet) =>
      an[IllegalArgumentException] should be thrownBy
        ForwardsUserTransactions.create(ctx, wallet, contracts, true, terms(fixtureEpoch + 2, 0), ctx.getHeight + 50)
      an[IllegalArgumentException] should be thrownBy
        ForwardsUserTransactions.create(ctx, wallet, contracts, true, window, ctx.getHeight + 1)
    }
  }

  // ─── fill ─────────────────────────────────────────────────────────────────

  property("fill: the maker on its side, this wallet on the other, both sides' LIT, and the fee") {
    Seq(true, false).foreach { makerLong =>
      atLastBlock() { (ctx, wallet) =>
        val (offer, box) = offerAt(ctx, offerUTXO(ctx, makerLong, window, ctx.getHeight + 50))
        val plan = ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box)
        plan.tokens shouldBe Seq(lit(DFMath.sideLit(NOTIONAL)))
        plan.value shouldBe DFHelpers.POSITION_ERG + Parameters.MinFee - ForwardsUserTransactions.OfferErg +
          UTXO.MIN_CHANGE

        val tx = signed(wallet, plan, walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL)))
        val position = DFBoxes.position(nodeBox(ctx, InputUTXO(outputs(tx)(0)).toUTXO, 0), contracts).get
        withClue(s"maker long $makerLong: ") {
          position.terms shouldBe window
          position.lit shouldBe DFMath.positionLit(NOTIONAL)
          (if (makerLong) position.long else position.short) shouldBe contractOf(maker(ctx))
          (if (makerLong) position.short else position.long) shouldBe wallet.contract
          outputs(tx).map(_.getValue) should contain(Parameters.MinFee)
        }
      }
    }
  }

  property("fill: LIT an offer holds beyond the maker's side goes to this wallet, which then adds less") {
    atLastBlock() { (ctx, wallet) =>
      val extra = 2 * LIT
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, window, ctx.getHeight + 50,
        lit = DFMath.sideLit(NOTIONAL) + extra))
      val plan = ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box)
      plan.tokens shouldBe Seq(lit(DFMath.sideLit(NOTIONAL) - extra))
      val tx = signed(wallet, plan, walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL)))
      tokensOf(tx, 0) shouldBe Seq(lit(DFMath.positionLit(NOTIONAL)))
      outputs(tx).indices.drop(1).filter(treeOf(tx, _) == wallet.contract.ergoTreeHex)
        .flatMap(tokensOf(tx, _)) shouldBe Seq(lit(extra))
    }
  }

  property("fill: refuses an offer holding less than the maker's side, before asking the wallet for anything") {
    atLastBlock() { (ctx, wallet) =>
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, window, ctx.getHeight + 50,
        lit = DFMath.sideLit(NOTIONAL) - 1))
      val refused = the[IllegalArgumentException] thrownBy ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box)
      refused.getMessage should include("short of the maker's")
    }
  }

  property("fill: refuses an offer whose expiry is the tip, which signs but which the next block cannot include") {
    atLastBlock() { (ctx, wallet) =>
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, window, ctx.getHeight))
      the[IllegalArgumentException].thrownBy(ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box))
        .getMessage should include("expired")
      val (open, openBox) = offerAt(ctx, offerUTXO(ctx, true, window, ctx.getHeight + 1))
      noException should be thrownBy signed(wallet, ForwardsUserTransactions.fill(ctx, wallet, contracts, open, openBox),
        walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL)))
    }
  }

  property("fill: at an epoch's first block, refuses a window the signature accepts and the node would refuse") {
    // The tip opens epoch 965; a signature here reads 964. A window at 966 is 2 after 964 but 1 after 965.
    atLastBlock(965 * 128) { (ctx, wallet) =>
      val doomed = terms(966, 8)
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, doomed, ctx.getHeight + 50))
      the[IllegalArgumentException].thrownBy(ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box))
        .getMessage should include("epoch 966")
      withClue("built by hand, the same fill signs, so only the client's check stands between it and the node: ") {
        val position = UTXO(contracts.position, DFHelpers.POSITION_ERG, Seq(lit(DFMath.positionLit(NOTIONAL))),
          DifficultyForwardContracts.positionRegisters(keyOf(maker(ctx)), wallet.p2pk.getPublicKey, doomed))
        noException should be thrownBy wallet.sign(TxBuilder(ctx)
          .setInputs(box, walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL))).setOutputs(position)
          .buildTx(Parameters.MinFee, wallet.p2pk))
      }
    }
  }

  property("fill: at an epoch's first block, waits a block for a window the node accepts and a signature refuses") {
    atLastBlock(965 * 128) { (ctx, wallet) =>
      val farthest = terms(965 + DFHelpers.MAX_LEAD, 8)
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, farthest, ctx.getHeight + 50))
      the[IllegalArgumentException].thrownBy(ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box))
        .getMessage should include("next block")
      val (inside, insideBox) = offerAt(ctx, offerUTXO(ctx, true, terms(967, 8), ctx.getHeight + 50))
      noException should be thrownBy signed(wallet,
        ForwardsUserTransactions.fill(ctx, wallet, contracts, inside, insideBox),
        walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL)))
    }
    atLastBlock(965 * 128 + 1) { (ctx, wallet) =>
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, terms(965 + DFHelpers.MAX_LEAD, 8), ctx.getHeight + 50))
      noException should be thrownBy signed(wallet, ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box),
        walletLit(ctx, wallet, DFMath.sideLit(NOTIONAL)))
    }
  }

  // ─── cancel ───────────────────────────────────────────────────────────────

  property("cancel: returns the offer to its maker, the fee out of its own ERG, with no wallet input") {
    atLastBlock() { (ctx, wallet) =>
      val (offer, box) = offerAt(ctx, ownOffer(ctx, wallet, window, ctx.getHeight + 50))
      val plan = ForwardsUserTransactions.cancel(ctx, wallet, offer, box)
      plan.value shouldBe 0L
      plan.tokens shouldBe empty
      val tx = signed(wallet, plan)
      treeOf(tx, 0) shouldBe wallet.contract.ergoTreeHex
      outputs(tx)(0).getValue shouldBe ForwardsUserTransactions.OfferErg - Parameters.MinFee
      tokensOf(tx, 0) shouldBe Seq(lit(DFMath.sideLit(NOTIONAL)))
    }
  }

  property("cancel: an offer too small to pay its own fee is topped up from the wallet and returned whole") {
    atLastBlock() { (ctx, wallet) =>
      val (offer, box) = offerAt(ctx, ownOffer(ctx, wallet, window, ctx.getHeight + 50, value = UTXO.MIN_CHANGE))
      val plan = ForwardsUserTransactions.cancel(ctx, wallet, offer, box)
      plan.value shouldBe Parameters.MinFee + UTXO.MIN_CHANGE
      val tx = signed(wallet, plan, walletBox(ctx, wallet, plan.value))
      outputs(tx)(0).getValue shouldBe UTXO.MIN_CHANGE
      tokensOf(tx, 0) shouldBe Seq(lit(DFMath.sideLit(NOTIONAL)))
    }
  }

  property("cancel: an offer another key made cannot be signed for") {
    atLastBlock() { (ctx, wallet) =>
      val (offer, box) = offerAt(ctx, offerUTXO(ctx, true, window, ctx.getHeight + 50))
      // The prover fails an unprovable tree with an AssertionError, not an exception
      a[Throwable] should be thrownBy signed(wallet, ForwardsUserTransactions.cancel(ctx, wallet, offer, box))
    }
  }

  // ─── settle by hand ───────────────────────────────────────────────────────

  private def ringOf(lastEpoch: Int)(f: Int => Long): IndexedSeq[Long] =
    (0 until RING_SIZE).map(i => f(lastEpoch - (lastEpoch - i) % RING_SIZE))

  private def accHolding(ctx: BlockchainContext, lastEpoch: Int, percent: Long, windows: DFTerms*): DFAccState = {
    def inside(e: Int) = windows.exists(t => e >= t.windowStart && e < t.windowStart + t.windowLength)
    accAt(ctx, AccState(lastEpoch, ringOf(lastEpoch)(e => if (inside(e)) STRIKE + STRIKE * percent / 100 else STRIKE),
      BALANCE))
  }

  property("settle: records the epoch due, pays the fee from the position, and the quote is what it pays") {
    atLastBlock() { (ctx, wallet) =>
      val ended = terms(fixtureEpoch - 20, 8)
      val acc = accHolding(ctx, fixtureEpoch - 1, 8, ended)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), ended, 1)
      val quote = ForwardsUserTransactions.settlement(acc, position, ForwardsTransactions.recordingAt(ctx)).get
      val step = ForwardsUserTransactions.settle(ctx, wallet, contracts, acc, position, box)

      step.recorded shouldBe true
      quote.records shouldBe true
      quote.refund shouldBe false
      outputs(step.signed).map(_.getValue) should contain(Parameters.MinFee)
      Seq(1, 2, 3).map(tokensOf(step.signed, _)) shouldBe
        Seq(Seq(lit(quote.owedLong)), Seq(lit(quote.owedShort)), Seq(lit(quote.rewardLit)))
      quote.rewardLit shouldBe DFHelpers.SETTLE_REWARD + DFHelpers.RECORD_REWARD
    }
  }

  property("settle: leaves the recording out where it would turn the settlement into a refund") {
    atLastBlock() { (ctx, wallet) =>
      // 127 epochs after its start: whole now, overwritten once the tip's epoch is recorded
      val fragile = terms(fixtureEpoch - RING_SIZE, 8)
      val acc = accHolding(ctx, fixtureEpoch - 1, 15, fragile)
      val (position, box) = positionAt(ctx, maker(ctx), taker(ctx), fragile, 1)
      val quote = ForwardsUserTransactions.settlement(acc, position, ForwardsTransactions.recordingAt(ctx)).get
      quote shouldBe quote.copy(records = false, refund = false)
      val step = ForwardsUserTransactions.settle(ctx, wallet, contracts, acc, position, box)
      step.recorded shouldBe false
      tokensOf(step.signed, 1) shouldBe Seq(lit(NOTIONAL / 5 + NOTIONAL * 15 / 100))
    }
  }

  property("settlement: none while the window's last epoch is still unrecorded, a refund once it has left") {
    atLastBlock() { (ctx, _) =>
      val open = terms(fixtureEpoch - 3, 8)
      val acc = accHolding(ctx, fixtureEpoch - 1, 0)
      ForwardsUserTransactions.settlement(acc, positionAt(ctx, maker(ctx), taker(ctx), open, 1)._1,
        ForwardsTransactions.recordingAt(ctx)) shouldBe None
      val gone = terms(fixtureEpoch - RING_SIZE - 2, 8)
      val refund = ForwardsUserTransactions.settlement(acc, positionAt(ctx, maker(ctx), taker(ctx), gone, 2)._1, None).get
      refund shouldBe DFSettlement(records = false, refund = true, NOTIONAL / 5, NOTIONAL / 5, DFHelpers.SETTLE_REWARD)
    }
  }
}
