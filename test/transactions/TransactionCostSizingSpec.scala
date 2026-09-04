package transactions

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import configs.{CandidateConfig, EmissionConfig}
import contracts.specs.emission.EmissionSpecBase
import contracts.specs.lithosdex.LithosDexSpecBase
import contracts.specs.rollup.{FraudProofSpecBase, NispFixtures}
import lfsm.states.{Rollup, RollupInfoState}
import lfsm.{LFSMHelpers, RollupProtocol}
import lithosdex.{LDHelpers, LDLiquidityPool}
import mining.CandidateTxBuilder
import mutations.NodeWallet
import nisp.{NISP, SuperShare, TransactionProof}
import node.NodeApi
import org.ergoplatform.ErgoLikeTransactionSerializer
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.SignedTransactionImpl
import org.ergoplatform.sdk.{ErgoId, SecretString}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import scorex.crypto.hash.Blake2b256
import scorex.utils.Ints
import transactions.dex.{DexContracts, LDBoxes, LDFundedTx, LithosDexTransactions}
import transactions.emissions.EmissionTransactions
import transactions.rollups.RollupTransactions
import transactions.rollups.TransactionMessages.LatestRollup
import transactions.wallet.WalletMessages.{RetrieveInputs, WalletInputs}
import transactions.wallet.{WalletReservation, WalletSelector, WalletSource}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * What each transaction this client builds costs a block, in the two currencies a block is spent in.
 *
 * A block is bounded twice over and the bounds are unrelated: `maxBlockSize` counts the serialised
 * bytes of everything in it, `maxBlockCost` counts what validating it costs the node. Ergo's defaults
 * are **524288 bytes and 1000000 cost units** — read here off the node fixture rather than written
 * down, because every cost below is measured under exactly those per-input, per-output and per-token
 * parameters. Miners vote on all of them, so these are a starting point rather than a ceiling.
 *
 * Nothing here checks a contract condition, so a failure means a number moved rather than a rule
 * broke — with one exception. Every measurement is taken off a transaction the production builder
 * produced and the production contract **signed**, because a cost taken off a transaction the chain
 * would reject is not a cost. That is also why `getCost` means anything: appkit's prover accumulates
 * reduction and verification cost the way the node does, out of the same parameters.
 *
 * The two figures are not the same figure:
 *
 *   - **bytes** is `ErgoLikeTransactionSerializer`, which is the node's own notion of transaction
 *     size, proofs included. `messageToSign`, which `NISPFormatSizingSpec` measures, is the same
 *     serialisation with the proofs blanked, so it runs short by one signature per input.
 *   - **cost** is `SignedTransaction.getCost`: interpreter init, plus per input, data input, output
 *     and token, plus what every input script cost to reduce and verify.
 *
 * Two things this client builds are NOT measured here:
 *
 *   - **A funded fraud proof.** `RollupTransactions.genFraudProofTransform` reaches `Globals` for a
 *     `BoxLoader`, which is the boundary the `NodeContext` refactor stopped at. The fraud proof at
 *     the end of this file is built by the contract harness instead and labelled as such: it is the
 *     largest transaction the protocol produces, and leaving it out would misreport the budget.
 *   - **A difficulty commitment.** `CommitmentTransactions.commitScore` signs and sends inside one
 *     method, and its transaction is two inputs and two outputs — the cheapest shape here, and the
 *     one whose absence changes no conclusion.
 */
class TransactionCostSizingSpec extends AnyPropSpec with BeforeAndAfterAll
  with EmissionSpecBase with FraudProofSpecBase with MockitoSugar {

  // ─── the block, and one transaction's share of it ─────────────────────────

  /** Ergo's `maxTransactionSize`: node policy rather than consensus, and it binds first. */
  private val MaxTxSize = 98304

  private var maxBlockSize = 512 * 1024
  private var maxBlockCost = 1000000

  private final case class Measured(family: String, name: String, tx: SignedTransaction) {
    val bytes: Int = ErgoLikeTransactionSerializer
      .toBytes(tx.asInstanceOf[SignedTransactionImpl].getTx).length
    val cost: Int = tx.getCost
    val inputs: Int = tx.getSignedInputs.size
    val outputs: Int = tx.getOutputsToSpend.size

    /** How many of this transaction a block holds before each bound is reached. */
    def perBlockBySize: Int = maxBlockSize / math.max(bytes, 1)
    def perBlockByCost: Int = maxBlockCost / math.max(cost, 1)
    def binds: String = if (perBlockBySize <= perBlockByCost) "size" else "cost"
  }

  private val measured = mutable.ArrayBuffer.empty[Measured]

  private val header =
    f"${"family"}%-10s ${"transaction"}%-36s ${"in"}%3s ${"out"}%4s ${"bytes"}%7s ${"%blk"}%6s " +
      f"${"/blk"}%5s ${"cost"}%8s ${"%blk"}%6s ${"/blk"}%5s  binds"

  private def printHeader(): Unit = println(s"[budget] $header")

  private def row(tag: String, m: Measured): String =
    f"[$tag] ${m.family}%-10s ${m.name}%-36s ${m.inputs}%3d ${m.outputs}%4d ${m.bytes}%7d " +
      f"${100.0 * m.bytes / maxBlockSize}%5.2f%% ${m.perBlockBySize}%5d ${m.cost}%8d " +
      f"${100.0 * m.cost / maxBlockCost}%5.2f%% ${m.perBlockByCost}%5d  ${m.binds}"

  /**
   * Record one transaction and print its row. Every caller has already had it signed by the contract
   * that has to accept it, so what is measured is a transaction the chain would take.
   */
  private def measure(family: String, name: String, tx: SignedTransaction): Measured = {
    val m = Measured(family, name, tx)
    measured += m
    println(row("budget", m))
    // A transaction wider than a block, or dearer than one, can never be mined at all.
    withClue(s"$family/$name must fit one block on both axes: ") {
      m.bytes should be < maxBlockSize
      m.cost should be < maxBlockCost
    }
    // And the node refuses an oversized transaction before consensus ever sees it.
    withClue(s"$family/$name must clear Ergo's maxTransactionSize: ") {
      m.bytes should be < MaxTxSize
    }
    m
  }

  // ─── the client's own wallet ──────────────────────────────────────────────

  /** The same fixed test mnemonic `support.FakeNodeContext` uses. Holds nothing, anywhere. */
  private val mnemonic =
    "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"

  /**
   * A wallet that keeps the last transaction it signed.
   *
   * `NodeWallet.sign` is the one funnel every builder in this client reaches signing through, so
   * subclassing it captures builders that hand their transaction straight to the node or wrap it in
   * something else. `CandidateTxBuilder.buildGenesis` is the reason it exists: it returns
   * `CollateralData`, whose `txBytes` is a `messageToSign` and carries no cost at all.
   */
  private class RecordingWallet(prover: ErgoProver) extends NodeWallet(prover) {
    private var last: Option[SignedTransaction] = None

    override def sign(uTx: UnsignedTransaction): SignedTransaction = {
      val signed = super.sign(uTx)
      last = Some(signed)
      signed
    }

    def lastSigned: SignedTransaction =
      last.getOrElse(fail("the builder under measurement signed nothing"))
  }

  /**
   * The prover comes from a mnemonic rather than `withDLogSecret`: `NodeWallet` reads
   * `getEip3Addresses`, which a DLog-only prover has none of, and every member throws on construction.
   */
  private def walletOf(ctx: BlockchainContext): RecordingWallet =
    new RecordingWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(mnemonic), SecretString.empty(), false)
      .withEip3Secret(0).build())

  private def walletInput(ctx: BlockchainContext,
                          wallet: NodeWallet,
                          value: Long = 100L * Parameters.OneErg,
                          tokens: Seq[Token] = Seq.empty[Token],
                          index: Int = 1): InputUTXO =
    inputAt(UTXO(wallet.contract, value, tokens), ctx, index)

  /**
   * The fee output `SubmissionHandler` puts on every rollup transaction.
   *
   * One box here, where a real batch carries one more per rollup still to be built in the same tick,
   * so the first transaction of a batch runs a little wider than these rows.
   */
  private def feeOutputs: Seq[UTXO] = Seq(UTXO.feeBox(Parameters.MinFee))

  // ══════════════════════════════════════════════════════════════════════════
  //  0. the parameters everything below is measured against
  // ══════════════════════════════════════════════════════════════════════════

  property("budget: the block parameters these numbers are measured against") {
    withCtx { ctx =>
      val p = ctx.getDataSource.getParameters
      maxBlockSize = p.getMaxBlockSize
      maxBlockCost = p.getMaxBlockCost

      println(s"[budget] maxBlockSize       = $maxBlockSize bytes (${maxBlockSize / 1024} KiB)")
      println(s"[budget] maxBlockCost       = $maxBlockCost cost units")
      println(s"[budget] maxTransactionSize = $MaxTxSize (node policy, not consensus)")
      println(s"[budget] inputCost=${p.getInputCost} dataInputCost=${p.getDataInputCost} " +
        s"outputCost=${p.getOutputCost} tokenAccessCost=${p.getTokenAccessCost}")

      // Ergo's launch defaults, and the premise of every percentage below. A vote moves them, and a
      // measurement taken under moved parameters is a different measurement — the per-input and
      // per-output costs feed straight into getCost.
      maxBlockSize shouldBe 512 * 1024
      maxBlockCost shouldBe 1000000
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  1. genesis — the transaction that makes a block a Lithos block
  // ══════════════════════════════════════════════════════════════════════════

  /** A collateral box exactly as the emission contract mints one, under this build's own script. */
  private def collateralInput(ctx: BlockchainContext,
                              currentBlock: Int = 0,
                              finderFee: Long = 0L): InputUTXO = {
    val c = ProtocolContracts(ctx)
    val (emitted, pub, split) = emissionAt(currentBlock, litSupply)
    inputAt(collateralUTXO(ctx, lender(ctx), lit = emitted + LFSMHelpers.PERMIT_FLOOR, pub = pub,
      founderSplit = split, rollupHash = c.holding.hashedPropBytes,
      feeValue = DUST_BUDGET + finderFee,
      value = principalFloor + 5L * finderFee,
      contract = c.collateral), ctx, 0)
  }

  /** The genesis transaction, and the two byte strings every super-share carries a copy of. */
  private def genesis(ctx: BlockchainContext,
                      wallet: RecordingWallet,
                      currentBlock: Int = 0,
                      finderFee: Long = 0L): (SignedTransaction, Array[Byte], Array[Byte]) = {
    val builder = new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
    val data = builder.buildGenesis(ctx, collateralInput(ctx, currentBlock, finderFee),
      ctx.getHeight + 1)
    (wallet.lastSigned, data.txBytes, data.collateralBoxBytes)
  }

  property("genesis: the transaction every Lithos block opens with") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      printHeader()

      val (launch, txBytes, collatBytes) = genesis(ctx, wallet)
      val m = measure("genesis", "launch era, founders, no bid", launch)

      val (bid, _, _) = genesis(ctx, wallet, finderFee = 3000000L)
      measure("genesis", "launch era, founders, with a bid", bid)

      val (late, _, _) = genesis(ctx, wallet, currentBlock = EPOCH_LENGTH * PRIV_LENGTH)
      measure("genesis", "post-founder era", late)

      println(s"[budget] the genesis tx rides inside every super-share: ${collatBytes.length}-byte " +
        s"collateral box and ${txBytes.length}-byte messageToSign, against a ${m.bytes}-byte " +
        "signed transaction")

      // The one transaction with no fee output and no wallet input: every output is funded out of
      // the collateral box's own fee channel, which is also why it is the cheapest thing here.
      m.inputs shouldBe 1

      // And the only one whose signed size is its unsigned size. The collateral contract is a pure
      // predicate — it names no key, because the coinbase is what pays its lender — so it reduces to
      // true and carries an empty proof. Everywhere else the two differ by a signature per input.
      withClue("a spend of the collateral box carries no signature at all: ") {
        m.bytes shouldBe txBytes.length
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  2. the rollup chain
  // ══════════════════════════════════════════════════════════════════════════

  private def keyFor(i: Int): Array[Byte] = Blake2b256.hash(Ints.toByteArray(i))

  // The rollup chain, at the scripts the BUILDERS compile rather than the ones `RollupSpecBase`
  // does. Evaluation carries the fraud-proof token id and Holding carries Evaluation's hash, so the
  // spec base's stand-in token produces a different guard — and a box under it is refused by
  // `validUTXO` for a reason that has nothing to do with what is being measured. Payout takes no
  // constants and so is the same script either way, which is why only these two used to fail.
  private def liveHolding(ctx: BlockchainContext): Contract = utils.Helpers.holdingContract(ctx)
  private def liveEval(ctx: BlockchainContext): Contract = utils.Helpers.evalContract(ctx)
  private def livePayout(ctx: BlockchainContext): Contract = utils.Helpers.payoutContract(ctx)

  /**
   * Ten super-shares carrying a real transaction proof.
   *
   * `txProof` is what a share proves its collateral spend with, and its two halves are the genesis
   * transaction and the collateral box that transaction spent — so the honest size of a NISP is
   * decided by the transaction measured in section 1, ten times over.
   */
  private def sharesWith(ctx: BlockchainContext,
                         wallet: NodeWallet,
                         proof: TransactionProof,
                         levels: Int): Seq[SuperShare] = {
    val pk = NispFixtures.pkOf(wallet.prover)
    val at = ctx.getHeight
    (0 until 10).map(i =>
      SuperShare(NispFixtures.header(at - i, pk, 1700000000000L + i).bytes, proof,
        NispFixtures.levels(levels)))
  }

  /**
   * One NISP submission, built and signed by the real builder against the real holding guard.
   *
   * The tree is stocked with `otherMiners` entries of the same NISP size, because an AVL insert proof
   * carries whichever leaf the new key splits — a tree of full NISPs makes that proof a NISP wider
   * than an empty one, and measuring against an empty tree flatters the result badly.
   */
  private def submission(ctx: BlockchainContext,
                         wallet: RecordingWallet,
                         proof: TransactionProof,
                         levels: Int,
                         otherMiners: Int): (SignedTransaction, Int) = {
    val score = 100000L
    val nisp = NISP(score, sharesWith(ctx, wallet, proof, levels))
    val nispSize = nisp.serialize.length

    val tree = treeWith((0 until otherMiners).map(i => keyFor(i) -> nispBytes(score, nispSize)))
    val heldBond = otherMiners.toLong * RollupProtocol.bondForScore(score)
    val periodStart = ctx.getHeight.toLong - 100L
    val value = boxValue + heldBond

    val in = UTXO(liveHolding(ctx), value, Seq.empty[Token], Seq(
      tree.ergoValue, ErgoValue.of(otherMiners),
      ErgoValue.of(BigInt(otherMiners.toLong * score).bigInteger),
      stateReg(periodStart, periodStart, heldBond)))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

    val state = Rollup(tree, numMiners = otherMiners,
      totalScore = BigInt(otherMiners.toLong * score),
      state = RollupInfoState.holding(periodStart, periodStart, heldBond), value = value,
      startHeight = periodStart.toInt, hasMiner = true, blockId = "bb" * 32, utxoId = dummyTxId)

    val signed = RollupTransactions.genNISPSubmission(ctx, wallet, in,
      Seq(walletInput(ctx, wallet)), LatestRollup(in, state), feeOutputs, nisp, score)
    (signed, nispSize)
  }

  /**
   * The biggest thing this client submits, and the only one whose size a miner controls.
   *
   * Both axes move together here for the same reason: the NISP travels in a context extension and so
   * does the insert proof that puts it in the tree, and the holding logic reads and hashes both.
   */
  property("rollup: a NISP submission, at the shapes this client actually builds") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val (_, txBytes, collatBytes) = genesis(ctx, wallet)
      val realProof = TransactionProof(txBytes, collatBytes)
      printHeader()

      // Merkle depth is set by how many transactions the proven block held rather than by the miner,
      // so both ends of the range an honest share can land in are measured.
      val shallow = submission(ctx, wallet, realProof, levels = 2, otherMiners = 0)
      measure("rollup", s"submit ${shallow._2}B NISP, empty tree", shallow._1)

      val populated = submission(ctx, wallet, realProof, levels = 2, otherMiners = 64)
      measure("rollup", s"submit ${populated._2}B NISP, 64 miners", populated._1)

      val deep = submission(ctx, wallet, realProof, levels = 16, otherMiners = 64)
      measure("rollup", s"submit ${deep._2}B NISP, 64 miners", deep._1)

      println(s"[budget] txProof = 2 + ${txBytes.length} + ${collatBytes.length} = " +
        s"${realProof.size}, against TX_PROOF_MAX ${LFSMHelpers.TX_PROOF_MAX}")
      println(s"[budget] the honest NISPs above are ${shallow._2} and ${deep._2} bytes, against " +
        s"CONST_NISP_MIN ${LFSMHelpers.NISP_MIN} and CONST_NISP_MAX ${LFSMHelpers.NISP_MAX}")

      // A NISP built out of this client's own genesis transaction has to be one the holding contract
      // accepts, or these are measurements of a transaction that could never be made.
      shallow._2 should be >= LFSMHelpers.NISP_MIN
      deep._2 should be < LFSMHelpers.NISP_MAX
    }
  }

  /** A rollup box holding `miners` entries, at whichever contract of the chain it has reached. */
  private def chainBox(ctx: BlockchainContext,
                       contract: Contract,
                       state: RollupInfoState,
                       value: Long,
                       tokens: Seq[Token],
                       score: Long,
                       miners: Int,
                       tree: Tree): InputUTXO =
    UTXO(contract, value, tokens, Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger), state.ergoValue))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

  /**
   * The two transforms, which are the cheap end of the chain: neither carries a proof, and neither
   * moves the tree. Whatever the rollup holds, these are the same transaction.
   */
  property("rollup: the two transforms that walk a rollup to payout") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val score = 100000L
      val bond = RollupProtocol.bondForScore(score)
      val tree = treeWith(Seq(wallet.contract.hashedPropBytes -> nispBytes(score, 24000)))
      printHeader()

      // Holding -> Evaluation. Only the period start moves; the tree, the counters and the bond
      // ledger carry through untouched.
      val holdingStart = ctx.getHeight.toLong - LFSMHelpers.HOLDING_PERIOD
      val holdingIn = chainBox(ctx, liveHolding(ctx),
        RollupInfoState.holding(holdingStart, holdingStart, bond),
        boxValue + bond, Seq.empty[Token], score, 1, tree)
      measure("rollup", "holding -> evaluation transform",
        RollupTransactions.genHoldingTransform(ctx, wallet, holdingIn,
          Seq(walletInput(ctx, wallet)), feeOutputs))

      // Evaluation -> Payout. R7 stops holding heights here and starts holding the two reward pots.
      val evalStart = ctx.getHeight.toLong - LFSMHelpers.EVAL_PERIOD - 10L
      val evalIn = chainBox(ctx, liveEval(ctx),
        RollupInfoState.evaluation(evalStart, evalStart - LFSMHelpers.HOLDING_PERIOD, bond),
        boxValue + bond, Seq.empty[Token], score, 1, tree)
      measure("rollup", "evaluation -> payout transform",
        RollupTransactions.genEvalTransform(ctx, wallet, evalIn,
          Seq(walletInput(ctx, wallet)), feeOutputs))
    }
  }

  /**
   * A payout, which is the other end of a NISP's cost. The entry went into the tree under one insert
   * proof and comes out under a lookup AND a removal, and a removal proof carries both the entry and
   * the neighbour it re-links — so a payout is roughly three NISPs where a submission was two.
   */
  property("rollup: a payout that pays one miner and recreates its box") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val score = 100000L
      val bond = RollupProtocol.bondForScore(score)
      val nftToken = Token(ErgoId.create("cc" * 32), 1L)
      printHeader()

      Seq(8000, 24000).foreach { nispSize =>
        // A bystander, so the removal proof carries a real neighbour rather than nothing at all.
        val tree = treeWith(Seq(
          wallet.contract.hashedPropBytes -> nispBytes(score, nispSize),
          keyFor(1) -> nispBytes(score, nispSize)))
        val bonds = 2L * bond
        val reward = 40L * Parameters.OneErg
        val value = reward + bonds
        val state = RollupInfoState.payout(reward, 0L, bonds)

        val in = chainBox(ctx, livePayout(ctx), state, value, Seq(nftToken), 2L * score, 2, tree)
        val rollup = Rollup(tree, numMiners = 2, totalScore = BigInt(2L * score), state = state,
          value = value, startHeight = ctx.getHeight - 800, hasMiner = true,
          blockId = "bb" * 32, utxoId = dummyTxId)

        measure("rollup", s"payout one miner, ${nispSize}B NISPs",
          RollupTransactions.genPayout(ctx, wallet, in, Seq(walletInput(ctx, wallet)),
            LatestRollup(in, rollup), feeOutputs))
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  3. emission — the lender-facing transactions
  // ══════════════════════════════════════════════════════════════════════════

  /** `genJoin`, `genActivate` and `genClear` take their funding as an argument, so this never runs. */
  private object UnusedWallet extends WalletSource {
    def reserve(value: Long, tokens: Seq[Token]): WalletReservation =
      throw new IllegalStateException("a builder under measurement asked the wallet for funds")
    def reserveCovering(value: Long): WalletReservation =
      throw new IllegalStateException("a builder under measurement asked the wallet for one input")
    def reserveCoveringP2PK(value: Long): WalletReservation =
      throw new IllegalStateException("a builder under measurement asked for one P2PK input")
    def reserveKnown(inputs: Seq[InputUTXO]): WalletReservation =
      throw new IllegalStateException("a builder under measurement asked the wallet to hold change")
    def giveBack(boxes: Seq[InputUTXO]): Unit = ()
  }

  /**
   * The emission box under the PRODUCTION guard, which is the script the builder carries through.
   *
   * The queue token count is `supply - (tail - head)` rather than the genesis supply: every queue box
   * in existence took one on the way out, and a fixture holding both overflows on the spend.
   */
  private def emissionInputAt(ctx: BlockchainContext,
                              lenderSet: Seq[Array[Byte]],
                              head: Long,
                              tail: Long,
                              collatTokens: Long = collatSupply): InputUTXO =
    inputAt(emissionUTXO(ctx, currentBlock = 0, lenderSet = lenderSet, head = head, tail = tail,
      lit = litSupply, queueTokens = queueSupply - (tail - head), collatTokens = collatTokens,
      contract = ProtocolContracts(ctx).guard), ctx, 0)

  private def emissionConfigInput(ctx: BlockchainContext): InputUTXO = {
    val c = ProtocolContracts(ctx)
    configInput(ctx, configBox(ctx, c.enforcer, rollupHash = c.holding.hashedPropBytes))
  }

  property("emission: the four transactions a lender's position passes through") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val txFee = Parameters.MinFee
      val txs = new EmissionTransactions(wallet, mock[NodeApi],
        EmissionConfig.Default.copy(txFee = txFee), UnusedWallet)
      val lenderProver = miner(ctx)
      printHeader()

      measure("emission", "join (open a queue position)",
        txs.genJoin(ctx, emissionInputAt(ctx, Seq.empty, 0L, 0L), emissionConfigInput(ctx),
          wallet.p2pk,
          Seq(inputAt(UTXO(wallet.contract, 10L * Parameters.OneErg,
            Seq(Token(LFSMHelpers.LIT_ID, permitAt(0L)))), ctx, 1))))

      // Bootstrapping: the active set has room, so a slot is appended rather than rotated.
      measure("emission", "activate (bootstrap, appends a slot)",
        txs.genActivate(ctx, emissionInputAt(ctx, Seq.empty, 0L, 1L), emissionConfigInput(ctx),
          inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permitAt(1L)), ctx, 1),
          retiring = None, funding = Seq.empty, feeOutput = None))

      // Rotating: a full set, so the builder must consume a proof of spend to free a slot. One more
      // input, and a hundred-entry lender set to fold over rather than an empty one.
      val retiringEntry =
        contractOf(proverWith(ctx, java.math.BigInteger.valueOf(9009L))).hashedPropBytes
      val fullSet = (0 until MAX_ACTIVE).map { i =>
        if (i == 3) retiringEntry
        else contractOf(proverWith(ctx, java.math.BigInteger.valueOf(20000L + i))).hashedPropBytes
      }
      measure("emission", "activate (rotating, spends a retirement)",
        txs.genActivate(ctx,
          emissionInputAt(ctx, fullSet, 0L, 1L, collatTokens = collatSupply - MAX_ACTIVE),
          emissionConfigInput(ctx),
          inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permitAt(1L)), ctx, 1),
          retiring = Some(inputAt(proofOfSpendUTXO(ctx, retiringEntry), ctx, 2) -> 3),
          funding = Seq.empty, feeOutput = None))

      // Clear: the queue head belongs to a lender who already holds a slot, so it can never be
      // activated and anyone may take its principal as change.
      measure("emission", "clear (drop an unactivatable head)",
        txs.genClear(ctx, emissionInputAt(ctx, Seq(setEntry(lenderProver)), 0L, 1L),
          emissionConfigInput(ctx),
          inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permitAt(1L)), ctx, 1),
          Seq.empty, Some(UTXO.feeBox(txFee))))
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  4. LithosDex — the only high-frequency transactions here
  // ══════════════════════════════════════════════════════════════════════════

  private var actorSystem: Option[ActorSystem] = None

  private def system: ActorSystem = actorSystem.getOrElse {
    val s = ActorSystem("transaction-cost-sizing-spec")
    actorSystem = Some(s)
    s
  }

  @volatile private var nextFunding: Seq[InputUTXO] = Seq.empty

  /**
   * The wallet boundary `WalletSelector` blocks on, and nothing else.
   *
   * The DEX builders reserve through the real selector, which is an ask to `WalletManager`. Standing
   * a manager up would drag a node fixture in for no gain: what is being measured is the transaction
   * the builder produces once it has its funding, not how the funding was chosen.
   */
  private class FundingResponder extends Actor {
    override def receive: Receive = {
      case r: RetrieveInputs => sender() ! WalletInputs(nextFunding, r.reservationId)
      case _ => ()
    }
  }

  private lazy val selector: WalletSelector = {
    val ref: ActorRef = system.actorOf(Props(new FundingResponder))
    WalletSelector(ref, 30.seconds, ExecutionContext.global)
  }

  private def funded[A <: LDFundedTx](funding: InputUTXO)(build: => A): A = {
    nextFunding = Seq(funding)
    val built = build
    built.reservation.release() // nothing is broadcast here, so close the lease the builder opened
    built
  }

  /**
   * Six of the seven. `refresh` is left out: it is only valid past `REFRESH_AGE`, which the offline
   * fixture's tip predates, and reaching it needs a proxied context rather than a different fixture.
   */
  property("dex: the six LithosDex transactions that move funds") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val ids = DexFixtures.ids(ctx)
      val tokenY = ids._4
      val headroom = Parameters.MinFee * 3
      val shares = 10000000000L
      val owner = LithosDexSpecBase.ownerNFT
      printHeader()

      val pool = DexFixtures.livePool(ctx, traded = false)
      val tradedPool = DexFixtures.livePool(ctx, traded = true)
      val provision = DexFixtures.liveProvision(ctx, shares, BigInt(0), BigInt(0), owner, 1)

      val swapIn = Parameters.OneErg
      val swapQuote = LDLiquidityPool(pool).simSwap(swapIn, ergIn = true)
      measure("dex", "swap",
        funded(walletInput(ctx, wallet, swapIn + headroom, index = 10)) {
          LithosDexTransactions.swap(ctx, wallet, selector, pool, swapIn,
            ergIn = true, minOutput = swapQuote.amountOut)
        }.tx)

      val depositQuote = LDLiquidityPool(pool).simDepositForShares(shares)
      val depositErg = depositQuote.amountX + DexFixtures.provisionMin +
        LithosDexTransactions.OWNER_BOX_VALUE + headroom
      measure("dex", "deposit (mints an ownership NFT)",
        funded(walletInput(ctx, wallet, depositErg,
          Seq(Token(tokenY, depositQuote.amountY)), index = 10)) {
          LithosDexTransactions.deposit(ctx, wallet, selector, pool, shares)
        }.tx)

      measure("dex", "redeem (burns it again)",
        funded(walletInput(ctx, wallet, headroom, Seq(Token(owner, 1L)), index = 10)) {
          LithosDexTransactions.redeem(ctx, wallet, selector, pool, provision)
        }.tx)

      val flushVault = DexFixtures.liveVault(ctx, DexFixtures.vaultMin, 0L, BigInt(0), BigInt(0), 1)
      measure("dex", "flush (pool fees into the vault)",
        funded(walletInput(ctx, wallet, headroom, index = 10)) {
          LithosDexTransactions.flush(ctx, wallet, selector, tradedPool, flushVault)
        }.tx)

      val owedX = DexFixtures.owedOn(shares, BigInt(0), DexFixtures.tradedAccX)
      val owedY = DexFixtures.owedOn(shares, BigInt(0), DexFixtures.tradedAccY)
      val claimVault = DexFixtures.liveVault(ctx,
        DexFixtures.vaultMin + owedX + DexFixtures.surplus, owedY + DexFixtures.surplus,
        DexFixtures.tradedAccX, DexFixtures.tradedAccY, 0)
      measure("dex", "claim (one provision's accrued fees)",
        funded(walletInput(ctx, wallet, headroom, Seq(Token(owner, 1L)), index = 10)) {
          LithosDexTransactions.claim(ctx, wallet, selector, claimVault, Seq(provision))
        }.tx)

      // The widest of the six: it grows the provision, flushes the pool and settles the vault in one
      // transaction, so three protocol boxes are spent and recreated rather than one.
      val resizeVault = DexFixtures.liveVault(ctx, DexFixtures.vaultMin, 0L, BigInt(0), BigInt(0), 2)
      val newShares = shares + 5000000000L
      val resizeQuote = LDLiquidityPool(tradedPool)
        .simResize(shares, newShares, provision.entryX, provision.entryY)
      measure("dex", "resize (grow, flush and settle)",
        funded(walletInput(ctx, wallet, resizeQuote.amountX + headroom,
          Seq(Token(owner, 1L), Token(tokenY, resizeQuote.amountY)), index = 10)) {
          LithosDexTransactions.resize(ctx, wallet, selector, tradedPool, resizeVault, provision,
            newShares)
        }.tx)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  5. the fraud proof — the largest transaction the protocol produces
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * A slash carries the accused's NISP three times over: once in the lookup proof and twice in the
   * removal, which re-links the neighbour and so carries that miner's NISP too. Nothing else here is
   * close to it on either axis, and it is the only shape whose cost is dominated by a script rather
   * than by bytes.
   *
   * Built by the contract harness rather than by `genFraudProofTransform`, which reaches `Globals`.
   * The transaction is the same shape either way: `INPUTS(0)` the evaluation box, `INPUTS(1)` the
   * prover's box carrying the two proofs, and FP_CONTROL as the data input.
   */
  property("fraud proof: a slash at the NISP ceiling") {
    withCtx { ctx =>
      val score = 100000L
      val pk = NispFixtures.pkOf(miner(ctx))
      val at = rollupBlock(ctx)
      printHeader()

      // The worst honest share: a txProof at CONST_TX_PROOF_MAX and the deepest Merkle proof the
      // format admits, which is the shape CONST_NISP_MAX was derived from.
      val maximal = TransactionProof(
        Array.fill(LFSMHelpers.TX_PROOF_MAX - 2 - 64)(1.toByte), Array.fill(64)(2.toByte))
      val shares = (0 until 10).map(i =>
        SuperShare(NispFixtures.header((at - i).toInt, pk, 1700000000000L + i).bytes, maximal,
          NispFixtures.levels(LFSMHelpers.NUM_LVLS_MAX)))

      // Malformed without being smaller: the declared txProof size is rewritten to 2, far under
      // CONST_TX_PROOF_MIN, and every other byte stays where it was.
      val broken = shares.map { s =>
        val bytes = NispFixtures.shareWithHeader(s, s.headerBytes)
        val sizeAt = 4 + s.headerBytes.length
        bytes.slice(0, sizeAt) ++ Array(0.toByte, 2.toByte) ++ bytes.drop(sizeAt + 2)
      }
      val nisp = NispFixtures.rawNisp(score, broken)

      val f = fraud(ctx, fpInvalidFormat(ctx), nisp, score)
      val m = measure("fraudproof", s"FP_InvalidFormat, ${nisp.length}B NISP",
        provesFraud(f.prover, fraudTx(f)()))

      println(s"[budget] the accused's NISP is ${nisp.length} bytes against CONST_NISP_MAX " +
        s"${LFSMHelpers.NISP_MAX}; the bystander's is $realisticNispSize, so a tree of ceiling-sized " +
        "NISPs would push the removal proof further still")
      println(f"[budget] one slash is ${100.0 * m.bytes / maxBlockSize}%.1f%% of a block by size " +
        f"and ${100.0 * m.cost / maxBlockCost}%.1f%% by cost")

      nisp.length should be < LFSMHelpers.NISP_MAX
    }
  }

  property("fraud proof: a slash at the NISP ceiling for MalformedGenesis") {
    withCtx { ctx =>
      val score = 100000L
      val pk = NispFixtures.pkOf(miner(ctx))
      val at = rollupBlock(ctx)
      printHeader()

      // The worst honest share: a txProof at CONST_TX_PROOF_MAX and the deepest Merkle proof the
      // format admits, which is the shape CONST_NISP_MAX was derived from.
      val maximal = TransactionProof(
        Array.fill(LFSMHelpers.TX_PROOF_MAX - 2 - 64)(1.toByte), Array.fill(64)(2.toByte))
      val shares = (0 until 10).map(i =>
        SuperShare(NispFixtures.header((at - i).toInt, pk, 1700000000000L + i).bytes, maximal,
          NispFixtures.levels(LFSMHelpers.NUM_LVLS_MAX)))

      // Malformed without being smaller: the declared txProof size is rewritten to 2, far under
      // CONST_TX_PROOF_MIN, and every other byte stays where it was.
      val broken = shares.map { s =>
        val bytes = NispFixtures.shareWithHeader(s, s.headerBytes)
        val sizeAt = 4 + s.headerBytes.length
        bytes.slice(0, sizeAt) ++ Array(0.toByte, 2.toByte) ++ bytes.drop(sizeAt + 2)
      }
      val nisp = NispFixtures.rawNisp(score, broken)

      val f = fraud(ctx, fpMalformedGenesis(ctx), nisp, score)
      val m = measure("fraudproof", s"FP_MalformedGenesis, ${nisp.length}B NISP",
        provesFraud(f.prover, fraudTx(f)()))

      println(s"[budget] the accused's NISP is ${nisp.length} bytes against CONST_NISP_MAX " +
        s"${LFSMHelpers.NISP_MAX}; the bystander's is $realisticNispSize, so a tree of ceiling-sized " +
        "NISPs would push the removal proof further still")
      println(f"[budget] one slash is ${100.0 * m.bytes / maxBlockSize}%.1f%% of a block by size " +
        f"and ${100.0 * m.cost / maxBlockCost}%.1f%% by cost")

      nisp.length should be < LFSMHelpers.NISP_MAX
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  the table
  // ══════════════════════════════════════════════════════════════════════════

  override def afterAll(): Unit = {
    actorSystem.foreach(_.terminate())
    if (measured.nonEmpty) {
      println()
      println(s"[summary] ${measured.size} transactions, dearest first, against a " +
        s"$maxBlockSize-byte / $maxBlockCost-cost block")
      println(s"[summary] $header")
      measured.sortBy(-_.cost).foreach(m => println(row("summary", m)))

      val costBound = measured.count(_.binds == "cost")
      println(s"[summary] $costBound of ${measured.size} run out of block COST before block SIZE")
      println(s"[summary] widest:  ${measured.maxBy(_.bytes).name} at ${measured.map(_.bytes).max} bytes")
      println(s"[summary] dearest: ${measured.maxBy(_.cost).name} at ${measured.map(_.cost).max} cost")

      // The finding the table is for. Cost barely moves across shapes that differ 100-fold in
      // bytes, because it is dominated by the per-transaction and per-input floor rather than by
      // what the scripts do — so on the cost axis a block holds about the same number of ANYTHING
      // this client builds, and only the NISP-carrying transactions are held back by size.
      val cheapest = measured.map(_.cost).min
      val dearest = measured.map(_.cost).max
      println(s"[summary] cost spans $cheapest..$dearest across shapes spanning " +
        s"${measured.map(_.bytes).min}..${measured.map(_.bytes).max} bytes — a block holds " +
        s"${maxBlockCost / dearest}-${maxBlockCost / cheapest} of any of them by cost")

      // What a block of nothing but this client's own work looks like, on the shape that dominates it.
      val submissions = measured.filter(_.name.startsWith("submit"))
      if (submissions.nonEmpty) {
        val worst = submissions.maxBy(_.cost)
        println(s"[summary] a block of nothing but the worst NISP submission holds " +
          s"${math.min(worst.perBlockBySize, worst.perBlockByCost)} of them " +
          s"(${worst.perBlockBySize} by size, ${worst.perBlockByCost} by cost)")
      }
    }
    super.afterAll()
  }
}

/**
 * The LithosDex box fixtures, reached by delegation rather than by mixing.
 *
 * `LithosDexSpecBase` and `EmissionSpecBase` both define `bytesValue` and `guardContract`, so one
 * suite cannot inherit both. What the DEX measurements need is exposed here instead, pointed at the
 * same cached production contracts the builders themselves compile against — a pool box under a
 * stand-in script is not recognised by the pool NFT check at all. `PoolState` stays inside, since it
 * is protected and cannot cross this boundary.
 */
private object DexFixtures extends LithosDexSpecBase {

  override protected def ldContracts(ctx: BlockchainContext): LithosDexSpecBase.LDSet = {
    val c = DexContracts(ctx)
    LithosDexSpecBase.LDSet(c.provisionLogic, c.provisionGuard, c.feeVault, c.liquidityPool)
  }

  /** Pool NFT, vault NFT, provision token and token Y, as the deployed helpers name them. */
  def ids(ctx: BlockchainContext): (ErgoId, ErgoId, ErgoId, ErgoId) = (
    LDHelpers.getPoolNFT(ctx.getNetworkType),
    LDHelpers.getVaultNFT(ctx.getNetworkType),
    LDHelpers.getProvToken(ctx.getNetworkType),
    LDHelpers.getTokenY(ctx.getNetworkType))

  def provisionMin: Long = PROVISION_MIN

  def vaultMin: Long = VAULT_MIN

  def surplus: Long = vaultSurplus

  def tradedAccX: BigInt = traded.accX

  def tradedAccY: BigInt = traded.accY

  def owedOn(shares: Long, entry: BigInt, acc: BigInt): Long = owed(shares, entry, acc)

  /** `traded` has both accumulators and both pending balances non-zero; `genesis` has neither. */
  def livePool(ctx: BlockchainContext, traded: Boolean, index: Int = 0): InputUTXO = {
    val i = ids(ctx)
    val state = if (traded) this.traded else genesis
    inputAt(poolUTXO(ctx, state, poolNFTId = i._1, tokenYId = i._4, provTokenId = i._3), ctx, index)
  }

  def liveVault(ctx: BlockchainContext, balanceX: Long, balanceY: Long,
                accX: BigInt, accY: BigInt, index: Int): InputUTXO = {
    val i = ids(ctx)
    inputAt(vaultUTXO(ctx, balanceX, balanceY, accX, accY, vaultNFTId = i._2, tokenYId = i._4),
      ctx, index)
  }

  def liveProvision(ctx: BlockchainContext, shares: Long, entryX: BigInt, entryY: BigInt,
                    owner: ErgoId, index: Int): LDBoxes.Provision =
    LDBoxes.Provision(
      inputAt(provisionUTXO(ctx, entryX, entryY, owner, shares, provTokenId = ids(ctx)._3),
        ctx, index),
      entryX, entryY, owner, shares)
}
