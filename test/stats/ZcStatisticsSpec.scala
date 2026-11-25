package stats

import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox, NodeTransaction}
import node.rest.NodeCodecs
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, NodeSync, TxOutput}
import support.FakeNodeContext
import transactions.batching.arb.{ArbFixtures, ArbPoolEntry, ErgoDexArbPool}
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.batching.sigmafi.zc._
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * SigmaFi ZC Standard batching and collateral arbitrage as the statistics see them in a block: every transaction
 * is built by `ZcBatchTransactions` or `ZcCollateralArb` and signed against the real pinned ZC and AMM contracts,
 * then decoded through `NodeCodecs` + `NodeSync.blockTx` (carrying the real `spendingProof.extension`) and read
 * back through `BatchingStatistics.read` and `BatchingStatistics.arbCycles`.
 */
class ZcStatisticsSpec extends AnyFlatSpec with Matchers {

  import ArbFixtures.{Erg => _, id => arbId, _}
  import ZcFixtures.{Erg => _, _}
  import ZcPricing.{RefreshAge, RefreshBounty, ShareCount}

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val Reserve = 50000000L
  private val Fee = 5000000L
  private val SigUsd = ZcContracts.SigUsdId
  private val TokenA = "aa" * 32
  private val TokenB = "bb" * 32
  private val UnpricedNft = "cc" * 32

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 3))
  private val mine = wallet.contract.ergoTreeHex
  private val stranger = "0008cd" + "02" + "11" * 32
  private val borrower: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val borrowerHash: String = Hex.toHexString(borrower.hashedPropBytes)
  private val lender: Contract = Contract.fromErgoContract(wallet.addresses(2).toErgoContract)
  private val lenderHash: String = Hex.toHexString(lender.hashedPropBytes)
  private val seller: Contract = borrower
  private val sellerHash: String = borrowerHash

  private var seq = 500
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

  private def decoded(signed: SignedTransaction): BlockTx = {
    val json = CandidateTx.signed(signed, "zc").json
    val nodeTx: NodeTransaction = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(json).getAsJsonObject)
    NodeSync.blockTx(nodeTx)
  }

  private def blockOf(height: Int, txs: Seq[BlockTx], confirmed: Seq[NodeBox]): BlockInfo = {
    val boxes = (confirmed.map(NodeSync.txOutput) ++ txs.flatMap(_.outputs)).map(b => b.id -> b).toMap
    BlockInfo("block", height, txs,
      resolvedInputs = txs.flatMap(_.inputs).flatMap(in => boxes.get(in.id).map(in.id -> _)).toMap)
  }

  private def oracleBox(ctx: BlockchainContext, price: Long, created: Int): InputUTXO =
    seeded(ctx, UTXO(Contract.SIGMA_TRUE, MinBox, Seq(tok(ZcContracts.OracleNft, 1L)),
      Seq(ErgoValue.of(price), ErgoValue.of(1)), Some(created)))

  private def ergOrder(ctx: BlockchainContext, h: Int, expiryOffset: Long = 5000L): (ZcOrder, InputUTXO) = {
    val terms = Seq(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong + expiryOffset, Fee, 0L, 15000L, 60L)
    val in = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
      Fee + MinBox, Seq(tok(SigUsd, 2000L)),
      Seq(longs(terms: _*), tokens(), bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))),
      Some(h - 10)))
    read[ZcOrder](nodeOf(in)) -> in
  }

  private def ergOffer(ctx: BlockchainContext, h: Int, capital: Long = 10 * Erg): (ZcOffer, InputUTXO) = {
    val terms = Seq(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 0L, 0L)
    val in = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract, capital, Seq.empty,
      Seq(bytes(lenderHash), longs(terms: _*), bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)),
        longs(), ZcUserTransactions.bytesList(Seq.empty), bytes("")), Some(h - 10)))
    read[ZcOffer](nodeOf(in)) -> in
  }

  private def poolEntry(box: NodeBox): ArbPoolEntry =
    ArbPoolEntry(ErgoDexArbPool(ErgoDexPool.parse(box).get), box)

  // ─── ZC batching attribution ("sigmafi") ───────────────────────────────────

  "ZC batching statistics" should "attribute fee-less local packs and split miner fees on broadcast packs" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100

      val maturedIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        2 * Erg + Reserve, Seq(tok("11" * 32, 2L)),
        Seq(bytes("11" * 32), bytes(borrowerHash), longs(2 * Erg, Erg, 2 * Erg, h.toLong - 10, h.toLong - 50, Fee, 0L),
          tokens(), bytes(expr(ZcExpression.MaturityLiquidation)), longs(Reserve, h.toLong - 100, h.toLong - 10)), Some(h - 100)))
      val oracle = oracleBox(ctx, 500000000L, h - 5)
      val earlyIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        Erg + Reserve, Seq(tok("22" * 32, 2L)),
        Seq(bytes("22" * 32), bytes(borrowerHash), longs(2 * Erg, Erg, 2 * Erg, h.toLong + 5000, h.toLong - 50, Fee, 0L, 15000L, 60L),
          tokens(), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg)), longs(Reserve, h.toLong - 100, h.toLong + 5000)),
        Some(h - 100)))
      val (expOrder, expOrderIn) = ergOrder(ctx, h, expiryOffset = -5L)
      val expOfferIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract, Erg, Seq.empty,
        Seq(bytes(lenderHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong - 5, 1L, 30L, 0L, 0L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)), longs(), ZcUserTransactions.bytesList(Seq.empty), bytes("")),
        Some(h - 50)))
      val expAskIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox + Fee,
        Seq(tok("33" * 32, 10L)), Seq(bytes(sellerHash), longs(Erg, 10L, 1L, h.toLong - 5)), Some(h - 50)))
      val expLotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, MinBox + Fee,
        Seq(tok("44" * 32, 10L)), Seq(bytes(sellerHash), longs(2 * Erg, Erg, h.toLong - 100, h.toLong - 10, h.toLong - 5, Fee)),
        Some(h - 50)))
      val oldVaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.BondVault, None).contract, MinBox + RefreshBounty,
        Seq(tok("55" * 32, 2L)), Seq(bytes("66" * 32), byte(1)), Some(old)))

      val inputs = Seq(maturedIn, earlyIn, expOrderIn, expOfferIn, expAskIn, expLotIn, oldVaultIn)
      val confirmed = inputs.map(nodeOf)
      val pieces = Seq(
        ZcBatchTransactions.liquidationPiece(read[ZcBond](confirmed(0)), maturedIn, ZcContracts.expression(ZcExpression.MaturityLiquidation, None).bytes, None),
        ZcBatchTransactions.liquidationPiece(read[ZcBond](confirmed(1)), earlyIn, ZcContracts.expression(ZcExpression.OracleLiquidation, Some(ZcMarket.Erg)).bytes, Some(oracle)),
        ZcBatchTransactions.orderRefundPiece(expOrder, expOrderIn, borrower),
        ZcBatchTransactions.offerRefundPiece(read[ZcOffer](confirmed(3)), expOfferIn, lender),
        ZcBatchTransactions.askRefundPiece(read[ZcAsk](confirmed(4)), expAskIn, seller),
        ZcBatchTransactions.lotRefundPiece(read[ZcLot](confirmed(5)), expLotIn, seller),
        ZcBatchTransactions.settlementRefreshPiece(ctx, read[ZcSettlement](confirmed(6)), oldVaultIn, h))

      // Fee-less local pack
      val localPack = ZcBatchTransactions.pack(ctx, wallet, pieces, wallet.contract, minerFee = 0L, height = h)
      val localTx = decoded(localPack.signed)
      val localBlock = blockOf(h, Seq(localTx), confirmed)
      val localFees = BatchingStatistics.read(localBlock, localTx, NetworkType.MAINNET, mine)
      localFees.map(f => (f.protocol, f.orderId, f.grossNanoErg, f.transactionFeeNanoErg, f.local)) shouldBe Vector(
        ("sigmafi", confirmed(0).boxId, Reserve.toString, "0", true),
        ("sigmafi", confirmed(1).boxId, Reserve.toString, "0", true),
        ("sigmafi", confirmed(2).boxId, Fee.toString, "0", true),
        ("sigmafi", confirmed(3).boxId, Fee.toString, "0", true),
        ("sigmafi", confirmed(4).boxId, Fee.toString, "0", true),
        ("sigmafi", confirmed(5).boxId, Fee.toString, "0", true),
        ("sigmafi", confirmed(6).boxId, RefreshBounty.toString, "0", true))
      BatchingStatistics.read(localBlock, localTx, NetworkType.MAINNET, stranger).map(_.local) shouldBe Vector.fill(7)(false)

      // Broadcast pack with miner fee split across 7 records (1,000,000 = 1*142,858 + 6*142,857)
      val broadcastPack = ZcBatchTransactions.pack(ctx, wallet, pieces, wallet.contract, minerFee = Parameters.MinFee, height = h)
      val bTx = decoded(broadcastPack.signed)
      val bBlock = blockOf(h, Seq(bTx), confirmed)
      val bFees = BatchingStatistics.read(bBlock, bTx, NetworkType.MAINNET, mine)
      bFees.size shouldBe 7
      bFees.map(f => BigInt(f.transactionFeeNanoErg)).sum shouldBe BigInt(Parameters.MinFee)
      bFees.map(_.transactionFeeNanoErg) shouldBe Vector("142858", "142857", "142857", "142857", "142857", "142857", "142857")
      // Storage-rented input (var 127) must be excluded
      val rented = localTx.copy(inputs = localTx.inputs.map(in =>
        in.copy(spendingProof = in.spendingProof.map(p => InputSpendingProof(p.ext + ("127" -> ErgoValue.of(0).toHex))))))
      BatchingStatistics.read(localBlock.copy(txs = Seq(rented)), rented, NetworkType.MAINNET, mine) shouldBe empty
      // A pack whose miner fee exceeds total gross rewards must be rejected (no negative net attribution)
      val hugeFeeOut = bTx.outputs.last.copy(value = 10L * Erg)
      val overFeeTx = bTx.copy(outputs = bTx.outputs.init :+ hugeFeeOut)
      BatchingStatistics.read(bBlock.copy(txs = Seq(overFeeTx)), overFeeTx, NetworkType.MAINNET, mine) shouldBe empty
    }
  }

  it should "attribute aged bond refreshes (EIP-39) under sigmafi" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      val bondIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        2 * Erg + Reserve, Seq(tok("77" * 32, 2L)),
        Seq(bytes("77" * 32), bytes(borrowerHash), longs(2 * Erg, Erg, 2 * Erg, h.toLong + 10000, h.toLong - 500000, Fee, 0L),
          tokens(), bytes(expr(ZcExpression.MaturityLiquidation)), longs(Reserve, old.toLong, h.toLong + 10000)), Some(old)))
      val bondBox = nodeOf(bondIn)
      val piece = ZcBatchTransactions.bondRefreshPiece(ctx, read[ZcBond](bondBox), bondIn, borrower, h)
      val refreshed = ZcBatchTransactions.pack(ctx, wallet, Seq(piece), wallet.contract, minerFee = 0L, height = h)
      val tx = decoded(refreshed.signed)
      val fees = BatchingStatistics.read(blockOf(h, Seq(tx), Seq(bondBox)), tx, NetworkType.MAINNET, mine)
      fees shouldBe Vector(BatchingFee(tx.id, bondBox.boxId, "sigmafi", RefreshBounty.toString, "0", local = true))
    }
  }

  it should "attribute order matches and ask matches (full and partial) under sigmafi, and ignore user direct fills" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val oracle = oracleBox(ctx, 500000000L, h - 5)
      val (offer, offerIn) = ergOffer(ctx, h, capital = 10 * Erg)
      val (order, orderIn) = ergOrder(ctx, h)

      // 1. matchOrder (fee-less)
      val matchedOrder = ZcBatchTransactions.matchOrder(ctx, wallet, offer, offerIn, order, orderIn, borrower, lender,
        order.terms(3), order.principal, oracle, wallet.contract, minerFee = 0L, height = h)
      val moTx = decoded(matchedOrder.tx.signed)
      val moBlock = blockOf(h, Seq(moTx), Seq(nodeOf(orderIn), nodeOf(offerIn)))
      BatchingStatistics.read(moBlock, moTx, NetworkType.MAINNET, mine) shouldBe Vector(
        BatchingFee(moTx.id, order.boxId, "sigmafi", Fee.toString, "0", local = true))
      BatchingStatistics.read(moBlock, moTx, NetworkType.MAINNET, stranger).map(_.local) shouldBe Vector(false)

      // 2. matchAsk (full fill recovers ask.box.value + Fee)
      val bondBox = out(matchedOrder.tx.signed, 1)
      val bondIn = input(ctx, bondBox)
      val fullAskIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(order.boxId, 1L)), Seq(bytes(sellerHash), longs(Erg, 1L, 1L, h.toLong + 5000L)), Some(h - 5)))
      val fullAsk = read[ZcAsk](nodeOf(fullAskIn))
      val matchedFull = ZcBatchTransactions.matchAsk(ctx, wallet, matchedOrder.nextOffer, matchedOrder.nextOfferInput,
        fullAsk, fullAskIn, seller, lender, units = 1L, price = Erg, bondIn, oracle, orderIn.bytes, None,
        wallet.contract, minerFee = Parameters.MinFee, height = h)
      val mfTx = decoded(matchedFull.tx.signed)
      val mfBlock = blockOf(h, Seq(mfTx), Seq(nodeOf(fullAskIn), nodeOf(matchedOrder.nextOfferInput)))
      BatchingStatistics.read(mfBlock, mfTx, NetworkType.MAINNET, mine) shouldBe Vector(
        BatchingFee(mfTx.id, fullAsk.boxId, "sigmafi", (Fee + 3000000L).toString, Parameters.MinFee.toString, local = true))

      // 3. User direct fillOrder (no LendOffer at input 1) must NOT be counted as batching revenue
      val (userOrder, userOrderIn) = ergOrder(ctx, h)
      val funding = seeded(ctx, UTXO(wallet.contract, 5 * Erg))
      val spend = ZcUserTransactions.fillOrder(userOrder, userOrderIn, borrower, lender, ctx.getHeight.toLong, userOrder.terms(3),
        Reserve, Some(oracle))
      val userSigned = wallet.sign(ZcUserTransactions.plan(ctx, wallet, spend).build(Seq(funding)))
      val userTx = decoded(userSigned)
      val userBlock = blockOf(h, Seq(userTx), Seq(nodeOf(userOrderIn), nodeOf(funding)))
      BatchingStatistics.read(userBlock, userTx, NetworkType.MAINNET, mine) shouldBe empty

      // 4. Borrower extinguish (direct 3-unit burn of live bond) must NOT be counted as batching revenue
      val unitOut = out(userSigned, 0)
      val liveBondOut = out(userSigned, 1)
      val borrowerUnitIn = seeded(ctx, UTXO(borrower, MinBox, Seq(tok(userOrder.boxId, 1L))))
      val extSpend = ZcUserTransactions.extinguish(read[ZcBond](liveBondOut), input(ctx, liveBondOut), borrowerUnitIn, borrower)
      val extSigned = wallet.sign(ZcUserTransactions.plan(ctx, wallet, extSpend).build(Seq.empty))
      val extTx = decoded(extSigned)
      val extBlock = blockOf(h, Seq(extTx), Seq(liveBondOut, nodeOf(borrowerUnitIn)))
      BatchingStatistics.read(extBlock, extTx, NetworkType.MAINNET, mine) shouldBe empty
    }
  }

  it should "attribute standalone share mints, pulls, wrap mints and wrap refreshes under sigmafi" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      val asset = "aa" * 32
      val share = "bb" * 32

      // 1. Share mint (FractionalMint)
      val shareReqIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.FractionalMint, None).contract, 2 * MinBox + Fee + Fee,
        Seq(tok(asset, 1L)),
        Seq(ErgoValue.of("n".getBytes("UTF-8")), ErgoValue.of("d".getBytes("UTF-8")), ErgoValue.of("0".getBytes("UTF-8")),
          bytes(lenderHash), longs(ShareCount, Fee, Fee),
          ZcUserTransactions.bytesList(Seq("n", "d", "0").map(_.getBytes("UTF-8")))), Some(h - 5)))
      val mintedShares = ZcBatchTransactions.mintShares(ctx, wallet, read[ZcShareRequest](nodeOf(shareReqIn)),
        shareReqIn, lender, None, wallet.contract, minerFee = 0L, height = h)
      val msTx = decoded(mintedShares.signed)
      BatchingStatistics.read(blockOf(h, Seq(msTx), Seq(nodeOf(shareReqIn))), msTx, NetworkType.MAINNET, mine) shouldBe
        Vector(BatchingFee(msTx.id, nodeOf(shareReqIn).boxId, "sigmafi", Fee.toString, "0", local = true))

      // 2. Pull
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, MinBox + Fee,
        Seq(tok(share, ShareCount + 1L), tok(asset, 1L)),
        Seq(bytes(asset), longs(ShareCount, ShareCount, Fee), byte(0),
          longs(2 * Erg, 10000L, 9000L, 720L, 2160L, 100L, 0L), bytes("")), Some(h - 10)))
      val settleIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.BondVault, None).contract, MinBox,
        Seq(tok(asset, 2L), tok("cc" * 32, 50L)), Seq(bytes("dd" * 32), byte(1)), Some(h - 5)))
      val pulled = ZcBatchTransactions.pull(ctx, wallet, read[ZcShareVault](nodeOf(vaultIn)), vaultIn,
        read[ZcSettlement](nodeOf(settleIn)), settleIn, wallet.contract, minerFee = Parameters.MinFee, height = h)
      val pTx = decoded(pulled.signed)
      BatchingStatistics.read(blockOf(h, Seq(pTx), Seq(nodeOf(vaultIn), nodeOf(settleIn))), pTx, NetworkType.MAINNET, mine) shouldBe
        Vector(BatchingFee(pTx.id, nodeOf(vaultIn).boxId, "sigmafi", Fee.toString, Parameters.MinFee.toString, local = true))

      // 3. Wrap mint
      val wrapReqIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.WrapMint, None).contract, 2 * MinBox + 30000000L + Fee,
        Seq(tok("ee" * 32, 1L)), Seq(bytes(lenderHash), long(Fee),
          ZcUserTransactions.bytesList(ZcMetadata.receipt("ee" * 32, 1L).map(_.getBytes("UTF-8")))), Some(h - 5)))
      val mintedWrap = ZcBatchTransactions.mintWrap(ctx, wallet, read[ZcWrapRequest](nodeOf(wrapReqIn)), wrapReqIn,
        lender, wallet.contract, minerFee = 0L, height = h)
      val mwTx = decoded(mintedWrap.signed)
      BatchingStatistics.read(blockOf(h, Seq(mwTx), Seq(nodeOf(wrapReqIn))), mwTx, NetworkType.MAINNET, mine) shouldBe
        Vector(BatchingFee(mwTx.id, nodeOf(wrapReqIn).boxId, "sigmafi", Fee.toString, "0", local = true))

      // 4. Wrap vault refresh
      val wrapVaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.WrapVault, None).contract, MinBox + 30000000L,
        Seq(tok(wrapReqIn.id.toString, 2L), tok("ee" * 32, 1L)), Seq.empty, Some(old)))
      val refreshedWrap = ZcBatchTransactions.refreshWrapVault(ctx, wallet, read[ZcWrapVault](nodeOf(wrapVaultIn)),
        wrapVaultIn, wallet.contract, minerFee = 0L, height = h)
      val rwTx = decoded(refreshedWrap.signed)
      BatchingStatistics.read(blockOf(h, Seq(rwTx), Seq(nodeOf(wrapVaultIn))), rwTx, NetworkType.MAINNET, mine) shouldBe
        Vector(BatchingFee(rwTx.id, nodeOf(wrapVaultIn).boxId, "sigmafi", RefreshBounty.toString, "0", local = true))
    }
  }

  // ─── ZC collateral arb attribution ("arb") ─────────────────────────────────

  "ZC collateral arb statistics" should "attribute an ERG LotAuction + unpriced NFT sweep + AMM swap under arb without double-counting" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pA.nft -> pA.pool)
      val poolBoxes = Map(pA.nft -> SigmaFiBoxes.input(pA.box, ctx).get)
      val stakeBox = arbStakeBox(ctx, wallet, 10 * Erg)
      val stake = SigmaFiBoxes.input(stakeBox, ctx).get

      val lotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L), tok(UnpricedNft, 1L)),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10)))
      val lot = read[ZcLot](nodeOf(lotIn))
      val plan = ZcCollateralArb.evaluateLot(lot, pools, _ => Some(seller), h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      val (signedLegs, _, _, _) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, wallet.contract, MinBox).get
      val txs = signedLegs.map(decoded)
      val confirmed = Seq(nodeOf(lotIn), pA.box, stakeBox)
      val block = blockOf(h, txs, confirmed)

      val found = BatchingStatistics.arbCycles(block, ctx.getNetworkType, mine)
      found shouldBe Vector(BatchingStatistics.ArbCycle(
        BatchingFee(txs.last.id, lot.boxId, "arb", plan.netProfit.toString, "0", local = true),
        txs.map(_.id)))
      // Foreign miner sees local = false
      BatchingStatistics.arbCycles(block, ctx.getNetworkType, stranger).map(_.fee.local) shouldBe Vector(false)
      // Neither leg is counted by BatchingStatistics.read (no double-counting!)
      txs.foreach(tx => BatchingStatistics.read(block, tx, ctx.getNetworkType, mine) shouldBe empty)

      // Adversarial: tampering sweep value, sweep recipient, stake return, or adding a miner fee rejects the cycle
      val badSweepVal = txs.updated(0, txs.head.copy(outputs = txs.head.outputs.updated(1, txs.head.outputs(1).copy(value = MinBox + 1))))
      val badSweepTree = txs.updated(0, txs.head.copy(outputs = txs.head.outputs.updated(1, txs.head.outputs(1).copy(ergoTree = stranger))))
      val shortStake = txs.updated(1, txs(1).copy(outputs = txs(1).outputs.updated(1, txs(1).outputs(1).copy(value = stake.value - 1))))
      val feeOut = txs.last.outputs(2).copy(id = "fe" * 32, ergoTree = Contract.FEE.ergoTreeHex, index = 3)
      val withFee = txs.updated(0, txs.head.copy(outputs = txs.head.outputs :+ feeOut))
      Seq(badSweepVal, badSweepTree, shortStake, withFee).foreach { tampered =>
        BatchingStatistics.arbCycles(blockOf(h, tampered, confirmed), ctx.getNetworkType, mine) shouldBe empty
      }
    }
  }

  it should "attribute a SigUSD DutchAuctionVault acquire + direct-profit purchase (with SIGMA_TRUE profit) under arb" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pUsd = poolEntry(nativePool(ctx, arbId(2), SigUsd, 1000 * Erg, 200000L))
      val pools = Map(pUsd.nft -> pUsd.pool)
      val poolBoxes = Map(pUsd.nft -> SigmaFiBoxes.input(pUsd.box, ctx).get)
      val stakeBox = arbStakeBox(ctx, wallet, 10 * Erg)
      val stake = SigmaFiBoxes.input(stakeBox, ctx).get

      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 3 * Erg,
        Seq(tok("55" * 32, ShareCount + 1L)),
        Seq(bytes("66" * 32), longs(ShareCount, ShareCount, 5000000L), byte(1),
          longs(200L, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 100L), bytes(SigUsd)), Some(h - 100)))
      val vault = read[ZcShareVault](nodeOf(vaultIn))
      val plan = ZcCollateralArb.evaluateVault(vault, pools, h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      val (signedLegs, _, _, _) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, Contract.SIGMA_TRUE, MinBox).get
      val txs = signedLegs.map(decoded)
      val confirmed = Seq(nodeOf(vaultIn), pUsd.box, stakeBox)
      val block = blockOf(h, txs, confirmed)

      val found = BatchingStatistics.arbCycles(block, ctx.getNetworkType, mine)
      found shouldBe Vector(BatchingStatistics.ArbCycle(
        BatchingFee(txs.last.id, pUsd.nft, "arb", plan.netProfit.toString, "0", local = true),
        txs.map(_.id)))
      txs.foreach(tx => BatchingStatistics.read(block, tx, ctx.getNetworkType, mine) shouldBe empty)
    }
  }

  it should "attribute a partial SaleOrder purchase (2 protocol outputs) + AMM swap under arb" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(3), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pA.nft -> pA.pool)
      val poolBoxes = Map(pA.nft -> SigmaFiBoxes.input(pA.box, ctx).get)
      val stakeBox = arbStakeBox(ctx, wallet, 10 * Erg)
      val stake = SigmaFiBoxes.input(stakeBox, ctx).get

      val askIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 20000L)), Seq(bytes(sellerHash), longs(500000000L, 1000L, 1000L, h.toLong + 500L)), Some(h - 5)))
      val ask = read[ZcAsk](nodeOf(askIn))
      val plan = ZcCollateralArb.evaluateSaleOrder(ask, pools, _ => Some(seller), h.toLong, 4 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      plan.target.asInstanceOf[ZcSaleOrderTarget].full shouldBe false
      val (signedLegs, _, _, _) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, wallet.contract, MinBox).get
      val txs = signedLegs.map(decoded)
      val confirmed = Seq(nodeOf(askIn), pA.box, stakeBox)
      val block = blockOf(h, txs, confirmed)

      val found = BatchingStatistics.arbCycles(block, ctx.getNetworkType, mine)
      found shouldBe Vector(BatchingStatistics.ArbCycle(
        BatchingFee(txs.last.id, ask.boxId, "arb", plan.netProfit.toString, "0", local = true),
        txs.map(_.id)))
      txs.foreach(tx => BatchingStatistics.read(block, tx, ctx.getNetworkType, mine) shouldBe empty)
    }
  }

  it should "attribute a ZC collateral arb broadcast as one transaction under arb, with its miner fee" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val lotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L)),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10)))
      val book = ZcBook.seeded(h - 1, "00" * 32, Seq(ZcEntry(read[ZcLot](nodeOf(lotIn)), h - 5)), Seq.empty, cut = false)
      val settings = configs.ZcCollateralArbConfig.Default
      val planned = ZcMempoolArb.best(book, Map(pA.nft -> pA.pool), Map(pA.nft -> pA.box), _ => Some(seller), h, settings,
        100000L, Set.empty).get
      val fundingIn = seeded(ctx, UTXO(wallet.contract, 5 * Erg))
      val signed = ZcMempoolArb.sign(ctx, wallet, planned, input(ctx, pA.box), input(ctx, nodeOf(lotIn)), Seq(fundingIn),
        settings)
      val tx = decoded(signed)
      val block = blockOf(h, Seq(tx), Seq(nodeOf(lotIn), pA.box, nodeOf(fundingIn)))
      val net = signed.getOutputsToSpend.get(2).getValue - fundingIn.value
      BatchingStatistics.arbCycles(block, ctx.getNetworkType, mine) shouldBe Vector(BatchingStatistics.ArbCycle(
        BatchingFee(tx.id, nodeOf(lotIn).boxId, "arb", net.toString, ZcMempoolArb.TxFee.toString, local = true), Seq(tx.id)))
      BatchingStatistics.arbCycles(block, ctx.getNetworkType, stranger).map(_.fee.local) shouldBe Vector(false)
      BatchingStatistics.read(block, tx, ctx.getNetworkType, mine) shouldBe empty
      // The wallet output paid elsewhere is no arb of anyone's
      val elsewhere = tx.copy(outputs = tx.outputs.updated(2, tx.outputs(2).copy(ergoTree = stranger)))
      BatchingStatistics.arbCycles(blockOf(h, Seq(elsewhere), Seq(nodeOf(lotIn), pA.box, nodeOf(fundingIn))),
        ctx.getNetworkType, mine) shouldBe empty
    }
  }
}
