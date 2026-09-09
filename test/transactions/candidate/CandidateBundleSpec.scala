package transactions.candidate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting, Supersede}

class CandidateBundleSpec extends AnyFlatSpec with Matchers {
  private def tx(id: String) = CandidateTx(id, id, CandidateTx.Activate)

  "Candidate admission" should "drop an oversized dependency bundle without retaining its parent" in {
    val parent = tx("parent")
    val child = tx("child")
    val independent = tx("independent")
    CandidateBundle.select(Seq(CandidateBundle(Vector(parent, child)),
      CandidateBundle(Vector(independent))), 1) shouldBe Vector(independent)
  }

  it should "charge a shared ancestor once while retaining every admitted child" in {
    val parent = tx("parent")
    val first = tx("first")
    val second = tx("second")
    CandidateBundle.select(Seq(CandidateBundle(Vector(parent, first)),
      CandidateBundle(Vector(parent, second))), 3) shouldBe Vector(parent, first, second)
  }

  it should "reject a bundle that changes the body of an already selected transaction" in {
    val parent = tx("parent")
    val changed = parent.copy(json = "another body")
    CandidateBundle.select(Seq(CandidateBundle(Vector(parent)),
      CandidateBundle(Vector(changed, tx("child")))), 3) shouldBe Vector(parent)
  }

  /**
   * Sources build independently and none can see what another offered, so two of them can spend the
   * same box. The node refuses the whole candidate over that, taking the genesis package with it.
   */
  it should "drop a bundle spending a box already claimed by an admitted one" in {
    val box = "box-1"
    val mine = tx("mine").copy(inputIds = Set(box))
    val theirs = tx("theirs").copy(inputIds = Set(box))
    CandidateBundle.select(Seq(CandidateBundle(Vector(mine)),
      CandidateBundle(Vector(theirs))), 5) shouldBe Vector(mine)
  }

  it should "drop a bundle whose own members spend the same box" in {
    val box = "box-1"
    val first = tx("first").copy(inputIds = Set(box))
    val second = tx("second").copy(inputIds = Set(box))
    val ok = tx("ok").copy(inputIds = Set("box-2"))
    CandidateBundle.select(Seq(CandidateBundle(Vector(first, second)),
      CandidateBundle(Vector(ok))), 5) shouldBe Vector(ok)
  }

  /** A shared ancestor is one transaction, so its inputs must not read as a conflict with itself. */
  it should "admit a chain that reuses an already selected ancestor" in {
    val ancestor = tx("ancestor").copy(inputIds = Set("box-1"))
    val first = tx("first").copy(inputIds = Set("out-1"))
    val second = tx("second").copy(inputIds = Set("out-2"))
    CandidateBundle.select(Seq(CandidateBundle(Vector(ancestor, first)),
      CandidateBundle(Vector(ancestor, second))), 5) shouldBe Vector(ancestor, first, second)
  }

  /** Independent transactions carrying no input information are still admitted on count alone. */
  it should "admit bundles that declare no inputs" in {
    CandidateBundle.select(Seq(CandidateBundle(Vector(tx("a"))),
      CandidateBundle(Vector(tx("b")))), 5).map(_.id) shouldBe Vector("a", "b")
  }

  // ─── block budget ─────────────────────────────────────────────────────────

  private def sized(id: String, bytes: Int, cost: Long) =
    tx(id).copy(inputIds = Set(id + "-in"), sizeBytes = bytes, cost = cost)

  "The block budget" should "claim only its share of the block" in {
    CandidateBudget.of(1000L, 2000L) shouldBe CandidateBudget(500L, 1000L)
  }

  it should "stop admitting once the byte share is spent" in {
    val budget = CandidateBudget(100L, Long.MaxValue)
    CandidateBundle.select(Seq(CandidateBundle(Vector(sized("a", 60, 0))),
      CandidateBundle(Vector(sized("b", 60, 0))),
      CandidateBundle(Vector(sized("c", 30, 0)))), 10, budget).map(_.id) shouldBe Vector("a", "c")
  }

  it should "stop admitting once the cost share is spent" in {
    val budget = CandidateBudget(Long.MaxValue, 100L)
    CandidateBundle.select(Seq(CandidateBundle(Vector(sized("a", 0, 60))),
      CandidateBundle(Vector(sized("b", 0, 60)))), 10, budget).map(_.id) shouldBe Vector("a")
  }

  /** A bundle over budget contributes nothing, exactly as one over the count limit does. */
  it should "drop a whole dependency bundle rather than its affordable prefix" in {
    val budget = CandidateBudget(100L, Long.MaxValue)
    val parent = sized("parent", 60, 0)
    val child = sized("child", 60, 0)
    CandidateBundle.select(Seq(CandidateBundle(Vector(parent, child)),
      CandidateBundle(Vector(sized("solo", 40, 0)))), 10, budget).map(_.id) shouldBe Vector("solo")
  }

  /** A shared ancestor is charged once, so a second bundle is not billed for it again. */
  it should "charge a shared ancestor's bytes only once" in {
    val budget = CandidateBudget(100L, Long.MaxValue)
    val ancestor = sized("ancestor", 50, 0)
    CandidateBundle.select(Seq(CandidateBundle(Vector(ancestor, sized("first", 25, 0))),
      CandidateBundle(Vector(ancestor, sized("second", 25, 0)))), 10, budget)
      .map(_.id) shouldBe Vector("ancestor", "first", "second")
  }

  /** An unconfirmed ancestor reports no cost; the unclaimed rest of the block is what covers it. */
  it should "admit a member whose cost is unknown" in {
    val budget = CandidateBudget(Long.MaxValue, 10L)
    CandidateBundle.select(Seq(CandidateBundle(Vector(sized("ancestor", 40, 0)))), 10, budget)
      .map(_.id) shouldBe Vector("ancestor")
  }

  /** Genesis is part of the same supplied package, so what it claims is not available to sources. */
  it should "leave less for the sources once genesis is charged" in {
    CandidateBudget(500L, 1000L).less(200L, 0L) shouldBe CandidateBudget(300L, 1000L)
  }

  it should "not go negative when what is already claimed exceeds the share" in {
    CandidateBudget(100L, 10L).less(400L, 40L) shouldBe CandidateBudget(0L, 0L)
  }

  // ─── declared mempool interactions ────────────────────────────────────────

  /**
   * A declaration is a request, not authority. A bundle that says it spends an unconfirmed parent
   * but does not carry it would put a child into a block whose parent is absent, which is invalid.
   */
  "A declared mempool parent" should "disqualify a bundle that does not carry it" in {
    val orphan = CandidateBundle(Vector(tx("child")), Seq(ChainFromMempool("parent")))
    CandidateBundle.select(Seq(orphan, CandidateBundle(Vector(tx("ok")))), 5).map(_.id) shouldBe
      Vector("ok")
  }

  it should "admit a bundle that carries the parent it chains from" in {
    val chained = CandidateBundle(Vector(tx("parent"), tx("child")),
      Seq(ChainFromMempool("parent"), IncludeExisting("parent")))
    CandidateBundle.select(Seq(chained), 5).map(_.id) shouldBe Vector("parent", "child")
  }

  /** Interactions this client does not yet produce must not accidentally block a bundle. */
  it should "ignore interactions that name nothing the bundle has to carry" in {
    val superseding = CandidateBundle(Vector(tx("mine")), Seq(Supersede(Set("theirs"))))
    CandidateBundle.select(Seq(superseding), 5).map(_.id) shouldBe Vector("mine")
  }

  // ─── per-source bounding ──────────────────────────────────────────────────

  /** A source is bounded on its own first, and a bundle has to survive that pass whole. */
  "Fitting one source" should "return bundles rather than break them up" in {
    val pair = CandidateBundle(Vector(sized("a", 10, 0), sized("b", 10, 0)))
    val solo = CandidateBundle(Vector(sized("c", 10, 0)))
    CandidateBundle.fit(Seq(pair, solo), 1).map(_.members.map(_.id)) shouldBe Vector(Vector("c"))
  }

  it should "agree with what selection would flatten" in {
    val bundles = Seq(CandidateBundle(Vector(sized("a", 10, 0))),
      CandidateBundle(Vector(sized("b", 10, 0))))
    CandidateBundle.fit(bundles, 5).flatMap(_.members).map(_.id) shouldBe
      CandidateBundle.select(bundles, 5).map(_.id)
  }

  // ─── reporting what was turned away ───────────────────────────────────────

  /**
   * A bundle that overruns the budget is otherwise invisible: it was built, costed and signed, then
   * simply did not appear. This is the case a sweep priced above the package share lands in.
   */
  "A refusal" should "name the cost overrun and what was left" in {
    val sweep = CandidateBundle(Vector(sized("sweep", 10, 700000)))
    val refused = CandidateBundle.refusals(Seq(sweep), 5, CandidateBudget(Long.MaxValue, 500000))
    refused.map(_.bundle) shouldBe Vector(sweep)
    refused.head.reason should include("700000 cost over the 500000")
  }

  it should "charge the overrun against what earlier bundles already took" in {
    val first = CandidateBundle(Vector(sized("first", 10, 400000)))
    val second = CandidateBundle(Vector(sized("second", 10, 400000)))
    val refused = CandidateBundle.refusals(Seq(first, second), 5,
      CandidateBudget(Long.MaxValue, 500000))
    refused.map(_.bundle) shouldBe Vector(second)
    refused.head.reason should include("400000 cost over the 100000")
  }

  it should "report the byte overrun separately from the cost one" in {
    val big = CandidateBundle(Vector(sized("big", 900, 0)))
    CandidateBundle.refusals(Seq(big), 5, CandidateBudget(500, Long.MaxValue))
      .head.reason should include("900 bytes over the 500")
  }

  /** One refusal per bundle, stating the first cause, so a conflict never reads as an overrun. */
  it should "prefer the conflict to the budget when both would refuse" in {
    val mine = CandidateBundle(Vector(sized("mine", 10, 10)))
    val theirs = CandidateBundle(Vector(tx("theirs")
      .copy(inputIds = Set("mine-in"), sizeBytes = 9000, cost = 9000)))
    CandidateBundle.refusals(Seq(mine, theirs), 5, CandidateBudget(100, 100))
      .head.reason shouldBe "an input is already claimed by this package"
  }

  it should "name a slot shortage rather than a budget" in {
    val pair = CandidateBundle(Vector(sized("a", 10, 0), sized("b", 10, 0)))
    CandidateBundle.refusals(Seq(pair), 1).head.reason shouldBe
      "2 transactions into 1 free slot(s)"
  }

  it should "say nothing when everything offered fits" in {
    CandidateBundle.refusals(Seq(CandidateBundle(Vector(sized("a", 10, 10)))), 5) shouldBe empty
  }

  /** The label is how the log line identifies a bundle, which has no id of its own. */
  it should "label a bundle by its members" in {
    val bundle = CandidateBundle(Vector(sized("aabbccddeeff0011", 10, 700000)))
    val refusal = CandidateBundle.refusals(Seq(bundle), 5,
      CandidateBudget(Long.MaxValue, 1)).head
    refusal.label shouldBe s"${CandidateTx.Activate}:aabbccdd"
    refusal.toString should startWith(s"[${refusal.label}] ")
  }
}