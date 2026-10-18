package contracts.specs.forwards

import contracts.specs.harness.ContractSpecBase
import forwards.contracts.{DFContracts, DFTerms, DifficultyForwardContracts}
import forwards.{DFHelpers, DFMath}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.{BlockchainContextImpl, ScalaBridge}
import org.ergoplatform.sdk.ErgoId
import sigma.util.NBitsUtils
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger
import java.util.{List => JList}
import scala.collection.JavaConverters._
import scala.io.Source

/** One deployment's three contracts, built from the pinned templates. */
object DFSpecBase {

  // Ids belonging to nothing in the protocol, so no "unrelated token" case reaches a real one
  val accNFT: ErgoId = ErgoId.create("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
  val litId: ErgoId = ErgoId.create("c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1")
  val unrelatedToken: ErgoId = ErgoId.create("e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1")

  lazy val contracts: DFContracts = DFContracts(accNFT, litId)
}

/**
 * Shared harness for DF_Accumulator, DF_Offer and DF_Position.
 *
 *   record   IN  accumulator, funding              OUT accumulator, (change takes the reward)
 *   fill     IN  offer, taker funding              OUT position
 *   cancel   IN  offer, maker funding              OUT anything
 *   settle   IN  accumulator, position, funding    OUT accumulator, long payout, short payout
 *
 * The accumulator reads `CONTEXT.headers(0)`, which appkit takes from the mocked node's header fixture.
 * [[withLastBlock]] rewrites that fixture so the last block sits at any height with any nBits; the
 * pre-header, and so HEIGHT, is one above it, the same shape a node validates at.
 */
trait DFSpecBase extends ContractSpecBase {

  import DFSpecBase._

  protected def contracts: DFContracts = DFSpecBase.contracts

  // ─── identities ───────────────────────────────────────────────────────────

  protected val makerSecret: BigInteger = BigInteger.valueOf(5005L)
  protected val takerSecret: BigInteger = BigInteger.valueOf(6006L)
  protected val settlerSecret: BigInteger = BigInteger.valueOf(8008L)

  protected def maker(ctx: BlockchainContext): ErgoProver = proverWith(ctx, makerSecret)
  protected def taker(ctx: BlockchainContext): ErgoProver = proverWith(ctx, takerSecret)
  protected def settler(ctx: BlockchainContext): ErgoProver = proverWith(ctx, settlerSecret)

  protected def keyOf(prover: ErgoProver) = prover.getAddress.getPublicKey

  // ─── constants, mirrored from the .ergo sources ───────────────────────────

  protected val LIT: Long = 1000000000L
  protected val RING_SIZE: Int = DFHelpers.RING_SIZE
  protected val SIDE_REWARD: Long = DFHelpers.SIDE_REWARD
  protected val ACC_DEPOSIT: Long = DFHelpers.ACC_DEPOSIT
  protected val RECORD_REWARD: Long = DFHelpers.RECORD_REWARD
  protected val POSITION_ERG: Long = DFHelpers.POSITION_ERG

  protected val ACC_VALUE: Long = Parameters.OneErg
  protected val PAYOUT_ERG: Long = Parameters.MinFee
  protected val SLACK: Long = 10L * Parameters.OneErg

  // ─── chain context ────────────────────────────────────────────────────────

  /** Last block of the stock fixture, and its epoch. HEIGHT under the stock fixture is one above. */
  protected val fixtureLastBlock: Int = 123412
  protected val fixtureNBits: Long = 108309041L

  protected def epochOf(height: Int): Int = DFMath.epochOf(height)

  protected val fixtureEpoch: Int = epochOf(fixtureLastBlock)

  /** The difficulty the contract decodes from `nBits`, exactly. */
  protected def difficultyOf(nBits: Long): Long = NBitsUtils.decodeCompactBits(nBits).toLong

  /** An nBits value encoding roughly `difficulty`; read the exact value back with [[difficultyOf]]. */
  protected def nBitsFor(difficulty: Long): Long = NBitsUtils.encodeCompactBits(BigInt(difficulty))

  /** A height inside `epoch`, `offset` blocks past its first block. */
  protected def heightIn(epoch: Int, offset: Int = 10): Int = epoch * DFHelpers.EPOCH_LENGTH + 1 + offset

  private def fixture(name: String): String = {
    val src = Source.fromResource(s"mockwebserver/node_responses/$name")
    try src.mkString finally src.close()
  }

  private def client(info: String, headers: String): FileMockedErgoClient = {
    val responses: JList[String] = Seq(info, headers).asJava
    new FileMockedErgoClient(responses, Seq.empty[String].asJava, true)
  }

  /** The fixture header at `height`, as the text span from its "id" field to its last field. */
  private def headerSpan(json: String, height: Int): (Int, Int) = {
    val at = json.indexOf(s""""height": $height""")
    require(at >= 0, s"the header fixture has no header at $height")
    (json.lastIndexOf(""""id"""", at), json.indexOf(""""adProofsId"""", at))
  }

  /**
   * A context whose `CONTEXT.headers(0)` is at `lastBlock` with `nBits`, and whose pre-header, so HEIGHT, is
   * one above. Only that header and the one appkit takes the pre-header from are rewritten: the interpreter
   * requires each header's parentId to be the id of the one before, ids are computed from content, and the
   * pre-header's parentId must be the id of headers(0), so that one link is recomputed and written back.
   */
  protected def withLastBlock[A](lastBlock: Int, nBits: Long = fixtureNBits)(f: BlockchainContext => A): A =
    lastBlockClient(lastBlock, nBits).execute(ctx => f(ctx))

  /** A client whose every context is the one [[withLastBlock]] describes, for code that opens its own. */
  protected def lastBlockClient(lastBlock: Int, nBits: Long = fixtureNBits): ErgoClient = {
    require(lastBlock >= fixtureLastBlock, "the older fixture headers would be filtered out below the stock height")
    val raw = fixture("response_LastHeaders_v6.json")
    val (start, end) = headerSpan(raw, fixtureLastBlock)
    val oldId = """"id"\s*:\s*"([0-9a-f]+)"""".r.findFirstMatchIn(raw.substring(start, end)).get.group(1)
    val newest = raw.substring(start, end)
      .replace(s""""height": $fixtureLastBlock""", s""""height": $lastBlock""")
      .replaceAll(""""nBits"\s*:\s*\d+""", s""""nBits": $nBits""")
    // The two newer headers follow it in the fixture. Highest first, so neither rewrite lands on the other.
    val newer = raw.substring(end)
      .replace(s""""height": ${fixtureLastBlock + 2}""", s""""height": ${lastBlock + 2}""")
      .replace(s""""height": ${fixtureLastBlock + 1}""", s""""height": ${lastBlock + 1}""")
    val rewritten = raw.substring(0, start) + newest + newer
    val info = fixture("response_NodeInfo_v6.json")
      .replaceAll(""""fullHeight"\s*:\s*\d+""", s""""fullHeight": ${lastBlock + 1}""")
      .replaceAll(""""headersHeight"\s*:\s*\d+""", s""""headersHeight": ${lastBlock + 2}""")

    // appkit lists the headers newest first, so the rewritten one is second, after the pre-header's source
    val newId = client(info, rewritten).execute { ctx =>
      val header = ctx.asInstanceOf[BlockchainContextImpl].getHeaders.get(1)
      Hex.toHexString(ScalaBridge.toSigmaHeader(header).id.toArray)
    }
    client(info, rewritten.replace(oldId, newId))
  }

  // ─── accumulator ──────────────────────────────────────────────────────────

  /** The genesis shape: every slot holds the same difficulty. */
  protected def flatRing(difficulty: Long): IndexedSeq[Long] = IndexedSeq.fill(RING_SIZE)(difficulty)

  /** A ring whose slot for epoch e holds `base + e`, so every slot is distinct and traceable to its epoch. */
  protected def traceableRing(lastEpoch: Int, base: Long): IndexedSeq[Long] =
    (0 until RING_SIZE).map { i =>
      val slotEpoch = lastEpoch - (lastEpoch - i) % RING_SIZE
      base + slotEpoch
    }

  protected case class AccState(lastEpoch: Int, ring: IndexedSeq[Long], lit: Long)

  /** R4 lastEpoch, R5 ring. Token 1 is absent when the balance is zero. */
  protected def accUTXO(s: AccState,
                        value: Long = ACC_VALUE,
                        contract: Contract = null,
                        nft: ErgoId = accNFT,
                        lit: ErgoId = litId): UTXO =
    UTXO(
      if (contract == null) contracts.accumulator else contract,
      value,
      Seq(Token(nft, 1L)) ++ (if (s.lit > 0) Seq(Token(lit, s.lit)) else Seq.empty[Token]),
      DifficultyForwardContracts.accumulatorRegisters(s.lastEpoch, s.ring))

  /** The state after recording the current epoch, as the client computes it. */
  protected def afterRecord(s: AccState, currentEpoch: Int, difficulty: Long, taken: Long = RECORD_REWARD): AccState =
    AccState(currentEpoch, DFMath.recordedRing(s.ring, s.lastEpoch, currentEpoch, difficulty), s.lit - taken)

  protected def funding(ctx: BlockchainContext, prover: ErgoProver, index: Int,
                        value: Long = SLACK, tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    inputAt(UTXO(contractOf(prover), value, tokens), ctx, index)

  // ─── offers and positions ─────────────────────────────────────────────────

  protected val NOTIONAL: Long = 10000L * LIT
  /** Near the fixture's difficulty, and divisible by 100 so whole-percent moves are exact. */
  protected val STRIKE: Long = 128274315345900L

  protected def terms(windowStart: Int, windowLength: Int = 8,
                      strike: Long = STRIKE, notional: Long = NOTIONAL): DFTerms =
    DFTerms(strike, notional, windowStart, windowLength)

  protected def offerUTXO(ctx: BlockchainContext,
                          makerLong: Boolean,
                          t: DFTerms,
                          expiry: Int,
                          lit: Long = -1L,
                          value: Long = POSITION_ERG / 2,
                          contract: Contract = null): UTXO =
    UTXO(
      if (contract == null) contracts.offer else contract,
      value,
      Seq(Token(litId, if (lit < 0) DFMath.sideLit(t.notional) else lit)),
      DifficultyForwardContracts.offerRegisters(keyOf(maker(ctx)), makerLong, t, expiry))

  protected def positionUTXO(long: ErgoProver,
                             short: ErgoProver,
                             t: DFTerms,
                             lit: Long = -1L,
                             value: Long = POSITION_ERG,
                             contract: Contract = null,
                             token: ErgoId = litId): UTXO =
    UTXO(
      if (contract == null) contracts.position else contract,
      value,
      Seq(Token(token, if (lit < 0) DFMath.positionLit(t.notional) else lit)),
      DifficultyForwardContracts.positionRegisters(keyOf(long), keyOf(short), t))

  protected def payoutUTXO(to: ErgoProver, amount: Long, token: ErgoId = litId): UTXO =
    UTXO(contractOf(to), PAYOUT_ERG, Seq(Token(token, amount)))
}
