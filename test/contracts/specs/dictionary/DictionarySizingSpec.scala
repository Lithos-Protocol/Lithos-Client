package contracts.specs.dictionary

import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256

/**
 * Sizing, and the timing argument that stands in for a value floor.
 *
 * A MinerData box is collectible for storage rent, deliberately: at 0.001 ERG the fee exceeds the
 * value from the first storage period, and the collector takes the credential token with it. What
 * makes that safe is ordering. A registration expires DATA_LIFETIME after it is made and the box
 * cannot be collected until STORAGE_PERIOD after it is created, so a credential can only leak once
 * the entry it names has already expired.
 */
class DictionarySizingSpec extends AnyPropSpec with DictionarySpecBase {

  /** Nanoerg per byte per storage period. Votable to 2500000. */
  private val StorageFeeFactor = 1250000L

  /** Ergo's own: 1051200 blocks, four years at 720 blocks a day. */
  private val StoragePeriod = LFSMHelpers.STORAGE_PERIOD

  private def rentFor(bytes: Int): Long = StorageFeeFactor * bytes.toLong

  // ─── 1. the contracts compile, and what each costs ────────────────────────

  property("compiles: the dictionary, the MinerData guard and its logic") {
    withCtx { ctx =>
      val dict = dictionaryContract(ctx)
      val guard = dataContract(ctx)
      val logic = dataLogic(ctx)

      println(s"[size] MinerDictionary ergoTree   = ${dict.ergoTree.bytes.length} bytes")
      println(s"[size] MinerData_Guard ergoTree   = ${guard.ergoTree.bytes.length} bytes (on the box)")
      println(s"[size] MinerData_Logic ergoTree   = ${logic.ergoTree.bytes.length} bytes (in var 64)")

      // Every 6.0 feature the set uses needs ErgoTree v3.
      dict.ergoTree.version shouldBe 3
      guard.ergoTree.version shouldBe 3
      logic.ergoTree.version shouldBe 3

      // What a miner creates and what the UTXO set carries is the guard, and the logic can grow
      // without moving either.
      guard.ergoTree.bytes.length should be < logic.ergoTree.bytes.length
    }
  }

  // ─── 2. the box, measured ─────────────────────────────────────────────────

  /**
   * Every field on a MinerData box is either pinned by the dictionary or bounded by its type, so the
   * maximum is a fact rather than an observation. R4 is two `(Int, Long)` pairs, R5 is a 32-byte
   * digest, R6 is refused on every path that produces a successor, and the token count is pinned at
   * one. What is left free is how wide the value and the heights serialise.
   */
  private def worstCaseBytes(ctx: BlockchainContext): Int = {
    val minerHash = Blake2b256.hash("a miner")
    val credential = inputAt(dictionaryUTXO(ctx, emptyTree), ctx, 0).id
    val box = dataUTXO(ctx, credential, minerHash,
      commitments = Seq((Int.MaxValue, Long.MaxValue), (Int.MaxValue, Long.MaxValue)),
      value = Long.MaxValue / 8L,
      creationHeight = Some(Int.MaxValue))
    inputAt(box, ctx, 0).bytes.length
  }

  property("sizing: the MinerData box, and the rent it is deliberately under") {
    withCtx { ctx =>
      val minerHash = Blake2b256.hash("a miner")
      val credential = inputAt(dictionaryUTXO(ctx, emptyTree), ctx, 0).id

      val fresh = inputAt(dataUTXO(ctx, credential, minerHash,
        Seq((ctx.getHeight + 200, 100000L))), ctx, 0).bytes.length
      val steady = inputAt(dataUTXO(ctx, credential, minerHash,
        Seq((ctx.getHeight + 200, 100000L), (ctx.getHeight - 900, 90000L))), ctx, 0).bytes.length
      val worst = worstCaseBytes(ctx)

      println(s"[rent] MinerData at registration (1 commitment) = $fresh bytes, rent ${rentFor(fresh)}")
      println(s"[rent] MinerData in steady state (2)            = $steady bytes, rent ${rentFor(steady)}")
      println(s"[rent] MinerData STRUCTURAL MAX                 = $worst bytes, rent ${rentFor(worst)}")
      println(s"[rent] CONST_MIN_DATA_BOX_AMNT                  = ${LFSMHelpers.MIN_DATA_BOX_AMNT}")
      println(s"[rent] a floor covering one collection would be ~${rentFor(worst)} per miner")

      // Nothing a miner controls can push the box past this.
      worst should be < 600
      // And the box really is under water, which is the premise the rest of this file rests on.
      LFSMHelpers.MIN_DATA_BOX_AMNT should be < rentFor(fresh)
    }
  }

  // ─── 3. the ordering that replaced the floor ──────────────────────────────

  property("timing: a registration expires strictly before the box it names can be collected") {
    println(s"[timing] DATA_LIFETIME = ${LFSMHelpers.DATA_LIFETIME} blocks " +
      f"(${LFSMHelpers.DATA_LIFETIME / 720.0 / 365.0}%.2f years at 720 blocks a day)")
    println(s"[timing] EVICT_DELAY   = ${LFSMHelpers.EVICT_DELAY} blocks " +
      f"(${LFSMHelpers.EVICT_DELAY / 720.0}%.0f days)")
    println(s"[timing] StoragePeriod = $StoragePeriod blocks")

    // The whole security argument, as one line: expiry then collection, never the other way round.
    LFSMHelpers.DATA_LIFETIME + LFSMHelpers.EVICT_DELAY shouldBe StoragePeriod
    LFSMHelpers.DATA_LIFETIME should be < StoragePeriod
    LFSMHelpers.EVICT_DELAY should be > 0L
  }

  /**
   * Eviction is permissionless, so it must be impossible to use against a miner whose registration
   * still mattered. Any rollup still in evaluation at the eviction height was mined after the entry
   * expired, so it was already judged against an expired registration.
   */
  property("timing: eviction cannot retire an entry a live rollup was judged against") {
    val evictableAt = evictDelay // blocks past validUntil
    // A rollup is challengeable for ROLLUP_LIFETIME blocks after its own block height.
    withClue("the eviction delay must exceed a full rollup lifetime: ") {
      evictableAt should be > rollupLifetime
    }
    println(s"[timing] eviction waits $evictableAt blocks past expiry, against a " +
      s"$rollupLifetime-block rollup lifetime")
  }

  /**
   * The half a contract has to enforce: a builder picks a box's creation height, so without a bound a
   * registration hands out a box already collectible while its entry is live.
   */
  property("timing: the backdating bound is exactly the expiry-to-collection gap") {
    LFSMHelpers.EVICT_DELAY shouldBe (StoragePeriod - LFSMHelpers.DATA_LIFETIME)
    // Backdated to the limit, collection still falls after expiry.
    val backdatedBy = LFSMHelpers.EVICT_DELAY - 1L
    val collectibleAt = -backdatedBy + StoragePeriod
    val expiresAt = LFSMHelpers.DATA_LIFETIME
    withClue(s"backdated $backdatedBy blocks, collectible at $collectibleAt, expires at $expiresAt: ") {
      collectibleAt should be > expiresAt
    }
  }

  /**
   * The spacing rule and the lifetime have to be compatible: a miner who only ever touches their box
   * by changing difficulty must be able to do so far more often than the registration lasts.
   */
  property("timing: the commitment spacing is far shorter than the lifetime") {
    commitSpacing shouldBe (LFSMHelpers.NISP_WINDOW + LFSMHelpers.ROLLUP_LIFETIME)
    commitSpacing should be < LFSMHelpers.DATA_LIFETIME
    println(s"[timing] commitment changes may be $commitSpacing blocks apart, against a " +
      s"${LFSMHelpers.DATA_LIFETIME}-block registration")
  }
}
