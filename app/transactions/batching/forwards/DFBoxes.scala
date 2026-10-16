package transactions.batching.forwards

import forwards.contracts.{DFContracts, DFTerms}
import forwards.{DFHelpers, DFMath}
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue}
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO}

import scala.util.Try

/**
 * The difficulty accumulator, read from its box.
 *
 * @param lastEpoch R4, the newest epoch recorded
 * @param ring      R5, 128 difficulties, epoch e at index e % 128
 * @param lit       the LIT balance recordings are paid from, 0 when the box holds no second token
 */
final case class DFAccumulator(box: NodeBox, lastEpoch: Int, ring: IndexedSeq[Long], lit: Long) {
  def boxId: String = box.boxId
}

/**
 * A difficulty forward position, read from its box.
 *
 * @param long  the script the long side is paid to: the prop bytes of R4's key
 * @param short the same for R5
 * @param terms R6 to R8
 * @param lit   LIT the box holds, both margins and both sides' rewards
 */
final case class DFPosition(box: NodeBox, long: Contract, short: Contract, terms: DFTerms, lit: Long) {
  def boxId: String = box.boxId

  /** The window's last epoch, which the accumulator must have recorded before the position settles. */
  def windowEnd: Int = terms.windowStart + terms.windowLength - 1
}

/**
 * Reads accumulator and position boxes the node reports. Anyone can create a box at either address with any
 * registers, so each read checks the script against this deployment's, every register's type, and every
 * amount a settlement depends on, and returns None for anything a spend would fail on.
 */
object DFBoxes {

  private val IntType = ErgoType.integerType()
  private val LongType = ErgoType.longType()
  private val RingType = ErgoType.collType(ErgoType.longType())
  private val WindowType = ErgoType.pairType(ErgoType.integerType(), ErgoType.integerType())
  private val KeyType = ErgoType.sigmaPropType()

  /** `box` as the accumulator of `contracts`, or None. */
  def accumulator(box: NodeBox, contracts: DFContracts): Option[DFAccumulator] =
    if (!box.ergoTree.equalsIgnoreCase(contracts.accumulator.ergoTreeHex)) None
    else Try {
      val assets = box.assets
      val litHeld =
        if (assets.size == 1) Some(0L)
        else if (assets.size == 2 && assets(1).tokenId == contracts.litId.toString && assets(1).amount > 0)
          Some(assets(1).amount)
        else None
      for {
        _ <- assets.headOption.filter(nft => nft.tokenId == contracts.accNFT.toString && nft.amount == 1L)
        lit <- litHeld
        lastEpoch <- register(box, 4, IntType).collect { case epoch: Integer if epoch >= 0 => epoch.intValue }
        ring <- register(box, 5, RingType).collect { case coll: sigma.Coll[Long @unchecked] => coll.toArray.toVector }
        if ring.size == DFHelpers.RING_SIZE
      } yield DFAccumulator(box, lastEpoch, ring, lit)
    }.toOption.flatten

  /**
   * `box` as a position of `contracts`, or None. Besides its types, a position is refused when its window
   * or amounts are outside what a fill creates, it holds a token other than LIT, it holds too little ERG to
   * fund its own payouts, or too little LIT to pay both margins and the accumulator's deposit.
   */
  def position(box: NodeBox, contracts: DFContracts): Option[DFPosition] =
    if (!box.ergoTree.equalsIgnoreCase(contracts.position.ergoTreeHex)) None
    else Try {
      for {
        lit <- box.assets match {
          case Seq(only) if only.tokenId == contracts.litId.toString && only.amount > 0 => Some(only.amount)
          case _ => None
        }
        if box.value >= DFHelpers.POSITION_ERG
        long <- register(box, 4, KeyType).flatMap(payoutContract)
        short <- register(box, 5, KeyType).flatMap(payoutContract)
        strike <- register(box, 6, LongType).collect { case v: java.lang.Long => v.longValue }
        notional <- register(box, 7, LongType).collect { case v: java.lang.Long => v.longValue }
        window <- register(box, 8, WindowType).collect { case (s: Integer, l: Integer) => (s.intValue, l.intValue) }
        (start, length) = window
        if strike > 0 && DFMath.margin(notional) > 0
        if length >= 1 && length <= DFHelpers.MAX_WINDOW && start >= 0 && start <= Int.MaxValue - length
        if BigInt(lit) >= BigInt(DFMath.margin(notional)) * 2 + DFHelpers.ACC_DEPOSIT
      } yield DFPosition(box, long, short, DFTerms(strike, notional, start, length), lit)
    }.toOption.flatten

  /** `box` rebuilt for spending, or None when its id does not come back the same, so its fields are not the box's. */
  def input(box: NodeBox, ctx: BlockchainContext): Option[InputUTXO] =
    Try(box.toInputUTXO(ctx)).toOption.filter(_.id.toString == box.boxId)

  /** The value of register `index`, when it decodes as `tpe`. */
  private def register(box: NodeBox, index: Int, tpe: ErgoType[_]): Option[Any] =
    box.additionalRegisters.get(index)
      .flatMap[ErgoValue[_]](hex => Try(ErgoValue.fromHex(hex)).toOption)
      .filter(_.getType == tpe)
      .map(_.getValue)

  /**
   * The script a position compares a payout box against, which is the key's prop bytes. Refused when those
   * bytes do not survive a parse back into a tree unchanged, since no output could then match them.
   */
  private def payoutContract(key: Any): Option[Contract] = key match {
    case prop: sigma.SigmaProp =>
      val bytes = prop.propBytes.toArray
      Try(Contract(ErgoTree.fromBytes(bytes))).toOption.filter(_.propBytes.sameElements(bytes))
    case _ => None
  }
}
