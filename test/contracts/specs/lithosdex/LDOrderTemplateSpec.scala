package contracts.specs.lithosdex

import lithosdex.contracts.{LDOrderKind, LDOrderContracts}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ContractTemplate
import org.scalatest.propspec.AnyPropSpec
import sigma.ast.{ByteArrayConstant, SigmaPropConstant}
import work.lithos.mutations.{Token, UTXO}

/**
 * The order templates a scan matches on, and the refund path every order shares.
 *
 * A batcher finds orders by template hash and reads their terms from fixed constant indexes, so the
 * properties that matter most here are that terms never change a template and that each term is where
 * the template says it is.
 */
class LDOrderTemplateSpec extends AnyPropSpec with LDOrderSpecBase {

  import LithosDexSpecBase._

  /**
   * The only place these scripts are compiled. A difference means every order already on chain has a
   * template the new one does not match, so re-pinning is a decision about those orders, not a fix.
   */
  property("templates: the pinned trees are exactly what the .ergo sources compile to") {
    withCtx { _ =>
      val drifted = LDOrderKind.all.flatMap { kind =>
        val hex = Hex.toHexString(LDOrderContracts.compileSource(kind).bytes)
        if (hex == LDOrderContracts.pinnedTreeHex(kind)) None
        else Some(s"${kind.scriptName} compiles to:\n" + hex.grouped(96).map(l => "\"" + l + "\"").mkString(" +\n"))
      }
      withClue(drifted.mkString("\n\n")) { drifted shouldBe empty }
    }
  }

  property("templates: every order is ErgoTree v3 with each term as exactly one constant") {
    withCtx { ctx =>
      LDOrderKind.all.foreach { kind =>
        val t = LDOrderContracts.template(kind)
        t.template.treeVersion shouldBe Some(3.toByte)
        t.termNames.map(t.indexOf).distinct should have size t.termNames.size
        println(f"[orders] ${kind.scriptName}%-18s ${t.templateBytes.length}%5d template bytes  hash ${t.templateHash}  " +
          t.termNames.map(n => s"$n@${t.indexOf(n)}").mkString(" "))
      }
    }
  }

  property("templates: orders of one kind share a template however their terms differ") {
    withCtx { ctx =>
      val a = LDOrderContracts.swapSell(terms(ctx, fee = 0L, maxMinerFee = 0L), 1L, 1L)
      val b = LDOrderContracts.swapSell(
        terms(ctx, fee = 6000000L).copy(redeemer = executor(ctx).getAddress.getPublicKey,
          poolNFT = unrelatedToken), 777L * Parameters.OneErg, 123456789L)
      a.propBytes should not equal b.propBytes
      a.ergoTree.template shouldEqual b.ergoTree.template
      a.ergoTree.template shouldEqual LDOrderContracts.template(LDOrderKind.SwapSell).templateBytes
    }
  }

  property("templates: the four kinds have four distinct templates") {
    withCtx { ctx =>
      val hashes = LDOrderKind.all.map(k => LDOrderContracts.template(k).templateHash)
      hashes.distinct should have size 4
    }
  }

  property("templates: every term reads back from the index its template reports") {
    withCtx { ctx =>
      val t = terms(ctx, fee = 1111L, maxMinerFee = 3333L)
      val orders = Seq(
        LDOrderKind.SwapSell -> LDOrderContracts.swapSell(t, 4444L, 5555L),
        LDOrderKind.SwapBuy  -> LDOrderContracts.swapBuy(t, 5555L),
        LDOrderKind.Deposit  -> LDOrderContracts.deposit(t, 6666L, 7777L),
        LDOrderKind.Redeem   -> LDOrderContracts.redeem(t))
      val expected = Map(
        LDOrderContracts.REDEEMER         -> SigmaPropConstant(t.redeemer),
        LDOrderContracts.POOL_NFT         -> ByteArrayConstant(t.poolNFT.getBytes),
        LDOrderContracts.EXECUTOR_FEE     -> LDOrderContracts.amount(1111L),
        LDOrderContracts.MAX_MINER_FEE    -> LDOrderContracts.amount(3333L),
        LDOrderContracts.BASE_AMOUNT      -> LDOrderContracts.amount(4444L),
        LDOrderContracts.MIN_QUOTE        -> LDOrderContracts.amount(5555L),
        LDOrderContracts.DEPOSIT_X        -> LDOrderContracts.amount(6666L),
        LDOrderContracts.MIN_SHARES       -> LDOrderContracts.amount(7777L))
      orders.foreach { case (kind, order) =>
        val template = LDOrderContracts.template(kind)
        template.termNames.foreach { name =>
          order.ergoTree.constants(template.indexOf(name)) shouldEqual expected(name)
        }
      }
    }
  }

  property("templates: an order missing a term is refused rather than built") {
    withCtx { ctx =>
      val template = LDOrderContracts.template(LDOrderKind.Redeem)
      an[IllegalArgumentException] should be thrownBy template.withValues(Map.empty)
    }
  }

  property("templates: each template survives a JSON round trip and still builds the same order") {
    withCtx { ctx =>
      val t = terms(ctx)
      LDOrderKind.all.foreach { kind =>
        val template = LDOrderContracts.template(kind)
        val json = template.template.toJsonString
        ContractTemplate.fromJsonString(json) shouldEqual template.template
      }
      val direct = LDOrderContracts.swapSell(t, 5L, 6L)
      val viaJson = ContractTemplate.fromJsonString(LDOrderContracts.template(LDOrderKind.SwapSell).template.toJsonString)
      val tree = viaJson.applyTemplate(None, Map(
        LDOrderContracts.REDEEMER -> SigmaPropConstant(t.redeemer),
        LDOrderContracts.POOL_NFT -> ByteArrayConstant(t.poolNFT.getBytes),
        LDOrderContracts.EXECUTOR_FEE -> LDOrderContracts.amount(t.executorFee),
        LDOrderContracts.MAX_MINER_FEE -> LDOrderContracts.amount(t.maxMinerFee),
        LDOrderContracts.BASE_AMOUNT -> LDOrderContracts.amount(5L),
        LDOrderContracts.MIN_QUOTE -> LDOrderContracts.amount(6L)
      ).map { case (k, v) => k -> v.asInstanceOf[sigma.ast.Constant[sigma.ast.SType]] })
      tree.bytes shouldEqual direct.propBytes
    }
  }

  // ─── refunds ──────────────────────────────────────────────────────────────

  private def everyOrder(ctx: BlockchainContext): Seq[(String, UTXO)] = {
    val t = terms(ctx)
    Seq(
      "sell"    -> UTXO(LDOrderContracts.swapSell(t, Parameters.OneErg, 1L), 2L * Parameters.OneErg),
      "buy"     -> UTXO(LDOrderContracts.swapBuy(t, 1L), REWARD_ERG, Seq(Token(tokenY, 1000000L))),
      "deposit" -> UTXO(LDOrderContracts.deposit(t, Parameters.OneErg, 1L), 2L * Parameters.OneErg,
        Seq(Token(tokenY, 1000000L))),
      "redeem"  -> UTXO(LDOrderContracts.redeem(t), REWARD_ERG + FEE, Seq(Token(ownerNFT, 1L))))
  }

  property("refund: the owner takes back every kind of order") {
    withCtx { ctx =>
      everyOrder(ctx).foreach { case (_, order) => accepts(user(ctx), refundTx(ctx, order)) }
    }
  }

  property("refund: nobody but the owner can take back an order") {
    withCtx { ctx =>
      everyOrder(ctx).foreach { case (_, order) =>
        val orderIn = inputAt(order, ctx, 0)
        val fund = executorFunding(ctx, 5)
        rejectsAtSigning(executor(ctx), build(ctx, Seq(orderIn, fund),
          Seq(UTXO(executorContract(ctx), order.value, order.tokens)), executor(ctx).getAddress))
      }
    }
  }

  property("refund: an order sitting where an execution would put it still refunds when no pool is there") {
    withCtx { ctx =>
      // Input 0 holds no tokens at all, so any read of a pool's layout would throw and take the refund with it
      val bare = inputAt(UTXO(userContract(ctx), SLACK), ctx, 7)
      everyOrder(ctx).foreach { case (name, order) =>
        val front = if (name == "redeem") Seq(bare, inputAt(UTXO(userContract(ctx), SLACK), ctx, 8)) else Seq(bare)
        val inputs = front ++ Seq(inputAt(order, ctx, 0))
        accepts(user(ctx), build(ctx, inputs, Seq(UTXO(userContract(ctx), order.value, order.tokens)),
          user(ctx).getAddress))
      }
    }
  }
}
