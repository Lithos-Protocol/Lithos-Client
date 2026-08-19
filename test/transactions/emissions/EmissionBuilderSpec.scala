package transactions.emissions

import configs.EmissionConfig
import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.ProtocolContracts
import transactions.wallet.{WalletReservation, WalletSource}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * The emission builders against the contracts that have to accept them.
 *
 * `EmissionSpec` asks whether the contracts accept a correctly shaped spend, assembling that shape by
 * hand. This asks the other half: whether `EmissionTransactions` produces it. The two drift in either
 * direction — a contract change leaves the hand-built spec passing, and a builder change leaves it
 * passing too, because it never runs the builder.
 *
 * Signing is the assertion. `prover.sign` executes the guard, which authenticates the emission box and
 * then runs the emission logic from context variable 64 and the enforcer from 65, so one successful
 * signature asserts every conjunct at once — against the contract rather than against a restatement of
 * it in the test.
 *
 * Everything is built at the PRODUCTION contracts (`ProtocolContracts`), not the spec base's, because
 * that is what the builder puts in those context variables: the guard compares their hashes against
 * its own constants, so a fixture compiled with the spec base's collateral stand-in would be rejected
 * for a reason that has nothing to do with the builder.
 */
class EmissionBuilderSpec extends AnyPropSpec with EmissionSpecBase with MockitoSugar {

  private val txFee = Parameters.MinFee

  private val emissionConfig: EmissionConfig = EmissionConfig.Default.copy(txFee = txFee)

  /** `genJoin`, `genActivate` and `genClear` take their funding as an argument, so this never runs. */
  private object UnusedWallet extends WalletSource {
    def reserve(value: Long, tokens: Seq[Token]): WalletReservation =
      throw new IllegalStateException("a builder under test asked the wallet for funds")
    def reserveCovering(value: Long): WalletReservation =
      throw new IllegalStateException("a builder under test asked the wallet for one input")
    def reserveKnown(inputs: Seq[InputUTXO]): WalletReservation =
      throw new IllegalStateException("a builder under test asked the wallet to hold known change")
    def giveBack(boxes: Seq[InputUTXO]): Unit = ()
  }

  /**
   * A context, this client's wallet, and the builder over them.
   *
   * The prover comes from a mnemonic because `NodeWallet` reads `getEip3Addresses`, which a
   * `withDLogSecret` prover does not have.
   */
  private def withBuilder[A](f: (BlockchainContext, NodeWallet, EmissionTransactions) => A): A = {
    val (nodeCtx, api, wallet) = FakeNodeContext(mock[NodeApi], numAddresses = 1)
    nodeCtx.getClient.execute { ctx =>
      f(ctx, wallet, new EmissionTransactions(wallet, api, emissionConfig, UnusedWallet))
    }
  }

  private def protocol(ctx: BlockchainContext) = ProtocolContracts(ctx)

  /**
   * The emission box under the production guard, which is the script the builder carries through.
   *
   * The queue token count is `supply - tail`, not the genesis supply: every queue box in existence
   * took one on the way out. A fixture that holds the full supply AND a queue box models a state a
   * Join already ruled out, and the `+1` the spend puts back overflows `Long.MaxValue` — which
   * surfaces as a token error and reads like a builder bug.
   */
  private def emissionAt(ctx: BlockchainContext,
                         currentBlock: Int,
                         lenderSet: Seq[Array[Byte]],
                         head: Long,
                         tail: Long,
                         lit: Long,
                         collatTokens: Long = collatSupply): InputUTXO =
    inputAt(emissionUTXO(ctx, currentBlock = currentBlock, lenderSet = lenderSet, head = head,
      tail = tail, lit = lit, queueTokens = queueSupply - (tail - head),
      collatTokens = collatTokens, contract = protocol(ctx).guard), ctx, 0)

  /** The config box the guard reads its enforcer hash and rollup hash out of, as dataInputs(0). */
  private def config(ctx: BlockchainContext): InputUTXO =
    configInput(ctx, configBox(ctx, protocol(ctx).enforcer,
      rollupHash = protocol(ctx).holding.hashedPropBytes))

  private def funding(ctx: BlockchainContext, wallet: NodeWallet, permit: Long,
                      value: Long = 10L * Parameters.OneErg): InputUTXO = {
    val tokens = if (permit > 0) Seq(Token(LFSMHelpers.LIT_ID, permit)) else Seq.empty[Token]
    inputAt(UTXO(wallet.contract, value, tokens), ctx, 1)
  }

  private def signs(tx: => SignedTransaction): SignedTransaction =
    try tx catch {
      case ex: Throwable =>
        fail(s"the contracts rejected the builder's transaction: ${ex.getMessage}", ex)
    }

  // ─── Join ─────────────────────────────────────────────────────────────────

  property("genJoin produces a queue box the emission stack accepts") {
    withBuilder { (ctx, wallet, txs) =>
      val permit = permitAt(0L)
      val signed = signs(txs.genJoin(
        ctx,
        emissionAt(ctx, currentBlock = 0, lenderSet = Seq.empty, head = 0L, tail = 0L, lit = litSupply),
        config(ctx),
        wallet.p2pk,
        Seq(funding(ctx, wallet, permit))))

      val queueOut = signed.getOutputsToSpend.get(1)
      withClue("the queue box carries the principal floor and the thermostat's permit: ") {
        queueOut.getValue shouldEqual principalFloor
        queueOut.getTokens.get(1).getValue shouldEqual permit
      }
    }
  }

  // ─── Clear ────────────────────────────────────────────────────────────────

  property("genClear funded takes the head box and pays its own fee") {
    withBuilder { (ctx, wallet, txs) =>
      // A Clear is only valid when the head box's lender ALREADY holds a slot — that duplicate key
      // is the whole reason the box can never be activated and the queue would otherwise stall.
      val lenderProver = miner(ctx)
      val permit = permitAt(1L)
      val signed = signs(txs.genClear(
        ctx,
        emissionAt(ctx, currentBlock = 0, lenderSet = Seq(setEntry(lenderProver)), head = 0L,
          tail = 1L, lit = litSupply),
        config(ctx),
        inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permit), ctx, 1),
        Seq.empty,
        Some(UTXO.feeBox(txFee))))

      withClue("the forfeited principal comes back as change, which is the whole incentive: ") {
        // outputs: emission, fee, change
        signed.getOutputsToSpend.size shouldEqual 3
        signed.getOutputsToSpend.get(2).getValue shouldEqual (principalFloor - txFee)
      }
    }
  }

  property("genClear fee-less balances with no wallet input at all") {
    withBuilder { (ctx, wallet, txs) =>
      val lenderProver = miner(ctx)
      val signed = signs(txs.genClear(
        ctx,
        emissionAt(ctx, currentBlock = 0, lenderSet = Seq(setEntry(lenderProver)), head = 0L,
          tail = 1L, lit = litSupply),
        config(ctx),
        inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permitAt(1L)), ctx, 1),
        Seq.empty,
        None))

      withClue("no fee output means TxBuilder has nothing to fold a short change into: ") {
        signed.getOutputsToSpend.size shouldEqual 2
        signed.getOutputsToSpend.get(1).getValue shouldEqual principalFloor
      }
    }
  }

  // ─── Activate ─────────────────────────────────────────────────────────────

  property("genActivate bootstrapping mints a collateral box the enforcer accepts") {
    withBuilder { (ctx, wallet, txs) =>
      val lenderProver = miner(ctx)
      val permit = permitAt(1L)
      val signed = signs(txs.genActivate(
        ctx,
        emissionAt(ctx, currentBlock = 0, lenderSet = Seq.empty, head = 0L, tail = 1L, lit = litSupply),
        config(ctx),
        inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permit), ctx, 1),
        retiring = None,
        funding = Seq.empty,
        feeOutput = None))

      val collatOut = signed.getOutputsToSpend.get(1)
      withClue("the queue box's whole principal carries into the collateral box, so change is zero: ") {
        collatOut.getValue shouldEqual principalFloor
        signed.getOutputsToSpend.size shouldEqual 2
      }
      withClue("the collateral box is at the script this build compiles: ") {
        collatOut.getErgoTree.bytesHex shouldEqual protocol(ctx).collateral.ergoTreeHex
      }
    }
  }

  property("genActivate rotating frees a slot with a proof of spend") {
    withBuilder { (ctx, wallet, txs) =>
      val lenderProver = miner(ctx)
      val retiringEntry = contractOf(proverWith(ctx, java.math.BigInteger.valueOf(9009L))).hashedPropBytes
      // A full active set, so the builder must consume a retirement rather than append.
      val fullSet = (0 until MAX_ACTIVE).map { i =>
        if (i == 3) retiringEntry
        else contractOf(proverWith(ctx, java.math.BigInteger.valueOf(20000L + i))).hashedPropBytes
      }
      val posBox = inputAt(proofOfSpendUTXO(ctx, retiringEntry), ctx, 2)

      val signed = signs(txs.genActivate(
        ctx,
        emissionAt(ctx, currentBlock = 0, lenderSet = fullSet, head = 0L, tail = 1L, lit = litSupply,
          collatTokens = collatSupply - MAX_ACTIVE),
        config(ctx),
        inputAt(queueUTXO(ctx, lenderProver, position = 0L, permit = permitAt(1L)), ctx, 1),
        retiring = Some(posBox -> 3),
        funding = Seq.empty,
        feeOutput = None))

      withClue("the retirement's value folds into the collateral box, so change stays zero: ") {
        signed.getOutputsToSpend.get(1).getValue shouldEqual (principalFloor + posBox.value)
        signed.getOutputsToSpend.size shouldEqual 2
      }
    }
  }
}
