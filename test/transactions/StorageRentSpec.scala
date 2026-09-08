package transactions

import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.{BlockchainParameters, ErgoId}
import org.ergoplatform.validation.ValidationRules
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.wallet.protocol.Constants
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeContext, ErgoLikeTransaction, Input}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.ast.ShortConstant
import sigma.crypto.CryptoConstants
import sigma.data.CGroupElement
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.{Coll, Colls, Header, PreHeader}
import sigmastate.eval.CPreHeader
import sigmastate.interpreter.Interpreter
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * Spending a box whose storage rent is due, which is the one spend Ergo allows with no script.
 *
 * The rule lives in `ErgoInterpreter.verify`: past `StoragePeriod` blocks, an input carrying an
 * empty proof and context variable 127 is accepted if the output that variable names recreates the
 * box. Nothing in it is a contract, so nothing here compiles one — the assertions run the real
 * interpreter against a script this client could never satisfy.
 *
 * The transaction is assembled directly rather than signed. A prover would try to satisfy the box's
 * own script and fail, so the empty proof has to be placed by hand.
 */
class StorageRentSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = nodeContext.getClient.execute(f)

  /** Mainnet's own values. `storageFeeFactor` is what prices a collection, at nanoERG per byte. */
  private val params: BlockchainParameters = new BlockchainParameters {
    override def storageFeeFactor: Int = 1250000
    override def minValuePerByte: Int = 360
    override def maxBlockSize: Int = 524288
    override def tokenAccessCost: Int = 100
    override def inputCost: Int = 2000
    override def dataInputCost: Int = 100
    override def outputCost: Int = 100
    override def maxBlockCost: Int = 8001000
    override def softForkStartingHeight: Option[Int] = None
    override def softForkVotesCollected: Option[Int] = None
    override def blockVersion: Byte = 3
  }

  private val verifier = ErgoInterpreter(params)

  /** A script this client holds no secret for, so only the rent rule can ever spend the box. */
  private val stranger: Contract = Contract.SIGMA_FALSE

  private def ergoBox(box: UTXO, ctx: BlockchainContext, index: Short = 0): ErgoBox =
    box.toInput(ctx, ErgoId.create("ab" * 32), index)
      .input.asInstanceOf[InputBoxImpl].getErgoBox

  /**
   * A box old enough to collect: created at height 0, so any height at or past `StoragePeriod`
   * makes it eligible. Registers and tokens are present because the rule compares all of them.
   */
  private def expiredBox(ctx: BlockchainContext, value: Long,
                         tokens: Seq[Token] = Seq.empty[Token]): ErgoBox =
    ergoBox(UTXO(stranger, value, tokens,
      Seq(org.ergoplatform.appkit.ErgoValue.of(42))).setCreationHeight(0), ctx)

  private def feeOf(box: ErgoBox): Long = params.storageFeeFactor.toLong * box.bytes.length

  /** The recreation the rule demands: everything preserved but value and creation height. */
  private def recreated(ctx: BlockchainContext, box: ErgoBox, value: Long, height: Int): ErgoBox =
    ergoBox(UTXO(Contract(box.ergoTree),
      value,
      box.additionalTokens.toArray.toSeq.map(t => Token(ErgoId.create(t._1.toString), t._2)),
      Seq(org.ergoplatform.appkit.ErgoValue.of(42))).setCreationHeight(height), ctx)

  private def minerBox(ctx: BlockchainContext, value: Long, height: Int): ErgoBox =
    ergoBox(UTXO(wallet.contract, value).setCreationHeight(height), ctx, index = 1)

  /**
   * The rent spend itself: one input carrying an empty proof, and variable 127 naming which output
   * recreates the box.
   */
  private def rentTx(box: ErgoBox, outputs: IndexedSeq[ErgoBoxCandidate],
                     namedOutput: Short = 0): ErgoLikeTransaction = {
    val input = Input(box.id, ProverResult(Array.emptyByteArray,
      ContextExtension(Map(Constants.StorageIndexVarId -> ShortConstant(namedOutput)))))
    new ErgoLikeTransaction(IndexedSeq(input), IndexedSeq.empty, outputs)
  }

  private def preHeaderAt(height: Int): PreHeader = CPreHeader(
    version = 0,
    parentId = Colls.emptyColl[Byte],
    timestamp = 0L,
    nBits = 0L,
    height = height,
    minerPk = CGroupElement(CryptoConstants.dlogGroup.generator),
    votes = Colls.emptyColl[Byte])

  /** Verify the one input against the real rule, at the height the collection is attempted. */
  private def accepts(tx: ErgoLikeTransaction, box: ErgoBox, height: Int): Boolean = {
    val context = new ErgoLikeContext(
      ErgoInterpreter.avlTreeFromDigest(Colls.fromArray(Array.fill[Byte](33)(0))),
      Colls.emptyColl[Header],
      preHeaderAt(height),
      IndexedSeq.empty[ErgoBox],
      IndexedSeq(box),
      tx,
      0,
      tx.inputs.head.spendingProof.extension,
      ValidationRules.currentSettings,
      params.maxBlockCost.toLong,
      0L,
      activatedScriptVersion = (params.blockVersion - 1).toByte)
    verifier.verify(Interpreter.emptyEnv, box.ergoTree, context,
      tx.inputs.head.spendingProof.proof, tx.messageToSign).map(_._1).getOrElse(false)
  }

  private val dueAt: Int = Constants.StoragePeriod

  // ─── the funded path ──────────────────────────────────────────────────────

  "A box past its storage period" should "be collectable by recreating it one fee lighter" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box)
      val tx = rentTx(box, IndexedSeq(
        recreated(ctx, box, box.value - fee, dueAt),
        minerBox(ctx, fee, dueAt)))

      withClue(s"a $fee nanoERG fee on a ${box.bytes.length} byte box: ") {
        accepts(tx, box, dueAt) shouldBe true
      }
    }
  }

  it should "not be collectable one block early" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box)
      val tx = rentTx(box, IndexedSeq(
        recreated(ctx, box, box.value - fee, dueAt - 1),
        minerBox(ctx, fee, dueAt - 1)))

      accepts(tx, box, dueAt - 1) shouldBe false
    }
  }

  /** `output.value >= box.value - storageFee`, so one nanoERG over the fee fails the whole spend. */
  it should "refuse a collection that takes more than the fee" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box) + 1L
      val tx = rentTx(box, IndexedSeq(
        recreated(ctx, box, box.value - fee, dueAt),
        minerBox(ctx, fee, dueAt)))

      accepts(tx, box, dueAt) shouldBe false
    }
  }

  /** `output.creationHeight == currentHeight`, which is what stops a collection being replayed. */
  it should "refuse a recreation that keeps the old creation height" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box)
      val tx = rentTx(box, IndexedSeq(
        recreated(ctx, box, box.value - fee, 0),
        minerBox(ctx, fee, dueAt)))

      accepts(tx, box, dueAt) shouldBe false
    }
  }

  // ─── what the recreation must preserve ────────────────────────────────────

  /** Every register but R0 and R3 is compared, and R1 is the script itself. */
  "A recreation under a different script" should "be refused" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box)
      val moved = ergoBox(UTXO(wallet.contract, box.value - fee,
        Seq.empty, Seq(org.ergoplatform.appkit.ErgoValue.of(42))).setCreationHeight(dueAt), ctx)
      val tx = rentTx(box, IndexedSeq(moved, minerBox(ctx, fee, dueAt)))

      accepts(tx, box, dueAt) shouldBe false
    }
  }

  /** R2 is the token register, so a funded collection can never take the box's tokens. */
  "A recreation dropping the box's tokens" should "be refused" in {
    withCtx { ctx =>
      val token = Token(ErgoId.create("cd" * 32), 500L)
      val box = expiredBox(ctx, 100L * 1000000000L, Seq(token))
      val fee = feeOf(box)
      val stripped = ergoBox(UTXO(Contract(box.ergoTree), box.value - fee, Seq.empty,
        Seq(org.ergoplatform.appkit.ErgoValue.of(42))).setCreationHeight(dueAt), ctx)
      val tx = rentTx(box, IndexedSeq(stripped, minerBox(ctx, fee, dueAt)))

      accepts(tx, box, dueAt) shouldBe false
    }
  }

  /** R4 upward is compared too, so a collection cannot quietly rewrite the box's own state. */
  "A recreation changing a register" should "be refused" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box)
      val altered = ergoBox(UTXO(Contract(box.ergoTree), box.value - fee, Seq.empty,
        Seq(org.ergoplatform.appkit.ErgoValue.of(43))).setCreationHeight(dueAt), ctx)
      val tx = rentTx(box, IndexedSeq(altered, minerBox(ctx, fee, dueAt)))

      accepts(tx, box, dueAt) shouldBe false
    }
  }

  // ─── the underfunded path ─────────────────────────────────────────────────

  /**
   * `storageFeeNotCovered` short-circuits the whole check, so a box that cannot pay its own rent is
   * spent outright — value, tokens and registers all forfeit, and no recreation required.
   */
  "A box that cannot cover its own fee" should "be spendable outright" in {
    withCtx { ctx =>
      val token = Token(ErgoId.create("cd" * 32), 500L)
      val box = expiredBox(ctx, 1000000L, Seq(token))
      box.value should be <= feeOf(box)

      val tx = rentTx(box, IndexedSeq(minerBox(ctx, box.value, dueAt)))
      accepts(tx, box, dueAt) shouldBe true
    }
  }

  /**
   * What a box has to hold to be worth recreating rather than taken whole, at mainnet's fee factor.
   *
   * The floor is high next to the consensus minimum — `minValuePerByte` prices the same box at 360
   * nanoERG a byte against rent's 1,250,000 — so most small boxes reaching collection age fall on
   * the outright branch, and the adapter's value is in what it can take whole.
   */
  "The fee" should "be reported for the box shapes a scan will meet" in {
    withCtx { ctx =>
      Seq("minimal" -> expiredBox(ctx, 1000000L),
        "with tokens" -> expiredBox(ctx, 1000000L, Seq(Token(ErgoId.create("cd" * 32), 1L)))
      ).foreach { case (name, box) =>
        val bytes = box.bytes.length
        println(f"[rent] $name%-12s $bytes%5d bytes  fee ${feeOf(box)}%12d  " +
          f"consensus minimum ${bytes.toLong * params.minValuePerByte}%10d")
      }
    }
  }

  // ─── what makes the bypass apply at all ───────────────────────────────────

  /** Without variable 127 the interpreter falls through and runs the box's own script. */
  "An input with no output named" should "fall back to the script and fail" in {
    withCtx { ctx =>
      val box = expiredBox(ctx, 100L * 1000000000L)
      val fee = feeOf(box)
      val input = Input(box.id, ProverResult(Array.emptyByteArray, ContextExtension.empty))
      val tx = new ErgoLikeTransaction(IndexedSeq(input), IndexedSeq.empty,
        IndexedSeq(recreated(ctx, box, box.value - fee, dueAt), minerBox(ctx, fee, dueAt)))

      accepts(tx, box, dueAt) shouldBe false
    }
  }
}
