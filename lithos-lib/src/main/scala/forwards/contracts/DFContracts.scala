package forwards.contracts

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{ConstantsBuilder, ErgoValue, NetworkType}
import org.ergoplatform.sdk.{ContractTemplate, ErgoId, Parameter}
import sigma.ast.{ByteArrayConstant, Constant, ErgoTree, SType}
import sigma.data.SigmaBoolean
import sigma.VersionContext
import work.lithos.mutations.{Contract, Mutator}

/** One of the three difficulty forward contracts, and the ids or hashes it takes. */
sealed abstract class DFContractKind(val scriptName: String, val termNames: Seq[String])

object DFContractKind {
  import DifficultyForwardContracts._

  /** The singleton holding the last 128 epochs' difficulty and the recording balance. */
  case object Accumulator extends DFContractKind("DF_Accumulator", Seq(LIT_ID))
  /** A filled forward, settled against the accumulator. */
  case object Position extends DFContractKind("DF_Position", Seq(ACC_NFT, LIT_ID))
  /** One side of a forward waiting for a taker. Filling it creates a position. */
  case object Offer extends DFContractKind("DF_Offer", Seq(LIT_ID, POSITION_HASH))

  val all: Seq[DFContractKind] = Seq(Accumulator, Position, Offer)
}

/**
 * Terms a forward carries from offer to position.
 *
 * @param strike       difficulty the window's mean is measured against
 * @param notional     LIT base units; each side's margin is a fifth of it
 * @param windowStart  first epoch of the window
 * @param windowLength epochs in the window
 */
final case class DFTerms(strike: Long, notional: Long, windowStart: Int, windowLength: Int)

/**
 * The three difficulty forward contracts, wired to each other.
 *
 * A deployment differs only in the accumulator NFT and the LIT id, and the position hash derived from them.
 * Each contract is built by putting those values into a pinned template, so nothing here compiles at runtime.
 *
 * {{{
 *   DF_Accumulator (litId)
 *   DF_Position    (accNFT, litId)
 *     -> DF_Offer  (litId, position hashedPropBytes)
 * }}}
 */
object DifficultyForwardContracts {

  final val LIT_ID        = "CONST_LIT_ID"
  final val ACC_NFT       = "CONST_ACC_NFT"
  final val POSITION_HASH = "CONST_POSITION_HASH"

  private val descriptions: Map[String, String] = Map(
    LIT_ID        -> "Id of the LIT token, which margins, payouts and rewards are paid in.",
    ACC_NFT       -> "Singleton NFT of the difficulty accumulator.",
    POSITION_HASH -> "Blake2b256 of the position contract's prop bytes, which a fill must create.")

  // 32 bytes each, like the ids and hashes they stand in for, and distinct from each other and from every
  // literal the scripts declare, so each marks exactly one constant
  private val sentinelBytes: Map[String, Byte] = Map(LIT_ID -> 0x7a, ACC_NFT -> 0x7b, POSITION_HASH -> 0x7c)

  private[contracts] def sentinel(name: String): Constant[SType] = bytes(Array.fill(32)(sentinelBytes(name)))

  private def bytes(value: Array[Byte]): Constant[SType] = ByteArrayConstant(value).asInstanceOf[Constant[SType]]

  /**
   * Each script compiled with sentinel terms, pinned rather than compiled at runtime. Every deployment's
   * boxes carry these templates. `DFContractTemplateSpec` compiles the sources and fails on any difference.
   */
  private val pinnedTrees: Map[DFContractKind, String] = Map(
    DFContractKind.Accumulator ->
      ("1be30214040004020400040204800204000400040404020e207a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a" +
      "7a7a7a7a7a7a7a7a7a0101048002048002040205000402040205000580a8d6b9070500d80cd601b2a5730000d602db63" +
      "087201d603db6308a7d604b17202d6059172047301d606e4c672010404d607e4c6a70404d6089472067207d609b2db65" +
      "02fe730200d60a9d99db6809720973037304d60be4c6a70511d60ce4c672010511d1eded9683050193c27201c2a793c1" +
      "7201c1a793b27202730500b272037306009072047307957205938cb27202730800017309730a9572089683030191720a" +
      "7207937206720a93720caddb0c0e720bd9010d04d801d60f99720a9e99720a720d730b9593720f720a7ddc6a07dd01db" +
      "68087209059591720f7207b2720b9e7207730c00b2720b720d0093720c720b929572058cb27202730d0002730e999591" +
      "b17203730f8cb2720373100002731195720873127313"),

    DFContractKind.Position ->
      ("1bc5041e04000e207b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b0400050a04800206" +
      "010004000480020e207a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a04020400040004" +
      "020402040205000402040205000580d0acf30e0580a8d6b907050005000101040204000500010104040400d810d601b2" +
      "a4730000d602db63087201d6037301d604b2a5730200d605db63087204d606e4c672040404d607e4c6a70858d6088c72" +
      "0701d6098c720702d60ae4c6a70705d60b9d720a7303d60ce4c672040511d60d9592997206720873048602720b720bd8" +
      "06d60d7305d60e9c7e7209067ee4c6a7060506d60f9d9c7e720a0699b0b4db0c0e720c73067209720dd9010f42049a8c" +
      "720f017eb2720c9e9a72088c720f0273070006720e720ed6107e720b06d61199720d7210d6127d9591720f7210721095" +
      "8f720f72117211720f0586029a720b721299720b7212d60e8c720d01d60f7308d6108c720d02d19683070193db6508fe" +
      "7309938cb27202730a00017203938cb27205730b00017203927206999a72087209730c929591b17205730d8cb2720573" +
      "0e0002730f999a9591b1720273108cb27202731100027312731395947206e4c672010404731473159593720e73167317" +
      "d802d611b2a5731800d612b2db630872117319009683030193c27211d0e4c6a70408938c721201720f928c721202720e" +
      "95937210731a731bd802d611b2a5731c00d612b2db63087211731d009683030193c27211d0e4c6a70508938c72120172" +
      "0f928c7212027210"),

    DFContractKind.Offer ->
      ("1bf0021304000e207c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c7c0400040204800205" +
      "0a04000400040404f001040204f0010500050004000e207a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a" +
      "7a7a7a7a7a7a7a05040580bcc1960b0580a4e803d806d601b2a5730000d602e4c6a70858d603e4c6a70605d604e4c6a7" +
      "0705d605e4c6a70501d606e4c6a704089593cbc272017301d807d6078c720201d6089d99db6809b2db6502fe73020073" +
      "037304d6098c720202d60a9d72047305d60be4c672010408d60ce4c672010508d60db2db63087201730600d196831001" +
      "93c5a7c5b2a473070090a3e4c6a709049272079a720873089072079a72087309927209730a907209730b917203730c91" +
      "720a730d93d0957205720b720cd0720691b1d0957205720c720b730e93e4c672010605720393e4c672010705720493e4" +
      "c6720108587202938c720d01730f938c720d029c73109a720a731192c1720173127206"))

  private lazy val templates: Map[DFContractKind, ContractTemplate] =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      DFContractKind.all.map(kind => kind -> fromTree(kind, ErgoTree.fromHex(pinnedTrees(kind)))).toMap
    }

  /** The template for one contract, read from its pinned tree. */
  def template(kind: DFContractKind): ContractTemplate = templates(kind)

  /** The pinned tree, sentinels included, as hex. */
  def pinnedTreeHex(kind: DFContractKind): String = pinnedTrees(kind)

  /** Index in the contract's constants of the term with this name. */
  def indexOf(kind: DFContractKind, name: String): Int =
    template(kind).parameters.find(_.name == name).map(_.constantIndex)
      .getOrElse(throw new NoSuchElementException(s"${kind.scriptName} has no term $name"))

  /**
   * Compiles a contract's `.ergo` source with sentinel terms, which is what the pinned tree must equal.
   * For the drift spec and for re-pinning only: compiling a script twice in one JVM can throw.
   */
  def compileSource(kind: DFContractKind): ErgoTree = {
    val builder = ConstantsBuilder.create()
    kind.termNames.foreach(name => builder.item(name, sentinel(name).value.asInstanceOf[AnyRef]))
    // The scripts hold no address literal, so the network prefix never reaches the tree
    Contract.fromErgoScript(NetworkType.MAINNET, builder.build(),
      ScriptGenerator.mkForwardScript(kind.scriptName), Seq.empty[Mutator]).ergoTree
  }

  private def fromTree(kind: DFContractKind, tree: ErgoTree): ContractTemplate = {
    val constants = tree.constants
    def describe = constants.zipWithIndex.map { case (c, i) => s"$i: ${c.tpe} = ${c.value}" }.mkString("; ")

    // A term that appears twice would need two values that nothing forces to agree. Counting the sentinel's
    // bytes across the whole tree also catches one folded into a larger constant, such as a tuple, where
    // substituting the term would leave the sentinel behind.
    val indices = kind.termNames.map { name =>
      val at = constants.indices.filter(i => constants(i) == sentinel(name))
      val raw = Array.fill(32)(sentinelBytes(name))
      val occurrences = tree.bytes.sliding(32).count(_.sameElements(raw))
      require(at.size == 1 && occurrences == 1,
        s"${kind.scriptName}: $name must compile to exactly one constant, found ${at.size} " +
          s"and $occurrences copies of its bytes. Constants: $describe")
      name -> at.head
    }.toMap
    val claimed = indices.values.toSet

    ContractTemplate(
      treeVersion = Some(tree.version),
      name = kind.scriptName,
      description = s"Difficulty forward ${kind.scriptName}",
      constTypes = constants.map(_.tpe),
      constValues = Some(constants.indices.map(i => if (claimed(i)) None else Some(constants(i).value))),
      parameters = kind.termNames.map(n => Parameter(n, descriptions(n), indices(n))).toIndexedSeq,
      expressionTree = tree.toProposition(false))
  }

  /** `kind` with every term supplied. Refuses a missing term, and any value that is not 32 bytes. */
  private def build(kind: DFContractKind, values: Map[String, Array[Byte]]): Contract = {
    require(values.keySet == kind.termNames.toSet,
      s"${kind.scriptName} takes ${kind.termNames.mkString(", ")}, got ${values.keySet.mkString(", ")}")
    require(values.values.forall(_.length == 32), s"${kind.scriptName} takes 32-byte ids and hashes only")
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      Contract(template(kind).applyTemplate(None, values.map { case (name, value) => name -> bytes(value) }))
    }
  }

  /** DF_Accumulator — the difficulty ring and recording balance. */
  def mkAccumulator(litId: ErgoId): Contract =
    build(DFContractKind.Accumulator, Map(LIT_ID -> litId.getBytes))

  /** DF_Position — a filled forward. */
  def mkPosition(accNFT: ErgoId, litId: ErgoId): Contract =
    build(DFContractKind.Position, Map(ACC_NFT -> accNFT.getBytes, LIT_ID -> litId.getBytes))

  /**
   * DF_Offer — one side waiting for a taker.
   *
   * @param positionHash `hashedPropBytes` of the position contract, which is the script a fill must create
   */
  def mkOffer(litId: ErgoId, positionHash: Array[Byte]): Contract =
    build(DFContractKind.Offer, Map(LIT_ID -> litId.getBytes, POSITION_HASH -> positionHash))

  // ─── registers ────────────────────────────────────────────────────────────

  /** R4 lastEpoch, R5 the 128-entry difficulty ring. */
  def accumulatorRegisters(lastEpoch: Int, ring: Seq[Long]): Seq[ErgoValue[_]] =
    Seq(ErgoValue.of(lastEpoch), ErgoValue.of(ring.toArray))

  /** R4 makerPk, R5 makerLong, R6 strike, R7 notional, R8 window, R9 expiry height. */
  def offerRegisters(maker: SigmaBoolean, makerLong: Boolean, terms: DFTerms, expiry: Int): Seq[ErgoValue[_]] =
    Seq(ErgoValue.of(maker), ErgoValue.of(makerLong), ErgoValue.of(terms.strike), ErgoValue.of(terms.notional),
      windowValue(terms), ErgoValue.of(expiry))

  /** R4 longPk, R5 shortPk, R6 strike, R7 notional, R8 window. */
  def positionRegisters(long: SigmaBoolean, short: SigmaBoolean, terms: DFTerms): Seq[ErgoValue[_]] =
    Seq(ErgoValue.of(long), ErgoValue.of(short), ErgoValue.of(terms.strike), ErgoValue.of(terms.notional),
      windowValue(terms))

  /** The (first epoch, length) pair both the offer and the position keep in R8. */
  def windowValue(terms: DFTerms): ErgoValue[_] =
    ErgoValue.pairOf(ErgoValue.of(terms.windowStart), ErgoValue.of(terms.windowLength))
}

/**
 * Every difficulty forward contract for one deployment, with the position hash already threaded into the offer.
 *
 * @param accNFT singleton NFT of the accumulator
 * @param litId  the LIT token
 */
case class DFContracts(accNFT: ErgoId, litId: ErgoId) {

  /** DF_Accumulator. */
  val accumulator: Contract = DifficultyForwardContracts.mkAccumulator(litId)

  /** DF_Position. */
  val position: Contract = DifficultyForwardContracts.mkPosition(accNFT, litId)

  /** DF_Offer, which only creates positions under [[position]]. */
  val offer: Contract = DifficultyForwardContracts.mkOffer(litId, position.hashedPropBytes)
}
