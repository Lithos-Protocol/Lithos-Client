package transactions.batching.sigmafi.zc

import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import sigmafi.zc.{ZcContracts, ZcKind, ZcMarket, ZcScript}

import scala.util.Try

/** A ZC box this client read: its contract, and the script hash it names for an owner, borrower or seller. */
sealed trait ZcRecord {
  def box: NodeBox
  def script: ZcScript
  def boxId: String = box.boxId
  /** The party the box names by script hash, which zcs1 script disclosure covers. */
  def namedHash: Option[String]
}

/**
 * An `Order` or `PricedOrder`. `terms` is R4: 0 repayment, 1 principal nanoERG, 2 repayment ERG floor, 3 maturity
 * height, duration or (window) least duration, 4 expiry height, 5 expiry fee, 6 mode (0 fixed, 1 from the fill,
 * 2 window), 7 on a payload only the liquidation rule reads. `guardHash` is the optional fill guard; a priced
 * order's `pricingHash` and R9 `pricingTerms` price its principal, whose stored amounts are then floors. `policies`
 * is how many entries a priced order's R8 holds; its shell fills only with exactly two.
 */
final case class ZcOrder(box: NodeBox, script: ZcScript, market: ZcMarket, terms: IndexedSeq[Long],
                         principalTokens: Seq[NodeAsset], borrowerHash: String, liqHash: String,
                         guardHash: Option[String], pricingHash: Option[String], pricingTerms: IndexedSeq[Long],
                         policies: Int = 0)
  extends ZcRecord {
  def namedHash: Option[String] = Some(borrowerHash)
  def priced: Boolean = script.kind == ZcKind.PricedOrder
  def repayment: Long = terms(0)
  def principalErg: Long = terms(1)
  def expiry: Long = terms(4)
  def expiryFee: Long = terms(5)
  def mode: Long = terms(6)
  def payload: IndexedSeq[Long] = terms.drop(7)
  /** The principal in the market's unit: nanoERG, or the one principal token's amount. */
  def principal: Long = if (market.tokenId.isEmpty) principalErg else principalTokens.headOption.fold(0L)(_.amount)
}

/** A live bond: holds its asset's two locked units first, then the collateral. R9 is (fee reserve, start, maturity). */
final case class ZcBond(box: NodeBox, script: ZcScript, market: ZcMarket, terms: IndexedSeq[Long],
                        principalTokens: Seq[NodeAsset], borrowerHash: String, liqHash: String, feeReserve: Long,
                        start: Long, maturity: Long) extends ZcRecord {
  def namedHash: Option[String] = Some(borrowerHash)
  def asset: String = box.assets.head.tokenId
  def repayment: Long = terms(0)
  def collateral: Seq[NodeAsset] = box.assets.drop(1)
  def collateralErg: Long = box.value - feeReserve
}

/** A `BondVault` holding a settlement: the asset's two units, then the proceeds. R4 names the bond box that settled. */
final case class ZcSettlement(box: NodeBox, script: ZcScript, bondBoxId: String, liquidated: Boolean) extends ZcRecord {
  def namedHash: Option[String] = None
  def asset: String = box.assets.head.tokenId
  def proceeds: Seq[NodeAsset] = box.assets.drop(1)
  /** The registers a successor keeps: R4 the bond box and R5 whether it was liquidated. Anything after is dropped. */
  def schemaRegisters: Int = 2
}

/**
 * A `LendOffer`. `terms` is R5: 0 least yield, 1 and 2 least and most blocks to maturity, 3 batcher fee, 4 new-bond
 * fee reserve, 5 collateral floor, 6 expiry, 7 least price, 8 oracle age, 9 least auction floor, 10 most auction
 * expiry; a token offer adds 11 original capital, 12 ERG budget, 13 least fill, 14 reward budget; then the curve.
 * `predecessor` is the offer box a fill continued this one from, the suffix of R6.
 */
final case class ZcOffer(box: NodeBox, script: ZcScript, market: ZcMarket, ownerHash: String, terms: IndexedSeq[Long],
                         policyHash: String, predecessor: Option[String], fractional: IndexedSeq[Long],
                         metadata: Seq[Array[Byte]], mintHash: Array[Byte]) extends ZcRecord {
  def namedHash: Option[String] = Some(ownerHash)
  def expiry: Long = terms(6)
  /** The least nanoERG the offer's continuation may hold after a fill. */
  lazy val restLeast: Long = ZcSizing.offerRestLeast(box)
  /** Capital in the market's unit: the box's nanoERG, or its repayment token. */
  def capital: Long = market.tokenId.fold(box.value)(id => box.assets.filter(_.tokenId == id).map(_.amount).sum)
  /** The yield curve's (yield bps, blocks left) points, after the market's fixed terms. */
  def curve: Seq[(Long, Long)] = terms.drop(ZcBoxes.offerBase(market)).grouped(2).collect { case Seq(y, d) => y -> d }.toSeq
}

/**
 * A `SaleOrder` or a `PricedAsk`, selling its one token. `terms` is R5: price numerator and denominator (a priced
 * ask's floor), least fill, expiry; a priced ask adds N and its pricing payload. `predecessor` is the ask a partial
 * fill continued this one from: a sale order's R6, a priced ask's R8.
 */
final case class ZcAsk(box: NodeBox, script: ZcScript, market: ZcMarket, sellerHash: String, terms: IndexedSeq[Long],
                       pricingHash: Option[String], pricedAsset: Option[String], predecessor: Option[String])
  extends ZcRecord {
  def namedHash: Option[String] = Some(sellerHash)
  def priced: Boolean = script.kind == ZcKind.PricedAsk
  def token: NodeAsset = box.assets.head
  def expiry: Long = terms(3)
}

/** A `LotAuction`: everything in the box, sold whole. R5 is (opening, floor, start, end, expiry, refund fee). */
final case class ZcLot(box: NodeBox, script: ZcScript, market: ZcMarket, sellerHash: String, terms: IndexedSeq[Long])
  extends ZcRecord {
  def namedHash: Option[String] = Some(sellerHash)
  def expiry: Long = terms(4)
}

/**
 * A share mint request holding one bond unit: a `FractionalMint`, or a market's `DutchAuctionMint` (`dutch`). R8 is
 * (N, pull fee, mint fee), then a Dutch request's auction terms. Its own id becomes the share token's.
 */
final case class ZcShareRequest(box: NodeBox, script: ZcScript, dutch: Option[ZcMarket], ownerHash: String,
                                deliveryTag: Option[String], terms: IndexedSeq[Long], metadata: Seq[Array[Byte]])
  extends ZcRecord {
  def namedHash: Option[String] = Some(ownerHash)
  def bondAsset: String = box.assets.head.tokenId
  def shareId: String = box.boxId
}

/**
 * A `FractionalVault` or `DutchAuctionVault`, holding its shares' N+1 reserve first. `state` is a Dutch vault's
 * R6: 0 repaid, 1 floor auction, 2 sold or nothing to sell, 3 paid in kind, 4 excess auction.
 */
final case class ZcShareVault(box: NodeBox, script: ZcScript, dutch: Boolean, bondAsset: String, shares: Long,
                              outstanding: Long, pullFee: Long, state: Option[Byte], auction: IndexedSeq[Long],
                              currency: Option[Array[Byte]]) extends ZcRecord {
  def namedHash: Option[String] = None
  def shareId: String = box.assets.head.tokenId
  /** Whether the bond unit is still inside, so the vault has not been pulled. */
  def holding: Boolean = box.assets.drop(1).exists(_.tokenId == bondAsset)
  /**
   * The registers a successor keeps: R4–R8 for a Dutch vault, R4–R5 for a plain one holding its bond unit, R4–R6 once
   * pulled. A later register is anyone's to add on a permissionless spend, and a refresh requires it gone.
   */
  def schemaRegisters: Int = if (dutch) 5 else if (holding) 2 else 3
}

/** A `WrapMint` request: one token waiting to be wrapped. Its own id becomes the receipt's. */
final case class ZcWrapRequest(box: NodeBox, script: ZcScript, ownerHash: String, mintFee: Long,
                               metadata: Seq[Array[Byte]]) extends ZcRecord {
  def namedHash: Option[String] = Some(ownerHash)
  def receiptId: String = box.boxId
  def wrapped: NodeAsset = box.assets.head
}

/** A `WrapVault`: the receipt's two locked units, then the wrapped token. */
final case class ZcWrapVault(box: NodeBox, script: ZcScript) extends ZcRecord {
  def namedHash: Option[String] = None
  def receiptId: String = box.assets.head.tokenId
  def wrapped: NodeAsset = box.assets(1)
}

/** The USD oracle pool box: R4 the price in nanoERG per USD, R5 its epoch. */
final case class ZcOracle(box: NodeBox, price: Long, epoch: Int)

/**
 * Reads ZC boxes from node JSON. A box qualifies by its exact tree first, then every register at the type its
 * contract writes, with the sizes and tokens the CLI's reader requires. Anyone can create a box under these
 * scripts with any registers, so a box that fails any check is not a ZC box to this client.
 */
object ZcBoxes {

  private val BytesType = ErgoType.collType(ErgoType.byteType())
  private val LongsType = ErgoType.collType(ErgoType.longType())
  private val TokensType = ErgoType.collType(ErgoType.pairType(BytesType, ErgoType.longType()))
  private val BytesListType = ErgoType.collType(BytesType)

  /** Where a lend offer's curve starts in R5: after 11 fixed terms for ERG, 15 for a token. */
  def offerBase(m: ZcMarket): Int = if (m.tokenId.isEmpty) 11 else 15

  final val MaxCurvePoints = 20

  /** The least nanoERG every box holding tokens or registers keeps. */
  final val MinBox = 1000000L

  /** `box` read as the ZC box its tree names, or None when its tree is not one or any register fails. */
  def read(box: NodeBox): Option[ZcRecord] = ZcContracts.classify(box.ergoTree).flatMap(read(box, _))

  /**
   * `box` read as `script`, whose tree the caller already matched. The record keeps the pinned tree string in
   * place of the box's own copy, so every box of a contract shares one.
   */
  def read(found: NodeBox, script: ZcScript): Option[ZcRecord] = Try {
    val box = found.copy(ergoTree = script.ergoTreeHex)
    script.kind match {
      case ZcKind.Order | ZcKind.PricedOrder => order(box, script)
      case ZcKind.Bond => bond(box, script)
      case ZcKind.BondVault => settlement(box, script)
      case ZcKind.LendOffer => offer(box, script)
      case ZcKind.SaleOrder | ZcKind.PricedAsk => ask(box, script)
      case ZcKind.LotAuction => lot(box, script)
      case ZcKind.FractionalMint | ZcKind.DutchAuctionMint => shareRequest(box, script)
      case ZcKind.FractionalVault | ZcKind.DutchAuctionVault => shareVault(box, script)
      case ZcKind.WrapMint => wrapRequest(box, script)
      case ZcKind.WrapVault => wrapVault(box, script)
    }
  }.toOption.flatten

  /** `box` as the USD oracle pool box, when it holds the pool NFT once and its price and epoch read. */
  def oracle(box: NodeBox): Option[ZcOracle] = Try {
    if (!box.assets.exists(a => a.tokenId == ZcContracts.OracleNft && a.amount == 1L)) None
    else for {
      price <- long(box, 4) if price > 0L
      epoch <- int(box, 5)
    } yield ZcOracle(box, price, epoch)
  }.toOption.flatten

  /** Whether `box` carries the oracle pool NFT at all, before any parse. */
  def holdsOracleNft(box: NodeBox): Boolean = box.assets.exists(_.tokenId == ZcContracts.OracleNft)

  private def market(script: ZcScript): Option[ZcMarket] = script.market

  /** A priced order's R8 and R9 read as leniently as the CLI does: an unknown pricing hash is flagged later, not here. */
  private def order(box: NodeBox, script: ZcScript): Option[ZcOrder] = {
    val priced = script.kind == ZcKind.PricedOrder
    for {
      m <- market(script)
      terms <- longs(box, 4) if terms.length >= 7 && terms.length <= 64
      principal <- tokens(box, 5)
      borrower <- bytes(box, 6) if borrower.length == 32
      liq <- bytes(box, 7) if liq.length == 32
    } yield {
      val policies = if (priced) bytesList(box, 8).getOrElse(Seq.empty) else Seq.empty
      val guard = if (priced) policies.lift(1).filter(_.nonEmpty) else bytes(box, 8)
      val pricing = if (priced) Some(policies.headOption.getOrElse(Array.emptyByteArray)) else None
      val pricingTerms = if (priced) longs(box, 9).getOrElse(IndexedSeq.empty[Long]) else IndexedSeq.empty[Long]
      ZcOrder(box, script, m, terms, principal, hex(borrower), hex(liq), guard.map(hex), pricing.map(hex), pricingTerms,
        policies.size)
    }
  }

  private def bond(box: NodeBox, script: ZcScript): Option[ZcBond] = for {
    m <- market(script)
    first <- box.assets.headOption if first.amount == 2L
    origin <- bytes(box, 4) if hex(origin) == first.tokenId
    borrower <- bytes(box, 5) if borrower.length == 32
    terms <- longs(box, 6) if terms.length >= 7 && terms.length <= 64
    principal <- tokens(box, 7)
    liq <- bytes(box, 8) if liq.length == 32
    lender <- longs(box, 9) if lender.length == 3 && lender(0) >= 0L && lender(0) <= box.value - MinBox
  } yield ZcBond(box, script, m, terms, principal, hex(borrower), hex(liq), lender(0), lender(1), lender(2))

  private def settlement(box: NodeBox, script: ZcScript): Option[ZcSettlement] = for {
    first <- box.assets.headOption if first.amount == 2L
    bondId <- bytes(box, 4) if bondId.length == 32
    path <- byte(box, 5) if path == 0.toByte || path == 1.toByte
  } yield ZcSettlement(box, script, hex(bondId), path == 1.toByte)

  private def offer(box: NodeBox, script: ZcScript): Option[ZcOffer] = for {
    m <- market(script)
    owner <- bytes(box, 4) if owner.length == 32
    terms <- longs(box, 5) if offerTermsShape(m, terms.length)
    policyRecord <- bytes(box, 6) if policyRecord.length == 32 || policyRecord.length == 64
    fractional <- longs(box, 7)
    metadata <- bytesList(box, 8)
    mint <- bytes(box, 9)
  } yield ZcOffer(box, script, m, hex(owner), terms, hex(policyRecord.take(32)),
    Some(policyRecord.drop(32)).filter(_.length == 32).map(hex), fractional, metadata, mint)

  private def offerTermsShape(m: ZcMarket, size: Int): Boolean = {
    val base = offerBase(m)
    size >= base && size <= base + 2 * MaxCurvePoints && (size - base) % 2 == 0
  }

  private def ask(box: NodeBox, script: ZcScript): Option[ZcAsk] = {
    val priced = script.kind == ZcKind.PricedAsk
    for {
      m <- market(script)
      if box.assets.size == 1
      seller <- bytes(box, 4) if seller.length == 32
      terms <- longs(box, 5) if (if (priced) terms.length >= 5 && terms.length <= 64 else terms.length == 4)
      if terms(0) > 0L && terms(1) > 0L
      pricing <- if (priced) bytes(box, 6).map(Some(_)) else Some(None)
      asset <- if (priced) bytes(box, 7).filter(_.length == 32).map(Some(_)) else Some(None)
      predecessor = bytes(box, if (priced) 8 else 6).filter(_.length == 32)
    } yield ZcAsk(box, script, m, hex(seller), terms, pricing.map(hex), asset.map(hex), predecessor.map(hex))
  }

  private def lot(box: NodeBox, script: ZcScript): Option[ZcLot] = for {
    m <- market(script)
    seller <- bytes(box, 4) if seller.length == 32
    terms <- longs(box, 5) if terms.length == 6
  } yield ZcLot(box, script, m, hex(seller), terms)

  private def shareRequest(box: NodeBox, script: ZcScript): Option[ZcShareRequest] = {
    val dutch = if (script.kind == ZcKind.DutchAuctionMint) script.market else None
    for {
      first <- box.assets.headOption if box.assets.size == 1 && first.amount == 1L
      ownerRecord <- bytes(box, 7) if ownerRecord.length == 32 || ownerRecord.length == 64
      terms <- longs(box, 8) if terms.length == (if (dutch.isDefined) 8 else 3)
      metadata <- bytesList(box, 9) if metadata.size == 3
    } yield ZcShareRequest(box, script, dutch, hex(ownerRecord.take(32)),
      Some(ownerRecord.drop(32)).filter(_.length == 32).map(hex), terms, metadata)
  }

  private def shareVault(box: NodeBox, script: ZcScript): Option[ZcShareVault] = {
    val dutch = script.kind == ZcKind.DutchAuctionVault
    for {
      first <- box.assets.headOption
      asset <- bytes(box, 4) if asset.length == 32
      supply <- longs(box, 5) if supply.length == 3 && supply(0) < Long.MaxValue && first.amount == supply(0) + 1L
      auction <- if (dutch) longs(box, 7).filter(_.length == 7) else Some(IndexedSeq.empty[Long])
      currency <- if (dutch) bytes(box, 8).map(Some(_)) else Some(None)
    } yield ZcShareVault(box, script, dutch, hex(asset), supply(0), supply(1), supply(2), byte(box, 6), auction,
      currency)
  }

  private def wrapRequest(box: NodeBox, script: ZcScript): Option[ZcWrapRequest] =
    if (box.assets.size != 1) None
    else for {
      owner <- bytes(box, 4) if owner.length == 32
      fee <- long(box, 5)
      metadata <- bytesList(box, 6) if metadata.size == 3
    } yield ZcWrapRequest(box, script, hex(owner), fee, metadata)

  private def wrapVault(box: NodeBox, script: ZcScript): Option[ZcWrapVault] =
    if (box.assets.size != 2 || box.assets.head.amount != 2L || box.additionalRegisters.values.nonEmpty) None
    else Some(ZcWrapVault(box, script))

  // ─── registers ───────────────────────────────────────────────────────────

  private def hex(b: Array[Byte]): String = Hex.toHexString(b)

  /** The value of register `index`, when it decodes as `tpe`. */
  private def register(box: NodeBox, index: Int, tpe: ErgoType[_]): Option[Any] =
    box.additionalRegisters.get(index)
      .flatMap[ErgoValue[_]](h => Try(ErgoValue.fromHex(h)).toOption)
      .filter(_.getType == tpe)
      .map(_.getValue)

  private def bytes(box: NodeBox, index: Int): Option[Array[Byte]] =
    register(box, index, BytesType).collect { case c: sigma.Coll[Byte @unchecked] => c.toArray }

  private def longs(box: NodeBox, index: Int): Option[IndexedSeq[Long]] =
    register(box, index, LongsType).collect { case c: sigma.Coll[Long @unchecked] => wrapped(c.toArray) }

  /** An unboxed view of `a`: eight bytes a term, where a `Vector[Long]` boxes each one. */
  private def wrapped(a: Array[Long]): IndexedSeq[Long] = a

  private def long(box: NodeBox, index: Int): Option[Long] =
    register(box, index, ErgoType.longType()).collect { case l: java.lang.Long => l.longValue }

  private def int(box: NodeBox, index: Int): Option[Int] =
    register(box, index, ErgoType.integerType()).collect { case i: java.lang.Integer => i.intValue }

  private def byte(box: NodeBox, index: Int): Option[Byte] =
    register(box, index, ErgoType.byteType()).collect { case b: java.lang.Byte => b.byteValue }

  private def tokens(box: NodeBox, index: Int): Option[Seq[NodeAsset]] =
    register(box, index, TokensType).collect {
      case c: sigma.Coll[(sigma.Coll[Byte], Long) @unchecked] => c.toArray.toSeq
    }.filter(_.forall { case (id, n) => id.length == 32 && n > 0L })
      .map(_.map { case (id, n) => NodeAsset(Hex.toHexString(id.toArray), n) })

  private def bytesList(box: NodeBox, index: Int): Option[Seq[Array[Byte]]] =
    register(box, index, BytesListType).collect {
      case c: sigma.Coll[sigma.Coll[Byte] @unchecked] => c.toArray.toSeq.map(_.toArray)
    }

  /**
   * An ask's payout tag: R4 the 32-byte id of the ask it pays for, R5 the units bought. The ask contracts require
   * both on the box paying the seller, so a purchase is found from its outputs alone.
   */
  def payoutTag(box: NodeBox): Option[(String, Long)] = Try {
    for {
      askId <- bytes(box, 4) if askId.length == 32
      units <- long(box, 5) if units > 0L
    } yield hex(askId) -> units
  }.toOption.flatten

  /** The trees a `Coll[Coll[Byte]]` context-variable value lists, as zcs1 script disclosure writes it. */
  def disclosedTrees(serialized: String): Seq[String] =
    Try(ErgoValue.fromHex(serialized)).toOption.filter(_.getType == BytesListType).map(_.getValue).toSeq.flatMap {
      case c: sigma.Coll[sigma.Coll[Byte] @unchecked] => c.toArray.toSeq.map(t => Hex.toHexString(t.toArray))
      case _ => Seq.empty
    }
}
