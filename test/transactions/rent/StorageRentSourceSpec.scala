package transactions.rent

import configs.{CandidateSourceConfig, RentConfig}
import node.model.{NodeAsset, NodeBox}
import org.ergoplatform.appkit.{NetworkType, Parameters}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import transactions.candidate.CandidateBudget
import lfsm.LFSMHelpers
import work.lithos.mutations.{Contract, MainnetEip27Constants}

/**
 * What the scan keeps and what a sweep takes, decided without touching a node.
 *
 * Both halves of the source are pure functions over what a block read returned, so they are driven
 * directly here. The actor around them owns a cursor and a timer and nothing else.
 */
class StorageRentSourceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def id(seed: String): String = (seed * 64).take(64)

  /** Nothing excluded, so these properties are about age and re-emission alone. */
  private val noProtocolBoxes = ProtocolBoxes(Set.empty, Set.empty)

  private def protocolBoxes: ProtocolBoxes =
    nodeContext.getClient.execute(ProtocolBoxes.apply)

  private def output(seed: String, creationHeight: Int,
                     assets: Seq[NodeAsset] = Seq.empty): NodeBox =
    NodeBox(id(seed), "ab" * 32, 100L * Parameters.OneErg, 0, creationHeight,
      Contract.SIGMA_FALSE.ergoTreeHex, assets)

  // ─── what the walk keeps ──────────────────────────────────────────────────

  /**
   * A box is due once its creation height is `StoragePeriod` behind the tip, which is what the
   * threshold already is — so the walk never compares ages itself.
   */
  "The walk" should "keep boxes at or under the threshold and no others" in {
    val boxes = Seq(output("a", 500), output("b", 1000), output("c", 1001))
    val (found, blocked) = StorageRent.sortByAge(boxes, 1000, NetworkType.MAINNET, noProtocolBoxes)

    found shouldBe Set(id("a"), id("b"))
    blocked shouldBe empty
  }

  /** They are eligible and will stay eligible, so they are set aside rather than discarded. */
  it should "set re-emission boxes apart rather than drop them" in {
    val reEmission = NodeAsset(MainnetEip27Constants.TokenId, 3L)
    val boxes = Seq(output("a", 500), output("b", 500, Seq(reEmission)))
    val (found, blocked) = StorageRent.sortByAge(boxes, 1000, NetworkType.MAINNET, noProtocolBoxes)

    found shouldBe Set(id("a"))
    blocked shouldBe Set(id("b"))
  }

  /** EIP-27 is a mainnet rule, and the rest of this client treats it as one. */
  it should "not set anything apart off mainnet" in {
    val reEmission = NodeAsset(MainnetEip27Constants.TokenId, 3L)
    val (found, blocked) =
      StorageRent.sortByAge(Seq(output("b", 500, Seq(reEmission))), 1000, NetworkType.TESTNET,
        noProtocolBoxes)

    found shouldBe Set(id("b"))
    blocked shouldBe empty
  }

  it should "keep nothing from a block whose boxes are all too young" in {
    StorageRent.sortByAge(Seq(output("a", 2000)), 1000, NetworkType.MAINNET,
      noProtocolBoxes)._1 shouldBe empty
  }

  // ─── this protocol's own boxes ────────────────────────────────────────────
  //
  // Age is all Ergo's rule cares about, so a singleton that has sat still long enough is as
  // collectable as anyone's forgotten change. Taking one costs protocol state rather than earning
  // ERG, and on a network with a short period every one of them ages out.

  "The walk" should "leave a box at one of this protocol's own scripts alone" in {
    nodeContext.getClient.execute { ctx =>
      val contracts = transactions.ProtocolContracts(ctx)
      Seq("holding" -> contracts.holding, "minerDictionary" -> contracts.minerDictionary,
        "collateral" -> contracts.collateral, "emission" -> contracts.emission,
        "gate" -> contracts.gate).foreach { case (name, contract) =>
        val box = NodeBox(id("a"), "ab" * 32, 100L * Parameters.OneErg, 0, 500,
          contract.ergoTreeHex)
        withClue(s"a $name box must never be collected: ") {
          StorageRent.sortByAge(Seq(box), 1000, NetworkType.MAINNET, protocolBoxes)._1 shouldBe empty
        }
      }
    }
  }

  /** A singleton resting at an ordinary key between spends is still the thing it names. */
  it should "leave a box carrying a protocol singleton alone, wherever it sits" in {
    nodeContext.getClient.execute { ctx =>
      Seq("collateral token" -> LFSMHelpers.COLLAT_TOKEN,
        "emission NFT" -> LFSMHelpers.EMISSION_NFT,
        "dictionary token" -> LFSMHelpers.getMDToken(ctx.getNetworkType),
        "fraud-proof token" -> LFSMHelpers.getFPToken(ctx.getNetworkType)).foreach {
        case (name, token) =>
          val box = output("a", 500, Seq(NodeAsset(token.toString, 1L)))
          withClue(s"a box carrying the $name must never be collected: ") {
            StorageRent.sortByAge(Seq(box), 1000, NetworkType.MAINNET, protocolBoxes)._1 shouldBe empty
          }
      }
    }
  }

  /**
   * LIT is the protocol's currency rather than one of its boxes, held by anyone who has been paid.
   * Excluding it would put ordinary wallets out of reach for no protocol reason.
   */
  it should "still collect an ordinary box holding LIT" in {
    val box = output("a", 500, Seq(NodeAsset(LFSMHelpers.LIT_ID.toString, 5L)))
    StorageRent.sortByAge(Seq(box), 1000, NetworkType.MAINNET, protocolBoxes)._1 shouldBe Set(id("a"))
  }

  /** A protocol box is dropped outright rather than set aside, since it is never going to be due. */
  it should "drop a protocol box rather than defer it" in {
    nodeContext.getClient.execute { ctx =>
      val box = NodeBox(id("a"), "ab" * 32, 100L * Parameters.OneErg, 0, 500,
        transactions.ProtocolContracts(ctx).holding.ergoTreeHex,
        Seq(NodeAsset(MainnetEip27Constants.TokenId, 3L)))
      val (found, blocked) = StorageRent.sortByAge(Seq(box), 1000, NetworkType.MAINNET, protocolBoxes)
      found shouldBe empty
      blocked shouldBe empty
    }
  }

  // ─── what a sweep takes ───────────────────────────────────────────────────

  private def candidates(count: Int, value: Long): Seq[RentCandidate] =
    nodeContext.getClient.execute { ctx =>
      val params = ctx.getDataSource.getParameters
      (0 until count).map { i =>
        val box = work.lithos.mutations.UTXO(Contract.SIGMA_FALSE, value + i * Parameters.OneErg)
          .setCreationHeight(0)
          .toInput(ctx, org.ergoplatform.sdk.ErgoId.create("ab" * 32), i.toShort)
        RentCandidate(box, StorageRent.decide(box.value, box.bytes.length, params).get)
      }
    }

  private def params = nodeContext.getClient.execute(_.getDataSource.getParameters)

  "A sweep" should "take everything when the budget affords it" in {
    val all = candidates(10, 100L * Parameters.OneErg)
    StorageRent.fitting(all, CandidateBudget(Long.MaxValue, Long.MaxValue), params) should have size 10
  }

  /**
   * Sized before it is built rather than built and dropped. A sweep over its share would cost a
   * whole assembly to find out, and the boxes left behind are still collectable next block.
   */
  it should "stop at the cost it is allowed" in {
    val all = candidates(50, 100L * Parameters.OneErg)
    val fitted = StorageRent.fitting(all, CandidateBudget(Long.MaxValue, 30000L), params)

    fitted.size should (be > 0 and be < 50)
    withClue("one more box would have to fit inside the same budget: ") {
      val perBox = params.getInputCost.toLong + 50L + params.getOutputCost.toLong
      (10000L + params.getOutputCost.toLong + (fitted.size + 1) * perBox) should be > 30000L
    }
  }

  it should "stop at the bytes it is allowed" in {
    val all = candidates(50, 100L * Parameters.OneErg)
    StorageRent.fitting(all, CandidateBudget(300L, Long.MaxValue), params).size should
      (be > 0 and be < 50)
  }

  /** The point of ranking: a truncated sweep should carry the boxes that pay most. */
  it should "take the boxes that yield the most when it cannot take them all" in {
    val all = candidates(20, 100L * Parameters.OneErg)
    val fitted = StorageRent.fitting(all, CandidateBudget(Long.MaxValue, 20000L), params)

    fitted should not be empty
    val taken = fitted.map(_.proceedsErg).min
    val left = all.filterNot(c => fitted.exists(_.box.id == c.box.id)).map(_.proceedsErg)
    withClue("nothing left behind may be worth more than what was taken: ") {
      left.foreach(_ should be <= taken)
    }
  }

  it should "take nothing when the budget cannot afford one box" in {
    StorageRent.fitting(candidates(5, 100L * Parameters.OneErg),
      CandidateBudget(10L, 10L), params) shouldBe empty
  }

  it should "never exceed the transaction ceiling" in {
    val all = candidates(3, 100L * Parameters.OneErg)
    StorageRent.fitting(all, CandidateBudget(Long.MaxValue, Long.MaxValue), params).size should
      be <= StorageRent.MaxBoxes
  }

  // ─── config ───────────────────────────────────────────────────────────────

  /**
   * Off, and starting well past genesis. The early chain was swept years ago, so walking it costs
   * node reads for nothing.
   */
  "The default" should "leave the source off and the walk short of the chain start" in {
    RentConfig.Default.startHeight should be > 0
    CandidateSourceConfig.Default.copy(enabled = false).enabled shouldBe false
    configs.CandidateConfig.Default.sources(CandidateSourceConfig.Rent).enabled shouldBe false
  }
}
