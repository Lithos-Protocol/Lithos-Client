package transactions.rent

import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import transactions.candidate.{CandidateBudget, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, MainnetEip27Constants, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * The sweep this client actually builds, checked against Ergo's rule input by input.
 *
 * A rent collection is assembled rather than signed, so nothing rejects a malformed one on the way
 * out — the only thing that would is the node, in the block. These properties put every input
 * through `ErgoInterpreter` instead, which is that same rule.
 */
class StorageRentBuilderSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = nodeContext.getClient.execute(f)

  /** A script this client holds no secret for, so only the rent rule can ever spend the box. */
  private val stranger: Contract = Contract.SIGMA_FALSE

  private val dueAt: Int = support.RentRule.dueHeight

  /** Ergo's own re-emission token, which is what makes a box uncollectable however old it is. */
  private val reEmissionToken: Token =
    Token(ErgoId.create(MainnetEip27Constants.TokenId), 4L)

  private def expired(ctx: BlockchainContext, value: Long, index: Int,
                      tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    UTXO(stranger, value, tokens, Seq(ErgoValue.of(42))).setCreationHeight(0)
      .toInput(ctx, ErgoId.create("ab" * 32), index.toShort)

  private def protocol(ctx: BlockchainContext): ProtocolBoxes = ProtocolBoxes(ctx)

  private def candidate(ctx: BlockchainContext, box: InputUTXO): RentCandidate =
    RentCandidate(box, StorageRent.plan(box, 0, dueAt, ctx.getDataSource.getParameters,
      ctx.getNetworkType, protocol(ctx)).get)

  private def ergoBoxOf(box: InputUTXO): ErgoBox =
    box.input.asInstanceOf[InputBoxImpl].getErgoBox

  /** The assembled transaction, recovered from the JSON-carrying candidate the builder returns. */
  private def swept(ctx: BlockchainContext, boxes: Seq[InputUTXO]) = {
    val candidates = boxes.map(candidate(ctx, _))
    val bundle = StorageRent.build(ctx, wallet, candidates, dueAt, useTrueProp = false).get
    (bundle, candidates)
  }

  private def verifyAll(ctx: BlockchainContext, boxes: Seq[InputUTXO]): Boolean = {
    val candidates = boxes.map(candidate(ctx, _))
    val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)
    support.RentRule.accepts(tx, boxes.map(ergoBoxOf).toIndexedSeq, dueAt,
      support.RentRule.paramsFrom(ctx))
  }

  /** A meaningless fee would make every assertion below pass for the wrong reason. */
  "The fixture" should "price storage at a nonzero factor" in {
    withCtx(ctx => ctx.getDataSource.getParameters.getStorageFeeFactor should be > 0)
  }

  // ─── the funded branch ────────────────────────────────────────────────────

  "A sweep of funded boxes" should "be accepted for every input" in {
    withCtx { ctx =>
      val boxes = (0 until 4).map(i => expired(ctx, 100L * Parameters.OneErg, i))
      verifyAll(ctx, boxes) shouldBe true
    }
  }

  it should "realize exactly the fees and nothing more" in {
    withCtx { ctx =>
      val boxes = (0 until 3).map(i => expired(ctx, 100L * Parameters.OneErg, i))
      val params = ctx.getDataSource.getParameters
      val fees = boxes.map(StorageRent.storageFee(_, params)).sum

      val (bundle, _) = swept(ctx, boxes)
      bundle.capital should have size 1
      bundle.capital.head.origin shouldBe CapitalOrigin.StorageRent
      bundle.capital.head.value shouldEqual fees
    }
  }

  it should "conserve value across the transaction" in {
    withCtx { ctx =>
      val boxes = (0 until 3).map(i => expired(ctx, 100L * Parameters.OneErg, i))
      val candidates = boxes.map(candidate(ctx, _))
      val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)

      tx.outputs.map(_.value).sum shouldEqual boxes.map(_.value).sum
    }
  }

  // ─── the underfunded branch ───────────────────────────────────────────────

  /** Below its own fee the rule short-circuits, so the box goes whole — tokens with it. */
  "A sweep of underfunded boxes" should "be accepted and take their tokens" in {
    withCtx { ctx =>
      val token = Token(ErgoId.create("cd" * 32), 500L)
      val boxes = (0 until 3).map(i => expired(ctx, 1000000L, i, Seq(token)))
      boxes.foreach(b => candidate(ctx, b).action shouldBe RentAction.Claim)
      verifyAll(ctx, boxes) shouldBe true

      val candidates = boxes.map(candidate(ctx, _))
      val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)
      // One output for the tokens, at this miner's own key, holding all three boxes' worth.
      val tokenBox = tx.outputs.find(_.additionalTokens.toArray.nonEmpty).get
      tokenBox.ergoTree.bytesHex shouldEqual wallet.contract.ergoTreeHex
      tokenBox.additionalTokens.toArray.map(_._2).sum shouldEqual 1500L
    }
  }

  /**
   * Claimed tokens stay at this miner's key: the capital the holding top-up spends is ERG only, and
   * a funded box keeps its own tokens on its recreation. Three claims and one residue also force
   * the merge, so the folded capital is covered too.
   */
  it should "keep their tokens out of the capital the top-up spends" in {
    withCtx { ctx =>
      val claimed = Token(ErgoId.create("cd" * 32), 500L)
      val kept = Token(ErgoId.create("ce" * 32), 9L)
      val boxes = Seq(
        expired(ctx, 100L * Parameters.OneErg, 0, Seq(kept)),
        expired(ctx, 1000000L, 1, Seq(claimed)),
        expired(ctx, 1000000L, 2, Seq(claimed)),
        expired(ctx, 1000000L, 3, Seq(claimed)))
      verifyAll(ctx, boxes) shouldBe true

      val (bundle, candidates) = swept(ctx, boxes)
      val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)
      def hex(bytes: Array[Byte]): String = org.bouncycastle.util.encoders.Hex.toHexString(bytes)
      def totals(tokens: Seq[(String, Long)]): Map[String, Long] =
        tokens.groupBy(_._1).map { case (id, ts) => id -> ts.map(_._2).sum }

      withClue("nothing burned: ") {
        totals(tx.outputs.flatMap(_.additionalTokens.toArray.map(t => hex(t._1.toArray) -> t._2))) shouldEqual
          totals(boxes.flatMap(_.tokens.map(t => t.id.toString -> t.amount)))
      }
      val recreation = tx.outputs.find(_.ergoTree.bytesHex == stranger.ergoTreeHex).get
      recreation.additionalTokens.toArray.map(t => hex(t._1.toArray) -> t._2).toSeq shouldEqual
        Seq(kept.id.toString -> kept.amount)
      val residue = tx.outputs.find(o => o.additionalTokens.toArray.exists(t => hex(t._1.toArray) == claimed.id.toString)).get
      residue.ergoTree.bytesHex shouldEqual wallet.contract.ergoTreeHex

      bundle.members should have size 2
      bundle.members(1).inputIds should not contain hex(residue.id)
      bundle.capital should have size 1
      bundle.capital.head.tokens shouldBe empty
      bundle.capital.head.value shouldEqual candidates.map(_.proceedsErg).sum - UTXO.MIN_CHANGE
    }
  }

  // ─── both at once ─────────────────────────────────────────────────────────

  /**
   * The index each input names is what makes a mixed sweep work: a funded one has to name its own
   * recreation, while an underfunded one may name anything because its branch never reads it.
   */
  "A sweep mixing both branches" should "be accepted for every input" in {
    withCtx { ctx =>
      val token = Token(ErgoId.create("cd" * 32), 7L)
      val boxes = Seq(
        expired(ctx, 100L * Parameters.OneErg, 0),
        expired(ctx, 1000000L, 1, Seq(token)),
        expired(ctx, 200L * Parameters.OneErg, 2),
        expired(ctx, 2000000L, 3))

      verifyAll(ctx, boxes) shouldBe true
    }
  }

  it should "carry one recreation per funded box and nothing for the rest" in {
    withCtx { ctx =>
      val boxes = Seq(
        expired(ctx, 100L * Parameters.OneErg, 0),
        expired(ctx, 1000000L, 1),
        expired(ctx, 200L * Parameters.OneErg, 2))
      val candidates = boxes.map(candidate(ctx, _))
      val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)

      // Collection, then the two recreations. The claimed box leaves no output of its own.
      tx.outputs should have size 3
      tx.outputs.tail.map(_.ergoTree.bytesHex).toSet shouldEqual Set(stranger.ergoTreeHex)
    }
  }

  /**
   * The shape a live sweep actually takes: a handful of recreations among a crowd of claims, with
   * tokens among the claims so the residue output lands after the recreations. Index assignment is
   * what this is about — a recreation naming the wrong output is checked against a box it has
   * nothing to do with, and the node reports it only as a refusal at some input number.
   */
  "A sweep the shape a live one takes" should "be accepted for every input" in {
    withCtx { ctx =>
      val token = Token(ErgoId.create("cd" * 32), 9L)
      val funded = (0 until 5).map(i => expired(ctx, (100L + i) * Parameters.OneErg, i))
      val claimed = (5 until 12).map(i => expired(ctx, 1000000L, i))
      val withTokens = (12 until 15).map(i => expired(ctx, 1000000L, i, Seq(token)))
      val boxes = funded ++ claimed ++ withTokens

      val candidates = boxes.map(candidate(ctx, _))
      candidates.count(_.recreates) shouldEqual 5
      val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)
      withClue("ten claims, one of them naming the residue: nine proceeds, five recreations, the residue: ") {
        tx.outputs should have size 15
      }
      verifyAll(ctx, boxes) shouldBe true
    }
  }

  // ─── the node's own rule, run before a sweep goes out ─────────────────────

  /** A Collect taking one nanoERG past its fee: the shape any mis-modelled box has to the node. */
  private def misplanned(ctx: BlockchainContext, index: Int): RentCandidate = {
    val box = expired(ctx, (500L + index) * Parameters.OneErg, index)
    RentCandidate(box, RentAction.Collect(StorageRent.storageFee(box, ctx.getDataSource.getParameters) + 1L))
  }

  "The node's rent rule" should "accept every input of a correct sweep" in {
    withCtx { ctx =>
      val boxes = Seq(expired(ctx, 100L * Parameters.OneErg, 0), expired(ctx, 1000000L, 1),
        expired(ctx, 1000001L, 2, Seq(Token(ErgoId.create("cd" * 32), 5L))))
      val tx = StorageRent.assembled(ctx, wallet, boxes.map(candidate(ctx, _)), dueAt, useTrueProp = false)
      RentVerifier.verdicts(tx, boxes.map(ergoBoxOf).toIndexedSeq, dueAt,
        ctx.getDataSource.getParameters).toSet shouldEqual Set(RentVerdict.Accepted)
    }
  }

  /** The node refuses a whole sweep for one bad input, so the bad one has to come out first. */
  "A collection holding a box the client mis-planned" should "leave it out, report it and send the rest" in {
    withCtx { ctx =>
      val good = Seq(expired(ctx, 100L * Parameters.OneErg, 0), expired(ctx, 200L * Parameters.OneErg, 1),
        expired(ctx, 1000000L, 2), expired(ctx, 1000001L, 3))
      val bad = misplanned(ctx, 4)
      val collection = StorageRent.collect(ctx, wallet, good.map(candidate(ctx, _)) :+ bad, dueAt,
        useTrueProp = false)

      collection.refused.map(_.box.id) shouldEqual Seq(bad.box.id)
      val sweep = collection.bundle.getOrElse(fail("the four good boxes were not offered")).members.head
      sweep.inputIds should have size 4
      sweep.inputIds should not contain bad.box.id.toString
      verifyAll(ctx, good) shouldBe true
    }
  }

  /** Every input refused points at this builder rather than at the boxes, so none are evicted. */
  "A collection the rule refuses whole" should "offer nothing and report nothing" in {
    withCtx { ctx =>
      val collection = StorageRent.collect(ctx, wallet, (0 until 3).map(misplanned(ctx, _)), dueAt,
        useTrueProp = false)
      collection.bundle shouldBe None
      collection.refused shouldBe empty
    }
  }

  // ─── one output per input ─────────────────────────────────────────────────

  /**
   * The node refuses a transaction in which two inputs carry the same variable 127. Claims never
   * have their named output checked, so nothing but this rule stops them sharing one.
   */
  "A sweep with several claims" should "name a distinct output for every input" in {
    withCtx { ctx =>
      val boxes = (0 until 4).map(i => expired(ctx, 1000000L + i, i)) ++
        (4 until 6).map(i => expired(ctx, 100L * Parameters.OneErg, i))
      val tx = StorageRent.assembled(ctx, wallet, boxes.map(candidate(ctx, _)), dueAt, useTrueProp = false)
      val named = tx.inputs.map(_.spendingProof.extension
        .values(org.ergoplatform.wallet.protocol.Constants.StorageIndexVarId).value.asInstanceOf[Short].toInt)

      named.distinct should have size boxes.size.toLong
      named.foreach(i => i should (be >= 0 and be < tx.outputs.size))
      support.RentRule.namesDistinctOutputs(tx) shouldBe true
      verifyAll(ctx, boxes) shouldBe true
    }
  }

  /** The proceeds outputs exist only to be named, so the second transaction folds them back. */
  it should "fold its proceeds outputs into one box that is the capital" in {
    withCtx { ctx =>
      val boxes = (0 until 5).map(i => expired(ctx, 1000000L + i, i))
      val (bundle, candidates) = swept(ctx, boxes)
      val tx = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)
      val proceedsIds = tx.outputs.take(5).map(o => org.bouncycastle.util.encoders.Hex.toHexString(o.id)).toSet

      bundle.members should have size 2
      bundle.members(1).inputIds shouldEqual proceedsIds
      bundle.capital should have size 1
      bundle.capital.head.parentTxId shouldEqual bundle.members(1).id
      bundle.capital.head.value shouldEqual boxes.map(_.value).sum
      bundle.capital.head.box.contract.ergoTreeHex shouldEqual wallet.contract.ergoTreeHex
    }
  }

  it should "fold them at TrueProp when that setting is on" in {
    withCtx { ctx =>
      val boxes = (0 until 3).map(i => expired(ctx, 1000000L + i, i))
      val bundle = StorageRent.build(ctx, wallet, boxes.map(candidate(ctx, _)), dueAt, useTrueProp = true).get

      bundle.members should have size 2
      bundle.capital.head.box.contract.ergoTreeHex shouldEqual Contract.SIGMA_TRUE.ergoTreeHex
      bundle.capital.head.value shouldEqual boxes.map(_.value).sum
    }
  }

  /**
   * One residue output used to carry every token the claims brought in, and past about 85 distinct
   * ids it held less than the dust minimum for its own size, which lost the whole sweep.
   */
  "A sweep claiming many distinct tokens" should "split them across residues the node accepts" in {
    withCtx { ctx =>
      val tokens = (0 until 90).map(i => Token(ErgoId.create(f"$i%064x"), 1L + i))
      val boxes = tokens.zipWithIndex.map { case (token, i) => expired(ctx, 1000000L, i, Seq(token)) }
      val tx = StorageRent.assembled(ctx, wallet, boxes.map(candidate(ctx, _)), dueAt, useTrueProp = false)
      val residues = tx.outputs.filter(_.additionalTokens.length > 0)

      residues should have size 5
      residues.foreach(_.additionalTokens.length should be <= StorageRent.TokensPerResidue)
      residues.flatMap(_.additionalTokens.toArray).map(_._2).sum shouldEqual tokens.map(_.amount).sum
      verifyAll(ctx, boxes) shouldBe true
    }
  }

  /**
   * A 0.001 ERG claim bringing a new token nets nothing on its own, because the residue holding the
   * token costs as much. Twenty share one residue, so the set pays even though no box alone does.
   */
  "A sweep of token-carrying dust" should "be fitted as a set rather than stopping at its first box" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      val floor = StorageRent.proceedsFloor(wallet.contract, dueAt, params)
      val boxes = (0 until 30).map(i => expired(ctx, UTXO.MIN_CHANGE, i, Seq(Token(ErgoId.create(f"$i%064x"), 1L))))
      val all = boxes.map(candidate(ctx, _))

      StorageRent.fitting(all.take(1), CandidateBudget.Unbounded, params, floor) shouldBe empty
      val fitted = StorageRent.fitting(all, CandidateBudget.Unbounded, params, floor)
      fitted should have size 30
      StorageRent.build(ctx, wallet, fitted, dueAt, useTrueProp = false) should not be empty
      verifyAll(ctx, boxes) shouldBe true
    }
  }

  /**
   * The source admits a bundle against its budget using what the built members report, so a
   * fitting that underestimates loses the whole collection rather than its last few boxes.
   */
  "A fitted sweep" should "never build larger than the budget it was fitted to" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      val floor = StorageRent.proceedsFloor(wallet.contract, dueAt, params)
      val token = Token(ErgoId.create("cd" * 32), 3L)
      val boxes = (0 until 40).map(i => expired(ctx, 1000000L + i, i)) ++
        (40 until 50).map(i => expired(ctx, 1000000L, i, Seq(token))) ++
        (50 until 60).map(i => expired(ctx, (100L + i) * Parameters.OneErg, i))
      val all = boxes.map(candidate(ctx, _))

      Seq(CandidateBudget(Long.MaxValue, Long.MaxValue), CandidateBudget(4000L, Long.MaxValue),
        CandidateBudget(Long.MaxValue, 120000L), CandidateBudget(6000L, 150000L)).foreach { budget =>
        val fitted = StorageRent.fitting(all, budget, params, floor)
        fitted should not be empty
        val bundle = StorageRent.build(ctx, wallet, fitted, dueAt, useTrueProp = false).get
        val bytes = bundle.members.map(_.sizeBytes.toLong).sum
        val cost = bundle.members.map(_.cost).sum
        withClue(s"${fitted.size} boxes in $budget built to $bytes bytes and $cost cost: ") {
          bytes should be <= budget.maxBytes
          cost should be <= budget.maxCost
        }
        if (bundle.members.size == 2) {
          val merge = bundle.members(1)
          val perInput = (merge.cost - 10000L - params.getOutputCost) / merge.inputIds.size
          println(f"[rent] merge of ${merge.inputIds.size}%3d inputs: ${merge.cost}%7d cost, " +
            f"$perInput%5d per input, ${merge.sizeBytes}%6d bytes")
          withClue("the per-input merge estimate has to cover what one really costs: ") {
            perInput should be <= params.getInputCost.toLong + StorageRent.MergeInputCost
          }
        }
      }
    }
  }

  /** Ordering is the builder's own choice, so it must hold whatever order the sweep arrives in. */
  it should "be accepted whatever order the candidates arrive in" in {
    withCtx { ctx =>
      val token = Token(ErgoId.create("cd" * 32), 9L)
      val boxes = Seq(
        expired(ctx, 1000000L, 0, Seq(token)),
        expired(ctx, 100L * Parameters.OneErg, 1),
        expired(ctx, 1000000L, 2),
        expired(ctx, 200L * Parameters.OneErg, 3),
        expired(ctx, 1000000L, 4, Seq(token)),
        expired(ctx, 300L * Parameters.OneErg, 5))

      verifyAll(ctx, boxes) shouldBe true
      verifyAll(ctx, boxes.reverse) shouldBe true
    }
  }

  // ─── what the plan refuses ────────────────────────────────────────────────

  "A box one block short of its period" should "not be planned at all" in {
    withCtx { ctx =>
      val box = expired(ctx, 100L * Parameters.OneErg, 0)
      StorageRent.plan(box, 0, StorageRent.StoragePeriod - 1,
        ctx.getDataSource.getParameters, ctx.getNetworkType, protocol(ctx)) shouldBe None
    }
  }

  /**
   * Just over its fee, `value - fee` is under the consensus minimum, but the rule only asks the
   * recreation for at least `value - fee`. So it is recreated at the minimum and the rest is taken.
   */
  "A box whose fee would leave it under the consensus minimum" should "be collected down to it" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      // A box's value is VLQ-encoded, so its length — and with it its fee — depends on the value
      // being measured. Two passes settle on the size the assertion is actually about.
      def justOverFee(guess: Long): Long = StorageRent.storageFee(expired(ctx, guess, 0), params) + 1000L
      val value = justOverFee(justOverFee(100L * Parameters.OneErg))
      val box = expired(ctx, value, 0)
      val minimum = params.getMinValuePerByte.toLong * StorageRent.successorBytes(box, dueAt)
      withClue("the box has to sit inside the gap for this to mean anything: ") {
        (value - StorageRent.storageFee(box, params)) should be < minimum
      }

      StorageRent.plan(box, 0, dueAt, params, ctx.getNetworkType, protocol(ctx)) shouldBe
        Some(RentAction.Collect(value - minimum))
      verifyAll(ctx, Seq(box, expired(ctx, 1000000L, 1), expired(ctx, 1000001L, 2))) shouldBe true
    }
  }

  it should "still give up its whole fee when that leaves it over the minimum" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      val box = expired(ctx, 100L * Parameters.OneErg, 0)
      StorageRent.plan(box, 0, dueAt, params, ctx.getNetworkType, protocol(ctx)) shouldBe
        Some(RentAction.Collect(StorageRent.storageFee(box, params)))
    }
  }

  // ─── EIP-27 ───────────────────────────────────────────────────────────────

  /**
   * Two consensus rules that cannot both hold. Past the EIP-27 activation height no output of a
   * transaction spending re-emission tokens may carry them, while a rent recreation has to preserve
   * the token register exactly — so the collection is refused however it is built.
   */
  "A box carrying the re-emission token" should "not be planned" in {
    withCtx { ctx =>
      val box = expired(ctx, 100L * Parameters.OneErg, 0, Seq(reEmissionToken))

      StorageRent.blockedByReEmission(box, ctx.getNetworkType) shouldBe true
      StorageRent.plan(box, 0, dueAt, ctx.getDataSource.getParameters,
        ctx.getNetworkType, protocol(ctx)) shouldBe None
    }
  }

  /** A candidate can be built without going through the plan, so the sweep refuses one too. */
  it should "be refused by the builder as well" in {
    withCtx { ctx =>
      val blocked = RentCandidate(expired(ctx, 100L * Parameters.OneErg, 0, Seq(reEmissionToken)),
        RentAction.Claim)
      val ordinary = candidate(ctx, expired(ctx, 100L * Parameters.OneErg, 1))

      StorageRent.build(ctx, wallet, Seq(ordinary, blocked), dueAt, useTrueProp = false) shouldBe None
    }
  }

  /** An ordinary token is not the re-emission one, so nothing else is caught by the exclusion. */
  it should "not exclude a box carrying some other token" in {
    withCtx { ctx =>
      val box = expired(ctx, 1000000L, 0, Seq(Token(ErgoId.create("cd" * 32), 4L)))
      StorageRent.blockedByReEmission(box, ctx.getNetworkType) shouldBe false
      StorageRent.plan(box, 0, dueAt, ctx.getDataSource.getParameters,
        ctx.getNetworkType, protocol(ctx)) shouldBe Some(RentAction.Claim)
    }
  }

  // ─── the node's own arithmetic ────────────────────────────────────────────

  /**
   * `checkExpiredBox` computes the fee in `Int`, so a large enough box wraps it negative. The rule
   * then reads the box as funded however little it holds and demands a successor richer than the
   * box itself, which only a collector paying the difference could build.
   */
  "A box whose fee wraps negative in the node's arithmetic" should "not be planned" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      val boundary = Int.MaxValue / params.getStorageFeeFactor

      StorageRent.storageFee(boundary, params) should be > 0L
      StorageRent.storageFee(boundary + 1, params) should be < 0L
      withClue("the box that broke a live sweep was 1742 bytes: ") {
        StorageRent.storageFee(1742, params) should be < 0L
      }
      // Underfunded on unwrapped arithmetic, and the node would still refuse it.
      StorageRent.decide(10000000L, 1742, 1745, params) shouldBe None
    }
  }

  it should "still be planned when it sits under the boundary" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      val boundary = Int.MaxValue / params.getStorageFeeFactor
      StorageRent.decide(1000L, boundary, boundary + 3, params) shouldBe Some(RentAction.Claim)
    }
  }

  /**
   * Past `2^32` the product wraps positive again, so a box that large has a small fee the node
   * really charges. Both branches have to be priced on that wrapped fee to be accepted.
   */
  "A box whose fee wraps back positive" should "be collected on the node's own fee" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      val secondWrap = ((1L << 32) / params.getStorageFeeFactor + 1).toInt
      assume(secondWrap + 100 < org.ergoplatform.ErgoBox.MaxBoxSize,
        s"no box under the size ceiling wraps positive at factor ${params.getStorageFeeFactor}")

      def large(value: Long, index: Int): InputUTXO =
        UTXO(stranger, value, Seq.empty[Token], Seq(ErgoValue.of(Array.fill[Byte](secondWrap)(7))))
          .setCreationHeight(0).toInput(ctx, ErgoId.create("ab" * 32), index.toShort)
      val claimed = large(1500000L, 0)
      val funded = large(10L * Parameters.OneErg, 1)
      val fee = StorageRent.storageFee(funded, params)
      withClue(s"a ${funded.bytes.length} byte box has to land past the second wrap: ") {
        fee should (be > 0L and be < params.getStorageFeeFactor.toLong * funded.bytes.length)
      }

      candidate(ctx, claimed).action shouldBe RentAction.Claim
      candidate(ctx, funded).action shouldBe RentAction.Collect(fee)
      verifyAll(ctx, Seq(claimed, funded)) shouldBe true
    }
  }

  "A sweep over the input ceiling" should "be refused rather than truncated" in {
    withCtx { ctx =>
      val boxes = (0 to StorageRent.MaxBoxes).map(i => expired(ctx, 100L * Parameters.OneErg, i))
      StorageRent.build(ctx, wallet, boxes.map(candidate(ctx, _)), dueAt,
        useTrueProp = false) shouldBe None
    }
  }

  "An empty sweep" should "produce nothing" in {
    withCtx { ctx =>
      StorageRent.build(ctx, wallet, Seq.empty, dueAt, useTrueProp = false) shouldBe None
    }
  }

  // ─── where the proceeds sit ───────────────────────────────────────────────

  /** The collection output is an ordinary candidate intermediate, so the flag governs its script. */
  "The collection output" should "follow the TrueProp setting" in {
    withCtx { ctx =>
      val boxes = Seq(expired(ctx, 100L * Parameters.OneErg, 0))
      val candidates = boxes.map(candidate(ctx, _))
      val open = StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = true)

      open.outputs.head.ergoTree.bytesHex shouldEqual Contract.SIGMA_TRUE.ergoTreeHex
      StorageRent.assembled(ctx, wallet, candidates, dueAt, useTrueProp = false)
        .outputs.head.ergoTree.bytesHex shouldEqual wallet.contract.ergoTreeHex
    }
  }

  /** The entry names the collection output, which is what a later top-up has to spend. */
  it should "be what the bundle declares as capital" in {
    withCtx { ctx =>
      val boxes = Seq(expired(ctx, 100L * Parameters.OneErg, 0))
      val (bundle, _) = swept(ctx, boxes)
      val tx = StorageRent.assembled(ctx, wallet, boxes.map(candidate(ctx, _)), dueAt,
        useTrueProp = false)

      bundle.capital.head.outputId shouldEqual
        org.bouncycastle.util.encoders.Hex.toHexString(tx.outputs.head.id)
      bundle.members should have size 1
      bundle.members.head.kind shouldEqual StorageRent.Kind
    }
  }
}
