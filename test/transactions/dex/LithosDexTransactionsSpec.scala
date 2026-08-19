package transactions.dex

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import contracts.specs.lithosdex.LithosDexSpecBase
import lithosdex.{LDHelpers, LDLiquidityPool}
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import support.FakeNodeContext
import transactions.dex.LDBoxes.{Provision => LiveProvision}
import transactions.wallet.WalletMessages.{ReleaseInputs, RetrieveInputs, WalletInputs}
import transactions.wallet.WalletSelector
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * The production [[LithosDexTransactions]] builders against the deployed four-contract wiring.
 *
 * The contract suites deliberately build their transactions by hand so they can perturb individual
 * fields. This suite covers the other seam: production must select the right wallet requirements,
 * construct the exact input/output shape, attach the real context variables and sign the result. It
 * reuses their box/register fixture rather than maintaining a second off-chain description here.
 */
class LithosDexTransactionsSpec extends TestKit(ActorSystem("lithosdex-transactions-spec"))
  with AnyFlatSpecLike with BeforeAndAfterAll with LithosDexSpecBase {

  private implicit val ec: ExecutionContext = system.dispatcher

  private val Headroom: Long = Parameters.MinFee * 3
  private val Shares: Long = 10000000000L

  private lazy val fake = FakeNodeContext(numAddresses = 2)

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** Point the shared contract fixture at the same cached production stack the builders use. */
  override protected def ldContracts(ctx: BlockchainContext): LithosDexSpecBase.LDSet = {
    val contracts = DexContracts(ctx)
    LithosDexSpecBase.LDSet(
      contracts.provisionLogic,
      contracts.provisionGuard,
      contracts.feeVault,
      contracts.liquidityPool)
  }

  private case class Ids(pool: ErgoId, vault: ErgoId, provision: ErgoId, tokenY: ErgoId)

  private def ids(ctx: BlockchainContext): Ids = Ids(
    LDHelpers.getPoolNFT(ctx.getNetworkType),
    LDHelpers.getVaultNFT(ctx.getNetworkType),
    LDHelpers.getProvToken(ctx.getNetworkType),
    LDHelpers.getTokenY(ctx.getNetworkType))

  private def withProduction[A](f: (BlockchainContext, NodeWallet) => A): A =
    fake._1.getClient.execute(ctx => f(ctx, fake._3))

  private def livePool(ctx: BlockchainContext, state: PoolState, index: Int = 0): InputUTXO = {
    val i = ids(ctx)
    inputAt(poolUTXO(ctx, state,
      poolNFTId = i.pool, tokenYId = i.tokenY, provTokenId = i.provision), ctx, index)
  }

  private def liveVault(ctx: BlockchainContext,
                        balanceX: Long,
                        balanceY: Long,
                        accX: BigInt,
                        accY: BigInt,
                        index: Int): InputUTXO = {
    val i = ids(ctx)
    inputAt(vaultUTXO(ctx, balanceX, balanceY, accX, accY,
      vaultNFTId = i.vault, tokenYId = i.tokenY), ctx, index)
  }

  private def liveProvision(ctx: BlockchainContext,
                            shares: Long,
                            entryX: BigInt,
                            entryY: BigInt,
                            owner: ErgoId,
                            index: Int,
                            createdHeight: Option[Int] = None): LiveProvision = {
    val shaped = provisionUTXO(ctx, entryX, entryY, owner, shares,
      provTokenId = ids(ctx).provision)
    val aged = createdHeight.fold(shaped)(shaped.setCreationHeight)
    LiveProvision(inputAt(aged, ctx, index), entryX, entryY, owner, shares)
  }

  private def walletInput(ctx: BlockchainContext,
                          wallet: NodeWallet,
                          value: Long,
                          tokens: Seq[Token],
                          index: Int): InputUTXO =
    inputAt(UTXO(wallet.contract, value, tokens), ctx, index)

  /**
   * Exercise the real blocking WalletSelector without a second selector implementation. The probe
   * acts only as the WalletManager boundary: it verifies the requested reservation and returns the
   * signable box the production builder must consume.
   */
  private def buildFunded[A <: LDFundedTx](expectedValue: Long,
                                            expectedTokens: Seq[Token],
                                            funding: InputUTXO)
                                           (build: WalletSelector => A): A = {
    val manager = TestProbe()
    val selector = WalletSelector(manager.ref, 30.seconds, ec)
    val pending = Future(build(selector))

    val request = manager.expectMsgType[RetrieveInputs](30.seconds)
    request.erg shouldBe expectedValue
    request.tokens shouldEqual expectedTokens
    request.trackUsed shouldBe true
    request.reservationId should not be empty
    manager.reply(WalletInputs(Seq(funding), request.reservationId))

    val built = Await.result(pending, 60.seconds)
    built.reservation.inputs shouldEqual Seq(funding)

    // Nothing is submitted in an offline builder spec, so close the real reservation lifecycle.
    built.reservation.release()
    manager.expectMsg(ReleaseInputs(request.reservationId))
    built
  }

  "LithosDexTransactions.swap" should "reserve, build and sign the production swap" in withProduction {
    (ctx, wallet) =>
      val amountIn = Parameters.OneErg
      val pool = livePool(ctx, genesis)
      val quote = LDLiquidityPool(pool).simSwap(amountIn, ergIn = true)
      val funding = walletInput(ctx, wallet, amountIn + Headroom, Seq.empty, 10)

      val built = buildFunded(amountIn + Headroom, Seq.empty, funding) { selector =>
        LithosDexTransactions.swap(ctx, wallet, selector, pool, amountIn,
          ergIn = true, minOutput = quote.amountOut)
      }

      built.quote shouldEqual quote
  }

  "LithosDexTransactions.deposit" should "reserve, mint ownership and sign the production deposit" in withProduction {
    (ctx, wallet) =>
      val pool = livePool(ctx, genesis)
      val quote = LDLiquidityPool(pool).simDepositForShares(Shares)
      val tokenNeed = Seq(Token(ids(ctx).tokenY, quote.amountY))
      val ergNeed = quote.amountX + PROVISION_MIN + LithosDexTransactions.OWNER_BOX_VALUE + Headroom
      val funding = walletInput(ctx, wallet, ergNeed, tokenNeed, 10)

      val built = buildFunded(ergNeed, tokenNeed, funding) { selector =>
        LithosDexTransactions.deposit(ctx, wallet, selector, pool, Shares)
      }

      built.quote shouldEqual quote
      built.ownerNFT shouldEqual pool.id
      built.provisionBoxId should not be empty
  }

  "LithosDexTransactions.redeem" should "reserve the ownership NFT and sign the production redemption" in withProduction {
    (ctx, wallet) =>
      val pool = livePool(ctx, genesis)
      val owner = LithosDexSpecBase.ownerNFT
      val provision = liveProvision(ctx, Shares, BigInt(0), BigInt(0), owner, 1)
      val tokenNeed = Seq(Token(owner, 1L))
      val funding = walletInput(ctx, wallet, Headroom, tokenNeed, 10)
      val quote = LDLiquidityPool(pool).simRedeem(Shares)

      val built = buildFunded(Headroom, tokenNeed, funding) { selector =>
        LithosDexTransactions.redeem(ctx, wallet, selector, pool, provision)
      }

      built.quote shouldEqual quote
  }

  "LithosDexTransactions.flush" should "reserve and sign the production pool-to-vault flush" in withProduction {
    (ctx, wallet) =>
      val pool = livePool(ctx, traded)
      val vault = liveVault(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0), 1)
      val funding = walletInput(ctx, wallet, Headroom, Seq.empty, 10)

      val built = buildFunded(Headroom, Seq.empty, funding) { selector =>
        LithosDexTransactions.flush(ctx, wallet, selector, pool, vault)
      }

      built.flushedX shouldBe traded.pendingX
      built.flushedY shouldBe traded.pendingY
  }

  "LithosDexTransactions.claim" should "reserve ownership and sign the production vault claim" in withProduction {
    (ctx, wallet) =>
      val owner = LithosDexSpecBase.ownerNFT
      val provision = liveProvision(ctx, Shares, BigInt(0), BigInt(0), owner, 1)
      val owedX = owed(Shares, BigInt(0), traded.accX)
      val owedY = owed(Shares, BigInt(0), traded.accY)
      val vault = liveVault(ctx,
        VAULT_MIN + owedX + vaultSurplus,
        owedY + vaultSurplus,
        traded.accX,
        traded.accY,
        0)
      val tokenNeed = Seq(Token(owner, 1L))
      val funding = walletInput(ctx, wallet, Headroom, tokenNeed, 10)

      val built = buildFunded(Headroom, tokenNeed, funding) { selector =>
        LithosDexTransactions.claim(ctx, wallet, selector, vault, Seq(provision))
      }

      built.claimedX shouldBe owedX
      built.claimedY shouldBe owedY
  }

  "LithosDexTransactions.resize" should "reserve both assets and sign the production grow-and-flush" in withProduction {
    (ctx, wallet) =>
      val oldShares = Shares
      val newShares = Shares + 5000000000L
      val pool = livePool(ctx, traded)
      val owner = LithosDexSpecBase.ownerNFT
      val provision = liveProvision(ctx, oldShares, BigInt(0), BigInt(0), owner, 1)
      val vault = liveVault(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0), 2)
      val quote = LDLiquidityPool(pool).simResize(
        oldShares, newShares, provision.entryX, provision.entryY)
      val tokenNeed = Seq(Token(owner, 1L), Token(ids(ctx).tokenY, quote.amountY))
      val ergNeed = quote.amountX + Headroom
      val funding = walletInput(ctx, wallet, ergNeed, tokenNeed, 10)

      val built = buildFunded(ergNeed, tokenNeed, funding) { selector =>
        LithosDexTransactions.resize(ctx, wallet, selector, pool, vault, provision, newShares)
      }

      built.quote shouldEqual quote
      built.boxId should not be empty
  }

  "LithosDexTransactions.refresh" should "reserve and sign a production refresh at an eligible height" in withProduction {
    (baseCtx, wallet) =>
      val ctx = contextAtHeight(baseCtx, refreshHeight)
      val owner = LithosDexSpecBase.ownerNFT
      val provision = liveProvision(ctx, Shares, traded.accX, traded.accY,
        owner, 0, createdHeight = Some(500000))
      val funding = walletInput(ctx, wallet, Headroom, Seq.empty, 10)

      val built = buildFunded(Headroom, Seq.empty, funding) { selector =>
        LithosDexTransactions.refresh(ctx, wallet, selector, provision)
      }

      built.boxId should not be empty
      built.tx.getOutputsToSpend.get(0).getCreationHeight shouldBe refreshHeight
  }

  /**
   * FileMockedErgoClient's fixture tip predates REFRESH_AGE. Delegate every context operation except
   * height and transaction-builder creation, where the matching high preheader is installed.
   */
  private def contextAtHeight(delegate: BlockchainContext, height: Int): BlockchainContext =
    Proxy.newProxyInstance(
      classOf[BlockchainContext].getClassLoader,
      Array(classOf[BlockchainContext]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match {
            case "getHeight" => Int.box(height)
            case "newTxBuilder" =>
              delegate.newTxBuilder().preHeader(delegate.createPreHeader().height(height).build())
            case _ =>
              val actual = if (args == null) Array.empty[AnyRef] else args
              try method.invoke(delegate, actual: _*)
              catch { case ex: InvocationTargetException => throw ex.getCause }
          }
      }).asInstanceOf[BlockchainContext]
}
