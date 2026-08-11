package contracts.specs

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.matchers.should.Matchers
import sigma.data.AvlTreeFlags
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import java.math.BigInteger
import java.util.logging.{Level, Logger => JLogger}
import java.util.{List => JList}
import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{Failure, Success, Try}

object ContractSpecBase {

  /**
   * appkit's FileMockedErgoClient spins up a MockWebServer per `execute`, and each one logs two
   * request lines plus a shutdown notice at INFO. Across a full run that buries the actual results.
   */
  private lazy val quieted: Unit = {
    Seq("okhttp3.mockwebserver.MockWebServer", "okhttp3.mockwebserver")
      .foreach(name => JLogger.getLogger(name).setLevel(Level.OFF))
  }

  def silenceMockWebServer(): Unit = quieted
}

/**
 * Offline harness shared by every contract spec.
 *
 * Uses appkit's FileMockedErgoClient so specs need no node. Contracts compile at ErgoTree v3, which
 * requires the `_v6` node fixtures — the plain ones report blockVersion 3 and reject 6.0 features.
 * See ERGOSCRIPT_TESTING.MD for the full rationale.
 */
trait ContractSpecBase extends Matchers {

  ContractSpecBase.silenceMockWebServer()

  type Tree = PlasmaMap[Array[Byte], Array[Byte]]

  protected val dummyTxId: String =
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"

  protected val minerSecret: BigInteger = BigInteger.valueOf(1001L)
  protected val otherSecret: BigInteger = BigInteger.valueOf(2002L)
  protected val funderSecret: BigInteger = BigInteger.valueOf(3003L)

  protected val boxValue: Long = 60L * Parameters.OneErg
  protected val fundingValue: Long = Parameters.OneErg

  private def loadResponse(name: String): String = {
    val src = Source.fromResource(s"mockwebserver/node_responses/$name")
    try src.mkString finally src.close()
  }

  protected def withCtx[A](f: BlockchainContext => A): A = {
    val responses: JList[String] = Seq(
      loadResponse("response_NodeInfo_v6.json"),
      loadResponse("response_LastHeaders_v6.json")
    ).asJava
    new FileMockedErgoClient(responses, Seq.empty[String].asJava, true).execute(ctx => f(ctx))
  }

  // ─── provers and identities ───────────────────────────────────────────────

  protected def proverWith(ctx: BlockchainContext, secret: BigInteger): ErgoProver =
    ctx.newProverBuilder().withDLogSecret(secret).build()

  protected def miner(ctx: BlockchainContext): ErgoProver = proverWith(ctx, minerSecret)

  protected def contractOf(prover: ErgoProver): Contract = Contract.fromAddress(prover.getAddress)

  // ─── trees ────────────────────────────────────────────────────────────────

  protected def emptyTree: Tree = emptyTreeWith(AvlTreeFlags.AllOperationsAllowed)

  protected def emptyTreeWith(flags: AvlTreeFlags, params: PlasmaParameters = PlasmaParameters.default): Tree =
    PlasmaMap[Array[Byte], Array[Byte]](flags, params)

  protected def treeWith(entries: Seq[(Array[Byte], Array[Byte])],
                         flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed,
                         params: PlasmaParameters = PlasmaParameters.default): Tree = {
    val tree = emptyTreeWith(flags, params)
    if (entries.nonEmpty) tree.insert(entries: _*)
    tree.prover.generateProof()
    tree
  }

  // ─── boxes and transactions ───────────────────────────────────────────────

  protected def inputAt(box: UTXO, ctx: BlockchainContext, index: Int, txId: String = dummyTxId): InputUTXO =
    box.toInput(ctx, ErgoId.create(txId), index.toShort)

  protected def fundingInput(ctx: BlockchainContext, prover: ErgoProver, value: Long = fundingValue): InputUTXO =
    UTXO(contractOf(prover), value).toInput(ctx, ErgoId.create(dummyTxId), 1.toShort)

  protected def build(ctx: BlockchainContext,
                      inputs: Seq[InputUTXO],
                      outputs: Seq[UTXO],
                      changeTo: Address,
                      dataInputs: Seq[InputUTXO] = Seq.empty[InputUTXO],
                      burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setDataInputs(dataInputs: _*)
      .setOutputs(outputs: _*)
      .buildTx(Parameters.MinFee, changeTo, burn)

  // ─── assertions ───────────────────────────────────────────────────────────

  /** The spend must be accepted by the contract. */
  protected def accepts(prover: ErgoProver, tx: UnsignedTransaction): SignedTransaction =
    Try(prover.sign(tx)) match {
      case Success(signed) => signed
      case Failure(ex) =>
        fail(s"expected the contract to accept this spend, but it was rejected: ${ex.getMessage}", ex)
    }

  /** The spend must be rejected by the contract. Takes the tx by name — some negatives fail during build. */
  protected def rejects(prover: ErgoProver, tx: => UnsignedTransaction): Throwable =
    Try(prover.sign(tx)) match {
      case Success(_) =>
        fail("expected the contract to reject this spend, but it was accepted")
      case Failure(ex) => ex
    }

  /**
   * The spend must be rejected by the *contract*, not by the transaction builder.
   *
   * A negative that perturbs token amounts or box values can easily produce a transaction that never
   * balances, and [[rejects]] would pass on the build failure alone — proving nothing about the
   * contract. This splits the two steps: building must succeed, signing must not.
   */
  protected def rejectsAtSigning(prover: ErgoProver, tx: => UnsignedTransaction): Throwable = {
    val built = Try(tx) match {
      case Success(t) => t
      case Failure(ex) =>
        fail(s"expected a well-formed transaction for the contract to reject, but building it failed: ${ex.getMessage}", ex)
    }
    Try(prover.sign(built)) match {
      case Success(_) =>
        fail("expected the contract to reject this spend, but it was accepted")
      case Failure(ex) => ex
    }
  }
}
