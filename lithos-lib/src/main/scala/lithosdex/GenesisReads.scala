package lithosdex

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

  /** Fee numerators a pool may start with: dexFee in (0, denom], each side fee in [0, denom). */
  def feesBounded(fees: Seq[Long]): Boolean = {
    val d = LDHelpers.FEE_DENOM
    fees.size == 3 && fees.head > 0 && fees.head <= d && fees.tail.forall(f => f >= 0 && f < d)
  }
}
