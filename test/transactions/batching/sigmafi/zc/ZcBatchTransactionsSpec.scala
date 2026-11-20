package transactions.batching.sigmafi.zc

import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import support.FakeNodeContext
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * Every ZC batching transaction builder signed offline against the real pinned ZC contracts: offer matches
 * (ERG & SigUSD, fixed/yield/window orders, plain & fractional share delivery with var 64 disclosure, chaining
 * continuations across multiple fills, sale orders, yield asks & Dutch shares), mixed packs of liquidations,
 * expiry refunds and non-bond refreshes, aged bond refresh packs (EIP-39), and standalone mints, pulls and wraps.
 */
class ZcBatchTransactionsSpec extends AnyFlatSpec with Matchers {

  import ZcFixtures.{Erg => _, _}
  import ZcPricing.{RefreshAge, RefreshBounty, ShareCount}

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val Reserve = 50000000L
  private val Fee = 5000000L
  private val SigUsd = ZcContracts.SigUsdId

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 3))
  private val feeTo: Contract = wallet.contract
  private val borrower: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val borrowerHash: String = Hex.toHexString(borrower.hashedPropBytes)
  private val lender: Contract = Contract.fromErgoContract(wallet.addresses(2).toErgoContract)
  private val lenderHash: String = Hex.toHexString(lender.hashedPropBytes)
  private val seller: Contract = borrower
  private val sellerHash: String = borrowerHash

  private var seq = 0
  private def nextId(): ErgoId = { seq += 1; ErgoId.create(f"$seq%064x") }
  private def seeded(ctx: BlockchainContext, u: UTXO): InputUTXO = u.toInput(ctx, nextId(), 0)
  private def tok(id: String, n: Long): Token = Token(ErgoId.create(id), n)
  private def input(ctx: BlockchainContext, b: NodeBox): InputUTXO = SigmaFiBoxes.input(b, ctx).get
  private def nodeOf(in: InputUTXO): NodeBox =
    ZcBatchTransactions.nodeBoxOf(in.input, in.input.getTransactionId, in.input.getTransactionIndex)
  private def out(tx: SignedTransaction, i: Int): NodeBox =
    ZcBatchTransactions.nodeBoxOf(tx.getOutputsToSpend.get(i), CandidateTx.txId(tx), i)
  private def read[A](b: NodeBox): A =
    ZcBoxes.read(b).getOrElse(fail(s"box ${b.boxId} does not read as ZC")).asInstanceOf[A]

  private def oracleBox(ctx: BlockchainContext, price: Long, created: Int): InputUTXO =
    seeded(ctx, UTXO(Contract.SIGMA_TRUE, MinBox, Seq(tok(ZcContracts.OracleNft, 1L)),
      Seq(ErgoValue.of(price), ErgoValue.of(1)), Some(created)))

  /** An ERG order carrying the oracle rule, ready to be matched by an ERG lend offer. */
  private def ergOrder(ctx: BlockchainContext, h: Int, principal: Long = Erg, repayment: Long = 2 * Erg,
                       collateralSigUsd: Long = 2000L, maturityOffset: Long = 50000L,
                       expiryOffset: Long = 5000L): (ZcOrder, InputUTXO) = {
    val terms = Seq(repayment, principal, repayment, h.toLong + maturityOffset, h.toLong + expiryOffset, Fee, 0L, 15000L, 60L)
    val in = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
      Fee + MinBox, Seq(tok(SigUsd, collateralSigUsd)),
      Seq(longs(terms: _*), tokens(), bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))),
      Some(h - 10)))
    read[ZcOrder](nodeOf(in)) -> in
  }

  /** An ERG lend offer, optionally delivering into a Selective DutchAuctionMint. */
  private def ergOffer(ctx: BlockchainContext, h: Int, capital: Long = 10 * Erg, delivers: Boolean = false,
                       buysShares: Boolean = false): (ZcOffer, InputUTXO) = {
    val terms = Seq(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L,
      if (buysShares) 8000L else 0L, if (buysShares) 5000L else 0L)
    val (frac, meta, mint) = if (!delivers) (Seq.empty[Long], Seq.empty[Array[Byte]], Array.empty[Byte])
    else {
      val a = ZcMetadata.Auction(10000L, 9000L, 720L, 2160L, 100L)
      (Seq(ShareCount, Fee, Fee, 10000L, 9000L, 720L, 2160L, 100L),
        ZcMetadata.shares(ZcMarket.Erg, Some(a), None).map(_.getBytes("UTF-8")),
        Hex.decode(ZcContracts.script(ZcKind.DutchAuctionMint, Some(ZcMarket.Erg)).treeHash))
    }
    val in = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract, capital, Seq.empty,
      Seq(bytes(lenderHash), longs(terms: _*), bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)),
        longs(frac: _*), ZcUserTransactions.bytesList(meta), ErgoValue.of(sigma.Colls.fromArray(mint),
          org.ergoplatform.appkit.scalaapi.scalaByteType)), Some(h - 10)))
    read[ZcOffer](nodeOf(in)) -> in
  }

  /** A SigUSD lend offer with proportional fee budgets. */
  private def sigUsdOffer(ctx: BlockchainContext, h: Int, capital: Long = 1000L, ergFees: Long = Erg): (ZcOffer, InputUTXO) = {
    val spendBudget = ergFees - MinBox - Fee
    val rewardBudget = 200000000L
    val terms = Seq(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 0L, 0L,
      capital, spendBudget, 100L, rewardBudget)
    val in = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.SigUsd)).contract, ergFees,
      Seq(tok(SigUsd, capital)),
      Seq(bytes(lenderHash), longs(terms: _*), bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.SigUsd)),
        longs(), ZcUserTransactions.bytesList(Seq.empty), bytes("")), Some(h - 10)))
    read[ZcOffer](nodeOf(in)) -> in
  }

  "matchOrder" should "fill two ERG orders sequentially by chaining the offer continuation, fee-less and broadcast" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val oracle = oracleBox(ctx, 500000000L, h - 5) // 1 USD = 0.5 ERG => 2000 SigUSD ($20) = 10 ERG collateral
      val (offer, offerIn) = ergOffer(ctx, h, capital = 10 * Erg)
      val (order1, order1In) = ergOrder(ctx, h)
      val (order2, order2In) = ergOrder(ctx, h)

      val first = ZcBatchTransactions.matchOrder(ctx, wallet, offer, offerIn, order1, order1In, borrower, lender,
        order1.terms(3), order1.principal, oracle, feeTo, minerFee = 0L, height = h)
      first.tx.fees shouldBe Fee
      first.tx.feeBox.value shouldBe Fee
      first.tx.candidateTx.kind shouldBe ZcBatchTransactions.KindMatchOrder
      first.nextOffer.predecessor shouldBe Some(offer.boxId)
      read[ZcBond](out(first.tx.signed, 1)).asset shouldBe order1.boxId

      // Chain second fill directly off first.nextOfferInput, with a broadcast miner fee
      val second = ZcBatchTransactions.matchOrder(ctx, wallet, first.nextOffer, first.nextOfferInput, order2, order2In,
        borrower, lender, order2.terms(3), order2.principal, oracle, feeTo, minerFee = Parameters.MinFee, height = h)
      second.tx.feeBox.value shouldBe Fee - Parameters.MinFee
      second.nextOffer.predecessor shouldBe Some(first.nextOffer.boxId)
    }
  }

  it should "disclose the owner's script in var 64 when an offer delivers into a share mint request" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val oracle = oracleBox(ctx, 500000000L, h - 5)
      val (offer, offerIn) = ergOffer(ctx, h, capital = 10 * Erg, delivers = true)
      val (order, orderIn) = ergOrder(ctx, h)

      val built = ZcBatchTransactions.matchOrder(ctx, wallet, offer, offerIn, order, orderIn, borrower, lender,
        order.terms(3), order.principal, oracle, feeTo, minerFee = 0L, height = h)
      val delivery = read[ZcShareRequest](out(built.tx.signed, 0))
      delivery.bondAsset shouldBe order.boxId
      delivery.ownerHash shouldBe lenderHash
      // Owner's script appears in neither inputs nor outputs, so INPUTS(0) must carry it in var 64
      val in0 = built.tx.signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx.inputs.head
      val ext = in0.spendingProof.extension.values.get(ZcUserTransactions.DisclosureVar)
      ext shouldBe defined
      built.tx.candidateTx.sizeBytes should be > 0
    }
  }

  it should "match a SigUSD token offer against a SigUSD order" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val oracle = oracleBox(ctx, 500000000L, h - 5) // 1 USD = 0.5 ERG => 10 ERG collateral = $20 vs $1.50 repayment
      val (offer, offerIn) = sigUsdOffer(ctx, h, capital = 1000L, ergFees = Erg)
      val terms = Seq(150L, MinBox, MinBox, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L)
      val orderIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.SigUsd)).contract,
        10 * Erg + Fee, Seq.empty,
        Seq(longs(terms: _*), tokens(SigUsd -> 100L), bytes(borrowerHash),
          bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.SigUsd))), Some(h - 10)))
      val order = read[ZcOrder](nodeOf(orderIn))

      val built = ZcBatchTransactions.matchOrder(ctx, wallet, offer, offerIn, order, orderIn, borrower, lender,
        order.terms(3), 100L, oracle, feeTo, minerFee = 0L, height = h)
      built.tx.fees shouldBe 20000000L // 10% of 0.2 ERG reward budget
      built.nextOffer.capital shouldBe 900L
      read[ZcBond](out(built.tx.signed, 1)).repayment shouldBe 150L
    }
  }

  "matchAsk" should "buy a bond unit from a SaleOrder and recover the ask's ERG on a full fill" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val oracle = oracleBox(ctx, 500000000L, h - 5)
      val (offer1, offer1In) = ergOffer(ctx, h, capital = 10 * Erg)
      val (order, orderIn) = ergOrder(ctx, h)
      val filled = ZcBatchTransactions.matchOrder(ctx, wallet, offer1, offer1In, order, orderIn, borrower, lender,
        order.terms(3), order.principal, oracle, feeTo, 0L, h)
      val bondBox = out(filled.tx.signed, 1)
      val bondIn = input(ctx, bondBox)
      val originBytes = orderIn.bytes

      val askIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(order.boxId, 1L)), Seq(bytes(sellerHash), longs(Erg, 1L, 1L, h.toLong + 5000L)), Some(h - 5)))
      val ask = read[ZcAsk](nodeOf(askIn))

      val matched = ZcBatchTransactions.matchAsk(ctx, wallet, filled.nextOffer, filled.nextOfferInput, ask, askIn,
        seller, lender, units = 1L, price = Erg, bondIn, oracle, originBytes, None, feeTo, minerFee = 0L, height = h)
      matched.nextAsk shouldBe None
      matched.tx.fees shouldBe Fee + 3000000L
      matched.tx.feeBox.value shouldBe Fee + 3000000L
      ZcBoxes.payoutTag(out(matched.tx.signed, 0)) shouldBe Some(ask.boxId -> 1L)
    }
  }

  it should "buy Selective shares from a partial SaleOrder with originBytes (var 4) and requestBytes (var 6)" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val oracle = oracleBox(ctx, 500000000L, h - 5)
      val (offer1, offer1In) = ergOffer(ctx, h, capital = 10 * Erg, delivers = true, buysShares = true)
      val (order, orderIn) = ergOrder(ctx, h)
      val filled = ZcBatchTransactions.matchOrder(ctx, wallet, offer1, offer1In, order, orderIn, borrower, lender,
        order.terms(3), order.principal, oracle, feeTo, 0L, h)
      val reqBox = out(filled.tx.signed, 0)
      val req = read[ZcShareRequest](reqBox)
      val reqIn = input(ctx, reqBox)
      val bondBox = out(filled.tx.signed, 1)
      val bondIn = input(ctx, bondBox)

      // Mint shares first
      val minted = ZcBatchTransactions.mintShares(ctx, wallet, req, reqIn, lender, Some((bondIn, order.repayment, None)),
        feeTo, minerFee = 0L, height = h)
      minted.fees shouldBe Fee
      val shareId = req.shareId

      // Post a SaleOrder for all 100M shares, priced at 1 ERG for all, partially bought (50M shares)
      val askIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(shareId, ShareCount)), Seq(bytes(sellerHash), longs(Erg, ShareCount, 1L, h.toLong + 5000L)), Some(h - 5)))
      val ask = read[ZcAsk](nodeOf(askIn))

      val matched = ZcBatchTransactions.matchAsk(ctx, wallet, filled.nextOffer, filled.nextOfferInput, ask, askIn,
        seller, lender, units = ShareCount, price = Erg, bondIn, oracle, orderIn.bytes, Some(reqIn.bytes),
        feeTo, minerFee = 0L, height = h)
      matched.tx.fees shouldBe Fee + 3000000L
      out(matched.tx.signed, 1).assets shouldBe Seq(NodeAsset(shareId, ShareCount))
    }
  }

  "pack" should "pack maturity liquidation, early oracle liquidation, all four refunds and vault refreshes in one tx" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      // 1. Matured bond
      val asset1 = "11" * 32
      val maturedIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        2 * Erg + Reserve, Seq(tok(asset1, 2L)),
        Seq(bytes(asset1), bytes(borrowerHash), longs(2 * Erg, Erg, 2 * Erg, h.toLong - 10, h.toLong - 50, Fee, 0L),
          tokens(), bytes(expr(ZcExpression.MaturityLiquidation)), longs(Reserve, h.toLong - 100, h.toLong - 10)), Some(h - 100)))
      val matured = read[ZcBond](nodeOf(maturedIn))

      // 2. Early oracle bond under its 150% ratio (1 ERG collateral vs 2 ERG repayment -> 50% cover)
      val asset2 = "22" * 32
      val oracle = oracleBox(ctx, 500000000L, h - 5)
      val earlyIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        Erg + Reserve, Seq(tok(asset2, 2L)),
        Seq(bytes(asset2), bytes(borrowerHash), longs(2 * Erg, Erg, 2 * Erg, h.toLong + 5000, h.toLong - 50, Fee, 0L, 15000L, 60L),
          tokens(), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg)), longs(Reserve, h.toLong - 100, h.toLong + 5000)),
        Some(h - 100)))
      val early = read[ZcBond](nodeOf(earlyIn))

      // 3. Expired order, offer, ask, lot
      val (expOrder, expOrderIn) = ergOrder(ctx, h, expiryOffset = -5L)
      val expOfferIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract, Erg, Seq.empty,
        Seq(bytes(lenderHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong - 5, 1L, 30L, 0L, 0L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)), longs(), ZcUserTransactions.bytesList(Seq.empty), bytes("")),
        Some(h - 50)))
      val expOffer = read[ZcOffer](nodeOf(expOfferIn))
      val expAskIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox + Fee,
        Seq(tok("33" * 32, 10L)), Seq(bytes(sellerHash), longs(Erg, 10L, 1L, h.toLong - 5)), Some(h - 50)))
      val expAsk = read[ZcAsk](nodeOf(expAskIn))
      val expLotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, MinBox + Fee,
        Seq(tok("44" * 32, 10L)), Seq(bytes(sellerHash), longs(2 * Erg, Erg, h.toLong - 100, h.toLong - 10, h.toLong - 5, Fee)),
        Some(h - 50)))
      val expLot = read[ZcLot](nodeOf(expLotIn))

      // 4. Aged settlement vault
      val oldVaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.BondVault, None).contract, MinBox + RefreshBounty,
        Seq(tok("55" * 32, 2L)), Seq(bytes("66" * 32), byte(1)), Some(old)))
      val oldVault = read[ZcSettlement](nodeOf(oldVaultIn))

      val pieces = Seq(
        ZcBatchTransactions.liquidationPiece(matured, maturedIn, ZcContracts.expression(ZcExpression.MaturityLiquidation, None).bytes, None),
        ZcBatchTransactions.liquidationPiece(early, earlyIn, ZcContracts.expression(ZcExpression.OracleLiquidation, Some(ZcMarket.Erg)).bytes, Some(oracle)),
        ZcBatchTransactions.orderRefundPiece(expOrder, expOrderIn, borrower),
        ZcBatchTransactions.offerRefundPiece(expOffer, expOfferIn, lender),
        ZcBatchTransactions.askRefundPiece(expAsk, expAskIn, seller),
        ZcBatchTransactions.lotRefundPiece(expLot, expLotIn, seller),
        ZcBatchTransactions.settlementRefreshPiece(oldVault, oldVaultIn, h))

      val packed = ZcBatchTransactions.pack(ctx, wallet, pieces, feeTo, minerFee = 0L, height = h)
      val expectedReward = Reserve + Reserve + Fee + Fee + Fee + Fee + RefreshBounty
      packed.fees shouldBe expectedReward
      packed.feeBox.value shouldBe expectedReward
      packed.spentIds.size shouldBe 7
      packed.candidateTx.kind shouldBe ZcBatchTransactions.KindPack

      // Broadcast variant with 0.001 ERG miner fee
      val broadcast = ZcBatchTransactions.pack(ctx, wallet, pieces, feeTo, minerFee = Parameters.MinFee, height = h)
      broadcast.feeBox.value shouldBe expectedReward - Parameters.MinFee
    }
  }

  it should "pack aged bond refreshes stamped 60 blocks back with var 64 disclosure, and refuse mixing with non-aged" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      val asset = "77" * 32
      val bondIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        2 * Erg + Reserve, Seq(tok(asset, 2L)),
        Seq(bytes(asset), bytes(borrowerHash), longs(2 * Erg, Erg, 2 * Erg, h.toLong + 10000, h.toLong - 500000, Fee, 0L),
          tokens(), bytes(expr(ZcExpression.MaturityLiquidation)), longs(Reserve, old.toLong, h.toLong + 10000)), Some(old)))
      val bond = read[ZcBond](nodeOf(bondIn))
      val piece = ZcBatchTransactions.bondRefreshPiece(bond, bondIn, borrower, h)
      piece.aged shouldBe true

      val refreshed = ZcBatchTransactions.pack(ctx, wallet, Seq(piece), feeTo, minerFee = 0L, height = h)
      refreshed.fees shouldBe RefreshBounty
      refreshed.kind shouldBe ZcBatchTransactions.KindBondRefresh
      out(refreshed.signed, 0).creationHeight shouldBe h - 60

      val oldVaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.BondVault, None).contract, MinBox + RefreshBounty,
        Seq(tok("88" * 32, 2L)), Seq(bytes("99" * 32), byte(1)), Some(old)))
      val vaultPiece = ZcBatchTransactions.settlementRefreshPiece(read[ZcSettlement](nodeOf(oldVaultIn)), oldVaultIn, h)
      an[IllegalArgumentException] should be thrownBy
        ZcBatchTransactions.pack(ctx, wallet, Seq(piece, vaultPiece), feeTo, 0L, h)
    }
  }

  "Standalone actions" should "pull a liquidated settlement into a Dutch share vault, mint a wrap, and refresh a wrap vault" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      val asset = "aa" * 32
      val share = "bb" * 32
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, MinBox + Fee,
        Seq(tok(share, ShareCount + 1L), tok(asset, 1L)),
        Seq(bytes(asset), longs(ShareCount, ShareCount, Fee), byte(0),
          longs(2 * Erg, 10000L, 9000L, 720L, 2160L, 100L, 0L), bytes("")), Some(h - 10)))
      val settleIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.BondVault, None).contract, MinBox,
        Seq(tok(asset, 2L), tok("cc" * 32, 50L)), Seq(bytes("dd" * 32), byte(1)), Some(h - 5)))
      val pulled = ZcBatchTransactions.pull(ctx, wallet, read[ZcShareVault](nodeOf(vaultIn)), vaultIn,
        read[ZcSettlement](nodeOf(settleIn)), settleIn, feeTo, minerFee = 0L, height = h)
      pulled.fees shouldBe Fee
      val nextVault = read[ZcShareVault](out(pulled.signed, 0))
      nextVault.holding shouldBe false
      nextVault.state shouldBe Some(1.toByte) // thin token (50 < 100) + short of floor => floor auction!

      // Wrap mint (alone)
      val wrapReqIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.WrapMint, None).contract, 2 * MinBox + 30000000L + Fee,
        Seq(tok("ee" * 32, 1L)), Seq(bytes(lenderHash), long(Fee),
          ZcUserTransactions.bytesList(ZcMetadata.receipt("ee" * 32, 1L).map(_.getBytes("UTF-8")))), Some(h - 5)))
      val mintedWrap = ZcBatchTransactions.mintWrap(ctx, wallet, read[ZcWrapRequest](nodeOf(wrapReqIn)), wrapReqIn,
        lender, feeTo, minerFee = 0L, height = h)
      mintedWrap.fees shouldBe Fee
      mintedWrap.signed.getInputBoxesIds.size shouldBe 1

      // Wrap vault refresh (alone)
      val wrapVaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.WrapVault, None).contract, MinBox + 30000000L,
        Seq(tok(wrapReqIn.id.toString, 2L), tok("ee" * 32, 1L)), Seq.empty, Some(old)))
      val refreshedWrap = ZcBatchTransactions.refreshWrapVault(ctx, wallet, read[ZcWrapVault](nodeOf(wrapVaultIn)),
        wrapVaultIn, feeTo, minerFee = 0L, height = h)
      refreshedWrap.fees shouldBe RefreshBounty
      refreshedWrap.signed.getInputBoxesIds.size shouldBe 1
    }
  }
}
