package transactions.rent

import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import transactions.candidate.CapitalOrigin
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

  private val dueAt: Int = StorageRent.StoragePeriod + 1

  /** Ergo's own re-emission token, which is what makes a box uncollectable however old it is. */
  private val reEmissionToken: Token =
    Token(ErgoId.create(MainnetEip27Constants.TokenId), 4L)

  private def expired(ctx: BlockchainContext, value: Long, index: Int,
                      tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    UTXO(stranger, value, tokens, Seq(ErgoValue.of(42))).setCreationHeight(0)
      .toInput(ctx, ErgoId.create("ab" * 32), index.toShort)

  private def candidate(ctx: BlockchainContext, box: InputUTXO): RentCandidate =
    RentCandidate(box, StorageRent.plan(box, 0, dueAt, ctx.getDataSource.getParameters,
      ctx.getNetworkType).get)

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

  // ─── what the plan refuses ────────────────────────────────────────────────

  "A box one block short of its period" should "not be planned at all" in {
    withCtx { ctx =>
      val box = expired(ctx, 100L * Parameters.OneErg, 0)
      StorageRent.plan(box, 0, StorageRent.StoragePeriod - 1,
        ctx.getDataSource.getParameters, ctx.getNetworkType) shouldBe None
    }
  }

  /**
   * The fee is payable but the successor could not legally hold what is left, so there is no
   * recreation to name and the collection cannot be built.
   */
  "A box whose recreation would fall below the consensus minimum" should "not be planned" in {
    withCtx { ctx =>
      val params = ctx.getDataSource.getParameters
      // A box's value is VLQ-encoded, so its length — and with it its fee — depends on the value
      // being measured. Two passes settle on the size the assertion is actually about.
      def leastCollectable(guess: Long): Long = {
        val probe = expired(ctx, guess, 0)
        StorageRent.storageFee(probe, params) + params.getMinValuePerByte.toLong * probe.bytes.length
      }
      val exact = leastCollectable(leastCollectable(100L * Parameters.OneErg))
      val lowest = expired(ctx, exact, 0)
      val under = expired(ctx, exact - 1L, 0)
      withClue("the two boxes must serialize to one length for the boundary to mean anything: ") {
        under.bytes.length shouldEqual lowest.bytes.length
      }

      StorageRent.plan(lowest, 0, dueAt, params, ctx.getNetworkType) shouldBe
        Some(RentAction.Collect(StorageRent.storageFee(lowest, params)))
      // One nanoERG under, and the same box has no successor it could legally leave behind.
      StorageRent.plan(under, 0, dueAt, params, ctx.getNetworkType) shouldBe None
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
        ctx.getNetworkType) shouldBe None
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
        ctx.getNetworkType) shouldBe Some(RentAction.Claim)
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
