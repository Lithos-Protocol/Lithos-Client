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
      Seq("collateral token" -> LFSMHelpers.COLLAT_TOKEN_MAINNET,
        "emission NFT" -> LFSMHelpers.EMISSION_NFT_MAINNET,
        "emission config NFT" -> LFSMHelpers.getEmConfigNft(ctx.getNetworkType),
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
    val box = output("a", 500, Seq(NodeAsset(LFSMHelpers.LIT_ID_MAINNET.toString, 5L)))
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

  // ─── when a box is offered ────────────────────────────────────────────────

  private val period = StorageRent.StoragePeriod

  /** The block being built is `tip + 1`, so its due boxes sit one block above `tip - period`. */
  "The walk" should "reach the boxes due in the next block, with no lookahead" in {
    val created = 900000
    StorageRentSource.walkCeiling(tip = created + period - 1, lookahead = 0) shouldBe created
  }

  /**
   * Wallets date a box a few blocks before it lands, and other collectors take it the block it is
   * due. A walk reading only what is already due meets it after they have.
   */
  it should "meet a box included after its creation height before the box falls due" in {
    val created = 900000
    val included = created + 3
    StorageRentSource.walkCeiling(tip = created + period - 1, RentConfig.Default.lookaheadBlocks) should
      be >= included
  }

  /** Placed from the tip, so a client started years from now begins as close to the frontier. */
  it should "start a fixed distance behind the due frontier, wherever the tip is" in {
    Seq(1899183, 2500000).foreach { tip =>
      val frontier = StorageRentSource.walkCeiling(tip, 0)
      StorageRentSource.walkStart(tip, RentConfig.Default.startBehindBlocks) shouldBe
        frontier - RentConfig.Default.startBehindBlocks
    }
  }

  /** A chain younger than one storage period has nothing due, and must not walk heights below 1. */
  it should "read nothing on a chain younger than one storage period" in {
    val tip = 300000
    val start = StorageRentSource.walkStart(tip, RentConfig.Default.startBehindBlocks)
    start shouldBe 1
    start should be > StorageRentSource.walkCeiling(tip, RentConfig.Default.lookaheadBlocks)
  }

  "A held box" should "be offered from the block its creation height makes it due, not before" in {
    val held = Map(id("a") -> 900000, id("b") -> 900001)
    StorageRentSource.dueAt(held, 900000 + period - 1) shouldBe empty
    StorageRentSource.dueAt(held, 900000 + period) shouldBe Seq(id("a"))
    StorageRentSource.dueAt(held, 900001 + period).toSet shouldBe Set(id("a"), id("b"))
  }

  it should "be offered oldest first" in {
    val held = Map(id("c") -> 900002, id("a") -> 900000, id("b") -> 900001)
    StorageRentSource.dueAt(held, 900002 + period) shouldBe Seq(id("a"), id("b"), id("c"))
  }

  // ─── reading due boxes back ───────────────────────────────────────────────

  private def dueIds(n: Int): Seq[String] = (0 until n).map(i => f"$i%064x")
  private def boxFor(boxId: String): NodeBox = NodeBox(boxId, "00" * 32, Parameters.OneErg, 0, 1, "0008cd" + "02" * 33)

  "Reading due boxes back" should "read in pages, forget what a page left out, and keep reading past a failed page" in {
    var asked = Vector.empty[Int]
    val failing = dueIds(250).slice(100, 200).toSet
    val read = StorageRentSource.readDue(dueIds(250), page => {
      asked :+= page.size
      if (page.exists(failing.contains)) scala.util.Failure(new RuntimeException("response too large"))
      else scala.util.Success(page.filterNot(_ == dueIds(250).head).map(boxFor))
    })
    asked shouldBe Vector(100, 100, 50)
    read.live.map(_.boxId).toSet shouldBe (dueIds(250).toSet -- failing - dueIds(250).head)
    read.gone shouldBe Set(dueIds(250).head)
    read.unread shouldBe 100
    read.failure.get should include("response too large")
  }

  it should "stop after two failed pages, and read no more than one sweep can take" in {
    var calls = 0
    val down = StorageRentSource.readDue(dueIds(1000), _ => { calls += 1; scala.util.Failure(new RuntimeException("down")) })
    (calls, down.live, down.gone, down.unread, down.failedIds.size) shouldBe ((2, Seq.empty, Set.empty, 1000, 200))
    var read = 0
    val many = StorageRentSource.readDue(dueIds(StorageRent.MaxBoxes + 500), page => {
      read += page.size
      scala.util.Success(page.map(boxFor))
    })
    (read, many.live.size, many.unread, many.failedIds) shouldBe ((StorageRent.MaxBoxes, StorageRent.MaxBoxes, 500, Set.empty))
  }

  "Due selection" should "rotate past the first 1,000 live boxes across builds so later collectible boxes are not starved" in {
    val total = StorageRent.MaxBoxes + 300
    val byAge = scala.collection.immutable.TreeSet((0 until total).map(i => (900000 + i / 10) -> f"$i%064x"): _*)
    val height = 900000 + total + period

    val (first, cursor1) = StorageRentSource.selectDue(byAge, height, None, Map.empty)
    first should have size StorageRent.MaxBoxes
    cursor1 shouldBe Some(first.last)

    // Next build continues where the first stopped, reaching the 300 newer boxes before wrapping around
    val (second, cursor2) = StorageRentSource.selectDue(byAge, height, cursor1, Map.empty)
    second should have size StorageRent.MaxBoxes
    second.take(300).map(_._2) shouldBe byAge.toVector.drop(StorageRent.MaxBoxes).map(_._2)
    (first.map(_._2).toSet ++ second.map(_._2).toSet) shouldBe byAge.map(_._2).toSet
    cursor2 shouldBe Some(second.last)
  }

  it should "defer boxes whose page read failed while other due boxes are available, and retry once backoff expires" in {
    val byAge = scala.collection.immutable.TreeSet((0 until 5).map(i => (900000 + i) -> f"$i%064x"): _*)
    val height = 900010 + period
    val backoff = Map(f"${0}%064x" -> (height + 2), f"${1}%064x" -> (height + 2))

    val (skipped, _) = StorageRentSource.selectDue(byAge, height, None, backoff, limit = 3)
    skipped.map(_._2) shouldBe Vector(f"${2}%064x", f"${3}%064x", f"${4}%064x")

    // Once backoff expires, the failed ids re-enter rotation
    val (recovered, _) = StorageRentSource.selectDue(byAge, height + 2, None, backoff, limit = 3)
    recovered.map(_._2) shouldBe Vector(f"${0}%064x", f"${1}%064x", f"${2}%064x")

    // If every due box is in cooldown, fall back immediately instead of returning empty
    val allBackedOff = byAge.map(_._2 -> (height + 2)).toMap
    val (fallback, _) = StorageRentSource.selectDue(byAge, height, None, allBackedOff, limit = 3)
    fallback should have size 3
  }

  // ─── what a sweep takes ───────────────────────────────────────────────────

  private def candidates(count: Int, value: Long): Seq[RentCandidate] =
    nodeContext.getClient.execute { ctx =>
      val params = ctx.getDataSource.getParameters
      (0 until count).map { i =>
        val box = work.lithos.mutations.UTXO(Contract.SIGMA_FALSE, value + i * Parameters.OneErg)
          .setCreationHeight(0)
          .toInput(ctx, org.ergoplatform.sdk.ErgoId.create("ab" * 32), i.toShort)
        RentCandidate(box, StorageRent.decide(box.value, box.bytes.length,
          StorageRent.successorBytes(box, support.RentRule.dueHeight), params).get)
      }
    }

  private def params = nodeContext.getClient.execute(_.getDataSource.getParameters)

  /** What a proceeds output at this miner's key has to hold, as the source computes it. */
  private def floor: Long = StorageRent.proceedsFloor(wallet.contract, support.RentRule.dueHeight, params)

  "A sweep" should "take everything when the budget affords it" in {
    val all = candidates(10, 100L * Parameters.OneErg)
    StorageRent.fitting(all, CandidateBudget(Long.MaxValue, Long.MaxValue), params, floor) should have size 10
  }

  /**
   * Sized before it is built rather than built and dropped. A sweep over its share would cost a
   * whole assembly to find out, and the boxes left behind are still collectable next block.
   */
  it should "stop at the cost it is allowed" in {
    val all = candidates(50, 100L * Parameters.OneErg)
    val fitted = StorageRent.fitting(all, CandidateBudget(Long.MaxValue, 30000L), params, floor)

    fitted.size should (be > 0 and be < 50)
    withClue("one more box would have to fit inside the same budget: ") {
      val perBox = params.getInputCost.toLong + 50L + params.getOutputCost.toLong
      (10000L + params.getOutputCost.toLong + (fitted.size + 1) * perBox) should be > 30000L
    }
  }

  it should "stop at the bytes it is allowed" in {
    val all = candidates(50, 100L * Parameters.OneErg)
    StorageRent.fitting(all, CandidateBudget(300L, Long.MaxValue), params, floor).size should
      (be > 0 and be < 50)
  }

  /** The point of ranking: a truncated sweep should carry the boxes that pay most. */
  it should "take the boxes that yield the most when it cannot take them all" in {
    val all = candidates(20, 100L * Parameters.OneErg)
    val fitted = StorageRent.fitting(all, CandidateBudget(Long.MaxValue, 20000L), params, floor)

    fitted should not be empty
    val taken = fitted.map(_.proceedsErg).min
    val left = all.filterNot(c => fitted.exists(_.box.id == c.box.id)).map(_.proceedsErg)
    withClue("nothing left behind may be worth more than what was taken: ") {
      left.foreach(_ should be <= taken)
    }
  }

  it should "take nothing when the budget cannot afford one box" in {
    StorageRent.fitting(candidates(5, 100L * Parameters.OneErg),
      CandidateBudget(10L, 10L), params, floor) shouldBe empty
  }

  it should "never exceed the transaction ceiling" in {
    val all = candidates(3, 100L * Parameters.OneErg)
    StorageRent.fitting(all, CandidateBudget(Long.MaxValue, Long.MaxValue), params, floor).size should
      be <= StorageRent.MaxBoxes
  }

  // ─── a pass over failing reads ────────────────────────────────────────────

  private def keepAll(boxes: Seq[NodeBox]): (Set[String], Set[String]) = boxes.map(_.boxId).toSet -> Set.empty

  private def blockOf(height: Int): Seq[NodeBox] = Seq(output(height.toString.last.toString, height))

  private def everyBoxUnspent(ids: Seq[String]): scala.util.Try[Set[String]] = scala.util.Success(ids.toSet)

  /** Read again from the height it stopped at, which is when the boxes behind the failure show up. */
  "A pass" should "stop at a failed block read and read that height on the next pass" in {
    val failing = StorageRentSource.walk(10, 15,
      h => if (h == 12) scala.util.Failure(new RuntimeException("timeout")) else scala.util.Success(blockOf(h)),
      everyBoxUnspent, keepAll)
    failing.nextHeight shouldBe 12
    failing.found.values.toSet shouldBe Set(10, 11)
    failing.stoppedBy should not be empty

    val next = StorageRentSource.walk(failing.nextHeight, 15, h => scala.util.Success(blockOf(h)),
      everyBoxUnspent, keepAll)
    withClue("the boxes of the height that failed must be found when it is read again: ") {
      next.found.values.toSet shouldBe Set(12, 13, 14)
    }
  }

  it should "stop at a failed unspent read rather than drop that block's boxes" in {
    val pass = StorageRentSource.walk(10, 15, h => scala.util.Success(blockOf(h)),
      ids => if (ids.exists(_.startsWith("1" * 2))) scala.util.Failure(new RuntimeException("timeout"))
        else everyBoxUnspent(ids),
      keepAll)
    pass.nextHeight shouldBe 11
    pass.found.values.toSet shouldBe Set(10)
  }

  /** Bounded, so one block that can never be read costs its own boxes and not every later block's. */
  "A height that keeps failing" should "count its passes, and a different height starts over" in {
    val stalls = Iterator.iterate(StorageRentSource.stalledAgain(None, 12))(s =>
      StorageRentSource.stalledAgain(Some(s), 12))
    stalls.drop(StorageRentSource.MaxReadsPerHeight - 1).next() shouldBe (12 -> StorageRentSource.MaxReadsPerHeight)
    StorageRentSource.stalledAgain(Some(12 -> 4), 13) shouldBe (13 -> 1)
  }

  "A pass" should "read every height when nothing fails" in {
    val pass = StorageRentSource.walk(10, 15, h => scala.util.Success(blockOf(h)), everyBoxUnspent, keepAll)
    pass.nextHeight shouldBe 15
    pass.stoppedBy shouldBe empty
  }

  // ─── config ───────────────────────────────────────────────────────────────

  /**
   * On, and starting a short way behind the frontier. Collectors sweep boxes as they fall due, so
   * a long walk back costs node reads for nothing.
   */
  "The default" should "leave the source on and start within a few days of the frontier" in {
    RentConfig.Default.startBehindBlocks should (be >= 0 and be <= 7 * 720)
    configs.CandidateConfig.Default.sources(CandidateSourceConfig.Rent).enabled shouldBe true
  }
}
