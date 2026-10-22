package contracts.specs.lithosdex

import lithosdex.contracts.{LDOrderContracts, LDOrderKind, LDT2TOrderKind}
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ContractTemplate
import org.scalatest.propspec.AnyPropSpec
import sigma.ast.{ByteArrayConstant, SigmaPropConstant}
import work.lithos.mutations.{Token, UTXO}

/**
 * The token/token order templates, and the refund path all three share. Their drift against the
 * `.ergo` sources is checked in `LDOrderTemplateSpec`, beside the ERG/token orders'.
 */
class LDT2TOrderTemplateSpec extends AnyPropSpec with LDT2TOrderSpecBase {

  import LDT2TSpecBase._

  property("templates: every token/token order is ErgoTree v3 with each term as exactly one constant") {
    withCtx { _ =>
      LDT2TOrderKind.all.foreach { kind =>
        val t = LDOrderContracts.template(kind)
        t.template.treeVersion shouldBe Some(3.toByte)
        t.termNames.map(t.indexOf).distinct should have size t.termNames.size
        println(f"[orders] ${kind.scriptName}%-20s ${t.templateBytes.length}%5d template bytes  hash ${t.templateHash}  " +
          t.termNames.map(n => s"$n@${t.indexOf(n)}").mkString(" "))
      }
    }
  }

  property("templates: orders of one kind share a template however their terms differ") {
    withCtx { ctx =>
      val a = LDOrderContracts.t2tSwap(terms(ctx, fee = 0L, maxMinerFee = 0L), 1L)
      val b = LDOrderContracts.t2tSwap(
        terms(ctx).copy(redeemer = executor(ctx).getAddress.getPublicKey, poolNFT = unrelatedToken), 123456789L)
      a.propBytes should not equal b.propBytes
      a.ergoTree.template shouldEqual b.ergoTree.template
      a.ergoTree.template shouldEqual LDOrderContracts.template(LDT2TOrderKind.Swap).templateBytes
    }
  }

  property("templates: all seven order kinds have distinct templates") {
    withCtx { _ =>
      val hashes = LDOrderKind.all.map(k => LDOrderContracts.template(k).templateHash) ++
        LDT2TOrderKind.all.map(k => LDOrderContracts.template(k).templateHash)
      hashes.distinct should have size 7
    }
  }

  property("templates: every term reads back from the index its template reports") {
    withCtx { ctx =>
      val t = terms(ctx, fee = 1111L, maxMinerFee = 3333L)
      val orders = Seq(
        LDT2TOrderKind.Swap    -> LDOrderContracts.t2tSwap(t, 5555L),
        LDT2TOrderKind.Deposit -> LDOrderContracts.t2tDeposit(t, 7777L),
        LDT2TOrderKind.Redeem  -> LDOrderContracts.t2tRedeem(t))
      val expected = Map(
        LDOrderContracts.REDEEMER      -> SigmaPropConstant(t.redeemer),
        LDOrderContracts.POOL_NFT      -> ByteArrayConstant(t.poolNFT.getBytes),
        LDOrderContracts.EXECUTOR_FEE  -> LDOrderContracts.amount(1111L),
        LDOrderContracts.MAX_MINER_FEE -> LDOrderContracts.amount(3333L),
        LDOrderContracts.MIN_QUOTE     -> LDOrderContracts.amount(5555L),
        LDOrderContracts.MIN_SHARES    -> LDOrderContracts.amount(7777L))
      orders.foreach { case (kind, order) =>
        val template = LDOrderContracts.template(kind)
        template.termNames.foreach { name =>
          order.ergoTree.constants(template.indexOf(name)) shouldEqual expected(name)
        }
      }
    }
  }

  property("templates: each template survives a JSON round trip") {
    withCtx { _ =>
      LDT2TOrderKind.all.foreach { kind =>
        val template = LDOrderContracts.template(kind).template
        ContractTemplate.fromJsonString(template.toJsonString) shouldEqual template
      }
    }
  }

  // ─── refunds ──────────────────────────────────────────────────────────────

  private def everyOrder(ctx: BlockchainContext): Seq[(String, UTXO)] = {
    val t = terms(ctx)
    Seq(
      "swap"    -> UTXO(LDOrderContracts.t2tSwap(t, 1L), REWARD_ERG + FEE, Seq(Token(tokenX, 1000000L))),
      "deposit" -> UTXO(LDOrderContracts.t2tDeposit(t, 1L), PROVISION_MIN + REWARD_ERG + FEE,
        Seq(Token(tokenX, 1000000L), Token(tokenY, 2000000L))),
      "redeem"  -> UTXO(LDOrderContracts.t2tRedeem(t), REWARD_ERG + FEE, Seq(Token(ownerNFT, 1L))))
  }

  property("refund: the owner takes back every kind of order") {
    withCtx { ctx =>
      everyOrder(ctx).foreach { case (_, order) => accepts(user(ctx), refundTx(ctx, order)) }
    }
  }

  property("refund: nobody but the owner can take back an order") {
    withCtx { ctx =>
      everyOrder(ctx).foreach { case (_, order) =>
        rejectsAtSigning(executor(ctx), build(ctx, Seq(inputAt(order, ctx, 0), executorFunding(ctx, 5)),
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
        accepts(user(ctx), build(ctx, front :+ inputAt(order, ctx, 0),
          Seq(UTXO(userContract(ctx), order.value, order.tokens)), user(ctx).getAddress))
      }
    }
  }
}
