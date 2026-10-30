package lithosdex

import org.ergoplatform.sdk.ErgoId
import sigma.Coll
import sigma.data.CBigInt
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * Script and register reads the two genesis checkers share. Registers are read by matching their value's
 * type, so a register of the wrong type reads as absent rather than throwing on data a stranger wrote.
 */
private[lithosdex] object GenesisReads {

  def sameScript(a: Contract, b: Contract): Boolean = a.propBytes.sameElements(b.propBytes)

  def longAt(box: InputUTXO, i: Int): Option[Long] =
    box.registers.lift(i).map(_.getValue).collect { case v: java.lang.Long => v.longValue }

  def longsAt(box: InputUTXO, i: Int): Option[Seq[Long]] =
    box.registers.lift(i).map(_.getValue).collect { case c: Coll[_] => c.toArray.toSeq }.flatMap { items =>
      val longs = items.collect { case v: java.lang.Long => v.longValue }
      if (longs.size == items.size) Some(longs) else None
    }

  def bigIntAt(box: InputUTXO, i: Int): Option[BigInt] =
    box.registers.lift(i).map(_.getValue).collect { case v: CBigInt => BigInt(v.wrappedValue) }

  def bytesAt(box: InputUTXO, i: Int): Option[Array[Byte]] =
    box.registers.lift(i).map(_.getValue).collect { case c: Coll[_] => c.toArray.toSeq }.flatMap { items =>
      val bytes = items.collect { case b: java.lang.Byte => b.byteValue }
      if (bytes.size == items.size) Some(bytes.toArray) else None
    }

  /** Fee numerators a pool may start with: dexFee in (0, denom], each side fee in [0, denom). */
  def feesBounded(fees: Seq[Long]): Boolean = {
    val d = LDHelpers.FEE_DENOM
    fees.size == 3 && fees.head > 0 && fees.head <= d && fees.tail.forall(f => f >= 0 && f < d)
  }

  /** Units of `token` across `boxes`. */
  def held(boxes: Seq[InputUTXO], token: ErgoId): Long =
    boxes.iterator.flatMap(_.tokens).filter(_.id == token).map(_.amount).sum

  /**
   * What is wrong with the provisions a genesis created for its creator. Each must be a fresh provision of
   * this deployment, shaped as a deposit would leave it, owned by an NFT minted once that no other genesis
   * provision shares. Together they may not represent so much of the supply that less than MIN_SUPPLY is
   * left that no provision represents.
   *
   * @param provisions every output of the genesis transaction other than the pool that holds `provToken`
   * @param owners     what the node reports was minted of each owner NFT those provisions name
   * @param ownTokens  the pool's NFTs, provision token and traded tokens, none of which may own a provision
   */
  def provisionProblems(pool: InputUTXO, provisions: Seq[InputUTXO], guard: Contract, provToken: ErgoId,
                        owners: Map[String, Long], ownTokens: Set[ErgoId], supply: Option[Long]): Seq[String] = {
    def ownerOf(p: InputUTXO): Option[String] = bytesAt(p, 2).filter(_.length == 32).map(b => new ErgoId(b).toString)
    val each = provisions.flatMap { p =>
      val id = p.id.toString
      val owner = ownerOf(p)
      Seq(
        (p.input.getTransactionId == pool.input.getTransactionId) ->
          s"provision $id was not created by the genesis transaction",
        sameScript(p.contract, guard) ->
          s"provision $id is not at the provision guard for this pool's own ids",
        (p.tokens.size == 1 && p.tokens.head.id == provToken && p.tokens.head.amount == 1L) ->
          s"provision $id must hold one provision token and nothing else",
        (bigIntAt(p, 0).contains(BigInt(0)) && bigIntAt(p, 1).contains(BigInt(0))) ->
          s"provision $id entries R4 and R5 must both be BigInt 0",
        owner.isDefined -> s"provision $id R6 must be a 32-byte owner NFT id",
        owner.forall(o => owners.get(o).contains(1L)) ->
          s"provision $id owner NFT ${owner.getOrElse("")} minted ${owner.flatMap(owners.get).getOrElse("an unknown amount")}; it must be 1",
        owner.forall(o => !ownTokens.exists(_.toString == o)) ->
          s"provision $id is owned by one of the pool's own tokens",
        longAt(p, 3).exists(_ > 0L) -> s"provision $id R7 must be a positive Long of shares",
        (p.registers.size == 4) -> s"provision $id must carry exactly R4 to R7, found ${p.registers.size}"
      ).collect { case (false, problem) => problem }
    }
    val named = provisions.flatMap(ownerOf)
    val represented = provisions.flatMap(longAt(_, 3)).map(BigInt(_)).sum
    each ++ Seq(
      (named.distinct.size == named.size) -> "two genesis provisions share one owner NFT",
      // With no provisions, the pool's own supply check already covers this
      (provisions.isEmpty || supply.forall(s => BigInt(s) - represented >= LDHelpers.MIN_SUPPLY)) ->
        (s"genesis provisions represent $represented shares of a supply of ${supply.getOrElse("none")}, leaving " +
          s"under the ${LDHelpers.MIN_SUPPLY} no provision may represent")
    ).collect { case (false, problem) => problem }
  }

  /** Genesis provisions holding less ERG than a deposit gives one. */
  def provisionWarnings(provisions: Seq[InputUTXO]): Seq[String] =
    provisions.filter(_.value < LDHelpers.PROVISION_MIN).map(p =>
      s"provision ${p.id} holds ${p.value} nanoERG, under the ${LDHelpers.PROVISION_MIN} that survives two storage-fee collections")
}
