package transactions.rollups

import contracts.specs.rollup.RollupSpecBase
import lfsm.LFSMPhase
import lfsm.states.NISPTree
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.{ErgoId, JavaHelpers}
import org.scalatest.propspec.AnyPropSpec
import support.FakeNodeContext
import transactions.rollups.TransactionMessages.LatestRollup
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

/**
 * Which outputs of a batch's initial transaction are the pre-created wallet boxes.
 *
 * `SubmissionHandler` builds one transaction that carries a fee output for itself plus one wallet
 * output per remaining rollup, then hands each of those out as the input that pays that rollup's fee.
 * Finding them used to be `slice(2, …)` — right for four of the five builders, and wrong for a Payout
 * that pays a miner as well as recreating its box. The first rollup got the fee-proposition box,
 * which this client cannot sign for, and every other rollup was shifted onto a neighbour's box, which
 * can be worth less than its own fee. One failed transaction per batch, reading as a signing error.
 *
 * The layouts here come from running the REAL builders rather than from restating them, which is the
 * whole point: a spec that hard-codes "Payout puts two outputs in front" would pass while a builder
 * drifted underneath it.
 *
 * The rollup input is a box at the prover's own key rather than at the rollup contract. Signing
 * executes INPUT scripts, never output ones, so this exercises the builder's output construction
 * without needing a satisfiable contract state — which is `PayoutSpec`'s job and is not repeated
 * here.
 */
class FeeAllocationSpec extends AnyPropSpec with RollupSpecBase {

  private val feeValue = 1000000L

  /** Distinct values, so an allocation can be identified by which one it got. */
  private val walletOutValues = Seq(2000000L, 3000000L, 4000000L)

  /** What `mkFeeOutputs` produces: the transaction's own fee box, then one box per other rollup. */
  private def feeOutputs(owner: Contract): Seq[UTXO] =
    UTXO.feeBox(feeValue) +: walletOutValues.map(v => UTXO(owner, v))

  private def treeFor(miner: Array[Byte]) = treeWith(Seq(miner -> nispBytes(4000L, realisticNispSize)))

  private def nispTree(ctx: BlockchainContext, miner: Array[Byte], totalScore: Long, reward: Long) =
    NISPTree(treeFor(miner), numMiners = 1, totalScore = BigInt(totalScore), currentPeriod = Some(100L),
      totalReward = reward, startHeight = 1, hasMiner = true, phase = LFSMPhase.PAYOUT,
      blockId = "bb" * 32, utxoId = dummyTxId)

  /**
   * A context and the client's own wallet over it.
   *
   * The prover has to come from a mnemonic: `NodeWallet` reads `getEip3Addresses`, and the
   * `withDLogSecret` prover the contract specs use has none, so every member throws on construction.
   */
  private def withWallet[A](f: (BlockchainContext, NodeWallet) => A): A = {
    val (nodeCtx, _, wallet) = FakeNodeContext(numAddresses = 1)
    nodeCtx.getClient.execute(ctx => f(ctx, wallet))
  }

  /**
   * A rollup box carrying the registers the builder reads, at the prover's own script.
   * R4 tree, R5 miner count, R6 total score, R7 the reward the payout divides up.
   */
  private def rollupInput(ctx: BlockchainContext, owner: Contract, tree: NISPTree,
                          value: Long, reward: Long): InputUTXO =
    UTXO(owner, value, Seq.empty, Seq(
      tree.dictionary.ergoValue,
      ErgoValue.of(1),
      ErgoValue.of(tree.totalScore.bigInteger),
      ErgoValue.of(reward)))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

  /**
   * The wallet input that funds the fee outputs. In production this is `rollupWalletInputs`; without
   * it the transaction cannot balance, since the rollup box only covers its own outputs.
   */
  private def funding(ctx: BlockchainContext, owner: Contract): InputUTXO =
    UTXO(owner, 20000000L).toInput(ctx, ErgoId.create(dummyTxId), 1.toShort)

  private def located(signed: SignedTransaction): Seq[Long] =
    SubmissionHandler.walletOutputsAfterFee(
      JavaHelpers.toIndexedSeq(signed.getOutputsToSpend).map(InputUTXO(_)),
      walletOutValues.size).map(_.value)

  // ─── the regression ───────────────────────────────────────────────────────

  property("a Payout that recreates its box still maps every rollup to a wallet output") {
    withWallet { (ctx, wallet) =>
      val tree = nispTree(ctx, wallet.contract.hashedPropBytes, totalScore = 4000L, reward = 10000000L)
      // Value far above the payout, so the box recreates itself — the branch with a miner output
      // AND a recreated box in front of the fee run, which is the one the fixed index got wrong.
      val in = rollupInput(ctx, wallet.contract, tree, value = 60000000L, reward = 10000000L)

      val signed = RollupTransactions.genPayout(
        ctx, wallet, in, Seq(funding(ctx, wallet.contract)), LatestRollup(in, tree),
        feeOutputs(wallet.contract))

      withClue("two outputs precede the fee box here, one in every other builder: ") {
        located(signed) shouldEqual walletOutValues
      }
    }
  }

  property("a Payout that drains its box maps every rollup to a wallet output") {
    withWallet { (ctx, wallet) =>
      val tree = nispTree(ctx, wallet.contract.hashedPropBytes, totalScore = 4000L, reward = 10000000L)
      // The whole box goes to the miner, so there is no recreated box — one output before the fee
      // run, like the transforms. This branch was always correct, which is why the defect was
      // intermittent rather than constant.
      val in = rollupInput(ctx, wallet.contract, tree, value = 10000000L, reward = 10000000L)

      val signed = RollupTransactions.genPayout(
        ctx, wallet, in, Seq(funding(ctx, wallet.contract)), LatestRollup(in, tree),
        feeOutputs(wallet.contract))

      located(signed) shouldEqual walletOutValues
    }
  }

  property("a transform maps every rollup to a wallet output") {
    withWallet { (ctx, wallet) =>
      val tree = nispTree(ctx, wallet.contract.hashedPropBytes, totalScore = 4000L, reward = 0L)
      val in = rollupInput(ctx, wallet.contract, tree, value = 60000000L, reward = 0L)

      val signed = RollupTransactions.genHoldingTransform(ctx, wallet, in,
        Seq(funding(ctx, wallet.contract)), feeOutputs(wallet.contract))

      located(signed) shouldEqual walletOutValues
    }
  }

  // ─── the refusal ──────────────────────────────────────────────────────────

  property("no fee output means no allocations rather than a guess") {
    withWallet { (ctx, wallet) =>
      val outputs = Seq(60000000L, 2000000L).map(v =>
        UTXO(wallet.contract, v).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort))

      withClue("mapping rollups onto arbitrary outputs is worse than mapping none: ") {
        SubmissionHandler.walletOutputsAfterFee(outputs, 2) shouldBe empty
      }
    }
  }
}
