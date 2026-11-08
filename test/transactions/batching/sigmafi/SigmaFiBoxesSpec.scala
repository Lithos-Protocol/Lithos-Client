package transactions.batching.sigmafi

import node.model.IndexedBox
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigma.ast.{SCollection, SLong}
import sigmafi.contracts._
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * What the client accepts as a SigmaFi bond, order or vault box. Anyone can create a box at any of these
 * scripts, so every refusal here is a box a stranger could plant to be read with terms no contract enforces.
 */
class SigmaFiBoxesSpec extends AnyPropSpec with SigmaFiFixtures {

  private val maturity = 200000

  // ─── bonds ──────────────────────────────────────────────────────────────

  property("bond: reads an ERG bond and a token bond, with the token its tree was built for") {
    withCtx { ctx =>
      val borrower = contractOf(miner(ctx))
      val erg = SigmaFiBoxes.bond(nodeBox(ctx, bondUTXO(None, borrower, maturity, collateral = Seq(Token(tokenB, 7))))).get
      erg.repayToken shouldBe None
      erg.asset shouldBe assetId
      erg.borrowerHash.toSeq shouldBe borrower.hashedPropBytes.toSeq
      (erg.repayment, erg.batchingFee, erg.maturity) shouldBe ((2 * Erg, 50000000L, maturity))
      erg.liquidation shouldBe Some(liq)
      erg.collateral.map(a => a.tokenId -> a.amount) shouldBe Seq(tokenB.toString -> 7L)

      val token = SigmaFiBoxes.bond(nodeBox(ctx, bondUTXO(Some(tokenA), borrower, maturity))).get
      token.repayToken shouldBe Some(tokenA)
    }
  }

  property("bond: refuses a register at another type, or amounts that are not four") {
    withCtx { ctx =>
      val b = bondUTXO(None, contractOf(miner(ctx)), maturity)
      SigmaFiBoxes.bond(nodeBox(ctx, b.withReg(3, ErgoValue.of(maturity.toLong)))) shouldBe None
      SigmaFiBoxes.bond(nodeBox(ctx, b.withReg(2, ErgoValue.of(Array(1L, 2L, 3L))))) shouldBe None
      SigmaFiBoxes.bond(nodeBox(ctx, b.withReg(0, ErgoValue.of(Array.fill(31)(1.toByte))))) shouldBe None
    }
  }

  property("bond: requires the first token to be a singleton matching R4, and hashes to be 32 bytes") {
    withCtx { ctx =>
      val b = bondUTXO(None, contractOf(miner(ctx)), maturity, collateral = Seq(Token(tokenB, 7L)))
      Seq(b.copy(tokens = b.tokens.tail), b.copy(tokens = Token(assetId, 2L) +: b.tokens.tail),
        b.copy(tokens = b.tokens.reverse), b.withReg(0, ErgoValue.of(tokenA.getBytes)),
        b.withReg(1, ErgoValue.of(Array[Byte](1))), b.withReg(4, ErgoValue.of(Array[Byte](1)))).foreach { bad =>
        SigmaFiBoxes.bond(nodeBox(ctx, bad)) shouldBe None
      }
    }
  }

  property("bond: refuses a token bond tree whose vault hash is not the pinned vault's, though its template matches") {
    withCtx { ctx =>
      val genuine = SigmaFiContracts.tokenBond(tokenA).ergoTree
      val vaultAt = constantIndex(genuine, SCollection.SByteArray, Colls.fromArray(SigmaFiContracts.vault.hashedPropBytes))
      val forged = Contract(forge(genuine, vaultAt, Colls.fromArray(Blake2b256.hash("elsewhere"))))
      Blake2b256.hash(forged.ergoTree.template).toSeq shouldBe Blake2b256.hash(genuine.template).toSeq
      val b = bondUTXO(Some(tokenA), contractOf(miner(ctx)), maturity)
      SigmaFiBoxes.bond(nodeBox(ctx, b.copy(contract = forged))) shouldBe None
    }
  }

  // ─── orders ─────────────────────────────────────────────────────────────

  property("order: reads both styles' terms back as they were built") {
    withCtx { ctx =>
      val borrower = contractOf(miner(ctx))
      Seq(FixedMaturity(maturity), OnClose(720)).foreach { schedule =>
        val terms = orderTerms(borrower, schedule, principalTokens = Seq(tokenA -> 500L))
        val read = SigmaFiBoxes.order(nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 3 * Erg))).get.terms
        read.schedule shouldBe schedule
        read.principalTokens shouldBe Seq(tokenA -> 500L)
        read.borrowerHash.toSeq shouldBe terms.borrowerHash.toSeq
        (read.repayment, read.principalErg) shouldBe ((terms.repayment, terms.principalErg))
        SigmaFiBoxes.pairedBond(read.bondHash, read.principalTokens) shouldBe Some(Some(tokenA))
      }
    }
  }

  property("order: refuses a forged order that shares the template hash but mints three") {
    withCtx { ctx =>
      val genuine = SigmaFiContracts.order(orderTerms(contractOf(miner(ctx)), FixedMaturity(maturity))).ergoTree
      val mintAt = constantIndex(genuine, SLong, 2L)
      val forged = Contract(forge(genuine, mintAt, 3L))
      Blake2b256.hash(forged.ergoTree.template).toSeq shouldBe Blake2b256.hash(genuine.template).toSeq
      SigmaFiBoxes.order(nodeBox(ctx, UTXO(forged, 3 * Erg))) shouldBe None
      SigmaFiBoxes.order(nodeBox(ctx, UTXO(Contract(genuine), 3 * Erg))) should not be empty
    }
  }

  // ─── pairing, vault, building ───────────────────────────────────────────

  property("pairing: refuses a token loan paired with another token's bond, or an ERG loan with a token bond") {
    SigmaFiBoxes.pairedBond(SigmaFiContracts.tokenBond(tokenB).hashedPropBytes, Seq(tokenA -> 1L)) shouldBe None
    SigmaFiBoxes.pairedBond(SigmaFiContracts.tokenBond(tokenA).hashedPropBytes, Seq.empty) shouldBe None
    SigmaFiBoxes.pairedBond(SigmaFiContracts.ergBond.hashedPropBytes, Seq(tokenA -> 1L)) shouldBe None
  }

  property("vault: reads the asset it opens for, and refuses one without a 32-byte asset") {
    withCtx { ctx =>
      val bondId = ErgoId.create("d4" * 32)
      val read = SigmaFiBoxes.vault(nodeBox(ctx, vaultUTXO(bondId, assetId))).get
      read.asset shouldBe assetId
      read.bondId shouldBe Some(bondId.toString)
      SigmaFiBoxes.vault(nodeBox(ctx, vaultUTXO(bondId, assetId).withReg(1, ErgoValue.of(Array(1.toByte))))) shouldBe None
    }
  }

  property("building: a term of the wrong type is refused, not built into a tree") {
    val t = SigmaFiContracts.template(SigmaFiTemplateKind.Order)
    val values = SigmaFiContracts.sentinelTerms(SigmaFiTemplateKind.Order) + (SigmaFiContracts.MATURITY -> 5)
    an[IllegalArgumentException] should be thrownBy t.withTerms(values)
  }

  property("hints: R4's trees come back, and garbage entries are dropped") {
    withCtx { ctx =>
      val tree = contractOf(miner(ctx)).ergoTree
      val entries = Seq(tree.bytes, Array[Byte](1, 2)).map(b => Colls.fromArray(b)).toArray
      val hints = ErgoValue.of(Colls.fromArray(entries)(sigma.collRType(sigma.ByteType)),
        org.ergoplatform.appkit.ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))
      val box = nodeBox(ctx, UTXO(Contract.SIGMA_TRUE, Erg, Seq.empty, Seq(hints)))
      SigmaFiBoxes.hintTrees(nodeBox(ctx, UTXO(Contract.SIGMA_TRUE, Erg, Seq.empty, Seq(SigmaFiBoxes.hintValue(tree)))))
        .map(_.bytes.toSeq) shouldBe Seq(tree.bytes.toSeq)
      SigmaFiBoxes.hintTrees(box).map(_.bytes.toSeq) shouldBe Seq(tree.bytes.toSeq)
    }
  }

  // ─── verification ───────────────────────────────────────────────────────

  property("verify: a bond is its asset's own only when its mint's genesis box was an order it spent") {
    withCtx { ctx =>
      val borrower = contractOf(miner(ctx))
      val order = nodeBox(ctx, UTXO(SigmaFiContracts.order(orderTerms(borrower, FixedMaturity(maturity))), 3 * Erg),
        0, "e1" * 32)
      val fillTx = "f2" * 32
      val bond = SigmaFiBoxes.bond(nodeBox(ctx, bondUTXO(None, borrower, maturity, value = 3 * Erg + 50000000L, asset = ErgoId.create(order.boxId)),
        1, fillTx)).get
      def genesis(box: node.model.NodeBox, spentBy: Option[String]) =
        Some(IndexedBox(box, "", 1, 1L, spentTransactionId = spentBy))

      SigmaFiBoxes.verify(bond, genesis(order, Some(fillTx))) shouldBe Right(())
      SigmaFiBoxes.verify(bond, None).isLeft shouldBe true
      SigmaFiBoxes.verify(bond, genesis(order, Some("f3" * 32))).isLeft shouldBe true
      val notMinter = nodeBox(ctx, UTXO(borrower, 3 * Erg), 0, "e1" * 32)
      SigmaFiBoxes.verify(bond.copy(asset = ErgoId.create(notMinter.boxId)), genesis(notMinter, Some(fillTx))).isLeft shouldBe true
      val atOutputTwo = bond.copy(box = bond.box.copy(index = 2))
      SigmaFiBoxes.verify(atOutputTwo, genesis(order, Some(fillTx))).isLeft shouldBe true
    }
  }
}
