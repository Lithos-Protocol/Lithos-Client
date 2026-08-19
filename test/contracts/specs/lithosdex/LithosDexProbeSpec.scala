package contracts.specs.lithosdex

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * Sanity for the LithosDex harness: the contracts compile at ErgoTree v3, and every spending path has a
 * known-good transaction the whole stack accepts. Run this first when a LithosDex spec starts failing —
 * if a positive here breaks, the harness is wrong rather than the contract.
 */
class LithosDexProbeSpec extends AnyPropSpec with LithosDexSpecBase {

  property("the four contracts compile at ErgoTree v3") {
    withCtx { ctx =>
      val c = ldContracts(ctx)
      Seq("LD_LiquidityPool" -> c.liquidityPool, "LD_FeeVault" -> c.feeVault,
        "LD_Provision_Guard" -> c.provisionGuard, "LD_Provision" -> c.provisionLogic
      ).foreach { case (name, k) =>
        k.ergoTree.version shouldBe 3
        println(f"[probe] $name%-20s ${k.propBytes.length}%5d prop bytes  v${k.ergoTree.version}")
      }
      // A provision box carries the guard, never the logic. If that ever inverts, the storage-rent
      // margin this whole design rests on goes with it.
      c.provisionGuard.propBytes.length should be < c.provisionLogic.propBytes.length
    }
  }

  property("swap: a known-good ERG-for-token swap is accepted") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      s.quote.isExecutable shouldBe true
      accepts(s.prover, swapTx(s)())
    }
  }

  property("swap: a known-good token-for-ERG swap is accepted") {
    withCtx { ctx =>
      val s = swapScenario(ctx, amountIn = 2000L * 1000000L, ergIn = false)
      s.quote.isExecutable shouldBe true
      accepts(s.prover, swapTx(s)())
    }
  }

  property("deposit: a known-good deposit is accepted") {
    withCtx { ctx =>
      val d = depositScenario(ctx)
      accepts(d.prover, depositTx(d)())
    }
  }

  property("redeem: a known-good redemption is accepted") {
    withCtx { ctx =>
      val r = redeemScenario(ctx)
      accepts(r.prover, redeemTx(r)())
    }
  }

  property("flush: a known-good flush is accepted") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      f.before.pendingX should be > 0L
      f.before.pendingY should be > 0L
      accepts(f.prover, flushTx(f)())
    }
  }

  property("resize: a known-good grow is accepted") {
    withCtx { ctx =>
      val r = resizeScenario(ctx)
      accepts(r.prover, resizeTx(r)())
    }
  }

  property("resize: a known-good shrink is accepted") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares = 10000000000L, newShares = 4000000000L)
      accepts(r.prover, resizeTx(r)())
    }
  }

  property("claim: a known-good settlement is accepted") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      c.owedX should be > 0L
      c.owedY should be > 0L
      accepts(c.prover, claimTx(c)())
    }
  }

  property("refresh: a known-good renewal of an aged box is accepted") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      accepts(r.prover, refreshTx(r)())
    }
  }

  property("inventory: serialized sizes and the storage-rent margin they buy") {
    withCtx { ctx =>
      val factor = ctx.getDataSource.getParameters.getStorageFeeFactor.toLong
      def report(name: String, box: work.lithos.mutations.UTXO, floor: Long): Unit = {
        val bytes = inputAt(box, ctx, 0).bytes.length
        println(f"[probe] $name%-14s $bytes%5d bytes  rent ${bytes * factor}%14d  floor $floor%14d  " +
          f"margin ${floor - bytes * factor}%14d")
      }
      println(s"[probe] storageFeeFactor = $factor nanoERG per byte per storage period")
      report("provision", provisionOf(ctx, 10000000000L, traded.accX, traded.accY).utxo, PROVISION_MIN)
      report("vault", vaultUTXO(ctx, VAULT_MIN, 1000000L, traded.accX, traded.accY), VAULT_MIN)
      report("pool", poolUTXO(ctx, traded), MIN_RENT)
    }
  }
}
