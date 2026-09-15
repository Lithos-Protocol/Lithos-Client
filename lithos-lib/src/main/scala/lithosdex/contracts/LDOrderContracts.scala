package lithosdex.contracts

import lfsm.ScriptGenerator
import lithosdex.LDHelpers
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.{ContractTemplate, ErgoId, Parameter}
import scorex.crypto.hash.Blake2b256
import sigma.ast.{BigIntConstant, ByteArrayConstant, Constant, ErgoTree, SType, SigmaPropConstant}
import sigma.crypto.CryptoConstants
import sigma.data.{CBigInt, ProveDlog, SigmaBoolean}
import sigma.{Colls, VersionContext}
import work.lithos.mutations.{Contract, Mutator}

import java.math.BigInteger

/**
 * What a LithosDex order asks the pool to do.
 *
 * @param inputIndex where the order sits in an executing transaction; the contract refuses anywhere else
 * @param poolOp     the pool operation an execution must run, which the contract reads from input 0
 */
sealed abstract class LDOrderKind(val scriptName: String, val inputIndex: Int, val poolOp: Byte)

object LDOrderKind {
  /** ERG in, the pool's token out. */
  case object SwapSell extends LDOrderKind("LD_SwapSellOrder", 1, LDHelpers.POOL_SWAP)
  /** The pool's token in, ERG out. */
  case object SwapBuy extends LDOrderKind("LD_SwapBuyOrder", 1, LDHelpers.POOL_SWAP)
  /** Both sides in, a provision and its ownership NFT out. */
  case object Deposit extends LDOrderKind("LD_DepositOrder", 1, LDHelpers.POOL_DEPOSIT)
  /** An ownership NFT in, the provision's share of both reserves out. The provision is input 1. */
  case object Redeem extends LDOrderKind("LD_RedeemOrder", 2, LDHelpers.POOL_REDEEM)

  val all: Seq[LDOrderKind] = Seq(SwapSell, SwapBuy, Deposit, Redeem)
}

/**
 * Terms every order carries.
 *
 * @param redeemer       receives the fill and is the only key that can refund
 * @param poolNFT        the pool the order executes against
 * @param executorFee    nanoERG the executor keeps, miner fee included
 * @param maxMinerFee    most nanoERG an execution may pay to the miner fee script
 */
final case class LDOrderTerms(redeemer: SigmaBoolean,
                              poolNFT: ErgoId,
                              executorFee: Long,
                              maxMinerFee: Long)

/**
 * One order kind's EIP-5 contract template, read from a tree compiled with a sentinel standing in for
 * every per-order term. Each term is exactly one constant, so each is one template parameter.
 *
 * Never compile an order with its real values instead: the compiler rewrites `x - 0` to `x` and `x * 1`
 * to `x` and merges equal constants, so the template would depend on the values and no scan would find it.
 */
final case class LDOrderTemplate(kind: LDOrderKind, template: ContractTemplate) {

  /** Template bytes as a node reports them: the expression with its constants left as placeholders. */
  lazy val templateBytes: Array[Byte] = template.applyTemplate(None, sentinelValues).template

  /** Node indexer hash: Blake2b256 over the template bytes. The public explorer uses SHA-256. */
  lazy val templateHash: String = Hex.toHexString(Blake2b256.hash(templateBytes))

  /** Index in the tree's constants of the term with this name. */
  def indexOf(name: String): Int =
    template.parameters.find(_.name == name).map(_.constantIndex)
      .getOrElse(throw new NoSuchElementException(s"${kind.scriptName} has no term $name"))

  def termNames: Seq[String] = template.parameters.map(_.name)

  /** An order: this template with every term supplied. Refuses a missing term or a value of the wrong type. */
  def withValues(values: Map[String, Constant[SType]]): Contract = {
    require(values.keySet == termNames.toSet,
      s"${kind.scriptName} takes ${termNames.mkString(", ")}, got ${values.keySet.mkString(", ")}")
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      Contract(template.applyTemplate(None, values))
    }
  }

  private def sentinelValues: Map[String, Constant[SType]] =
    termNames.map(n => n -> LDOrderContracts.sentinel(n)).toMap
}

/**
 * The four LithosDex order contracts.
 *
 * Templates come from pinned trees, so nothing here compiles at runtime. Build orders through
 * `swapSell`, `swapBuy`, `deposit` and `redeem`, which apply those templates.
 */
object LDOrderContracts {

  final val REDEEMER         = "CONST_REDEEMER"
  final val POOL_NFT         = "CONST_POOL_NFT"
  final val EXECUTOR_FEE     = "CONST_EXECUTOR_FEE"
  final val MAX_MINER_FEE    = "CONST_MAX_MINER_FEE"
  final val BASE_AMOUNT      = "CONST_BASE_AMOUNT"
  final val MIN_QUOTE        = "CONST_MIN_QUOTE"
  final val DEPOSIT_X        = "CONST_DEPOSIT_X"
  final val MIN_SHARES       = "CONST_MIN_SHARES"

  /** The same for every order, so it is part of the pinned tree rather than supplied. */
  final val MINER_FEE_PROP = "CONST_MINER_FEE_PROP"

  private val descriptions: Map[String, String] = Map(
    REDEEMER         -> "Owner. Receives the fill and is the only key that can refund.",
    POOL_NFT         -> "Singleton NFT of the pool the order executes against.",
    EXECUTOR_FEE     -> "nanoERG the executor keeps, miner fee included.",
    MAX_MINER_FEE    -> "Most nanoERG an execution may pay to the miner fee script.",
    BASE_AMOUNT      -> "nanoERG sold into the pool.",
    MIN_QUOTE        -> "Least the owner receives: tokens for a sell, nanoERG after the executor fee for a buy.",
    DEPOSIT_X        -> "Most nanoERG offered to the pool's reserves, not counting the provision box.",
    MIN_SHARES       -> "Fewest shares the owner accepts for their funds, against the pool the order executes on.")

  // Sentinels are numbered by position here, so a new term goes last and no pinned tree moves
  private val amountNames = Seq(EXECUTOR_FEE, MAX_MINER_FEE, BASE_AMOUNT, MIN_QUOTE, DEPOSIT_X, MIN_SHARES)

  private def namesFor(kind: LDOrderKind): Seq[String] = {
    val common = Seq(REDEEMER, POOL_NFT, EXECUTOR_FEE, MAX_MINER_FEE)
    kind match {
      case LDOrderKind.SwapSell => common ++ Seq(BASE_AMOUNT, MIN_QUOTE)
      case LDOrderKind.SwapBuy  => common :+ MIN_QUOTE
      case LDOrderKind.Deposit  => common ++ Seq(DEPOSIT_X, MIN_SHARES)
      case LDOrderKind.Redeem   => common
    }
  }

  // Sentinels differ from each other and from every literal the scripts declare, and are never 0 or 1,
  // which the compiler would rewrite away. The amounts sit a trillion apart so that any arithmetic the
  // compiler folds out of them is large enough for `fromTree` to notice.
  private[contracts] def sentinel(name: String): Constant[SType] = (name match {
    case REDEEMER => SigmaPropConstant(ProveDlog(CryptoConstants.dlogGroup.generator))
    case POOL_NFT => ByteArrayConstant(Array.fill(32)(0x5d.toByte))
    case _        => amount(0x4c444f5200000000L + (amountNames.indexOf(name) + 1) * 0x0101010101L)
  }).asInstanceOf[Constant[SType]]

  /** Literals the scripts declare themselves are small or Long.MaxValue; anything else came from a sentinel. */
  private val largestLiteral: BigInt = BigInt(1000000)

  /** An amount term. Every amount in the order scripts is a BigInt. */
  def amount(v: Long): Constant[SType] = BigIntConstant(BigInteger.valueOf(v)).asInstanceOf[Constant[SType]]

  /**
   * Each order script compiled with sentinel terms, pinned rather than compiled at runtime. Every order
   * ever placed carries this template, so a compiler that emitted anything different would leave all of
   * them invisible to a scan. `LDOrderTemplateSpec` compiles the sources and fails on any difference.
   */
  private val pinnedTrees: Map[LDOrderKind, String] = Map(
    LDOrderKind.SwapSell ->
      ("1ba6041a040008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179806084c444f5503" +
      "0303030402040004000e205d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d0100040204" +
      "00040204a09c01040204000300020002ff020006084c444f560404040406084c444f5301010101040206010104000500" +
      "0e691005040004000e36100204a00b08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8" +
      "1798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303" +
      "830108cdeeac93b1a5730406084c444f5402020202d803d601b2a4730000d6027301d60373029595ed93db6508fe7303" +
      "91b1db630872017304938cb2db630872017305000173067307d809d604b2a5730800d605b2db63087204730900d606b2" +
      "db63087201730a00d6077e8c72050206d608e4c672010611d609e4c672010511d60a7e730b06d60b9972039d9c72037e" +
      "b27209730c0006720ad60c7eb27209730d0006d19683070193e5dc650cfe02730e730f027310731193c27204d0720293" +
      "8c7205018c7206019272077312927ec172040699997ec1a70672037313909c9c7e998c720602b2720873140006720b72" +
      "0c9c9a720773159a9c7e99c17201b2720873160006720a9c720b720c907eb0a57317d9010d4163d802d60f8c720d02d6" +
      "108c720d019593c2720f73189a7210c1720f72100673197202"),

    LDOrderKind.SwapBuy ->
      ("1b940419040008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980402040004000e" +
      "205d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d010004000402040204a09c01040404" +
      "000300020002ff020006084c444f5604040404040006084c444f5301010101060101040205000e691005040004000e36" +
      "100204a00b08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7" +
      "a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a573" +
      "0406084c444f5402020202d802d601b2a4730000d60273019595ed93db6508fe730291b1db630872017303938cb2db63" +
      "0872017304000173057306d80ad603b2db6308a7730700d604b2db63087201730800d605b2a5730900d6067e99c17205" +
      "c1a706d607e4c672010611d6087e8c72030206d609e4c672010511d60a7e730a06d60b9972089d9c72087eb27209730b" +
      "0006720ad60c7eb27209730c0006d19683060193e5dc650cfe02730d730e02730f7310938c7203018c72040193c27205" +
      "d072029272067311909c9c7e99c17201b2720773120006720b720c9c9a9a7206731373149a9c7e998c720402b2720773" +
      "150006720a9c720b720c907eb0a57316d9010d4163d802d60f8c720d02d6108c720d019593c2720f73179a7210c1720f" +
      "72100673187202"),

    LDOrderKind.Deposit ->
      ("1b8a051d040006084c444f570505050508cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16" +
      "f817980402040004000e205d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d0100040004" +
      "0205feffffffffffffffff010400040204020404040205000300020002ff020106084c444f5806060606040006084c44" +
      "4f530101010106010006010005000e691005040004000e36100204a00b08cd0279be667ef9dcbbac55a06295ce870b07" +
      "029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300" +
      "000193c2b2a57301007473027303830108cdeeac93b1a5730406084c444f5402020202d803d601b2a4730000d6027301" +
      "d60373029595ed93db6508fe730391b1db630872017304938cb2db630872017305000173067307d80ed604b2db6308a7" +
      "730800d605b2db63087201730900d6068c720501d6077e99730ae4c67201040506d608e4c672010611d6097e99c17201" +
      "b27208730b0006d60a9d9c720272077209d60b7e998c720502b27208730c0006d60c9d9c7e8c720402067207720bd60d" +
      "a1720a720cd60eb2a5730d00d60fb2a5730e00d610db6308720fd611b27210730f01860272067310d196830a0193e5dc" +
      "650cfe02731173120273137314938c720401720692720d7315927ee4c6720e070506720d93c2720fd07203938cb27210" +
      "73160001c57201927e9ac1720fc1720e069a99997ec1a706720273179591720a720c9d9c99720a720c72097207731893" +
      "8c7211017206927e8c721102069591720c720a9d9c99720c720a720b72077319907eb0a5731ad901124163d802d6148c" +
      "721202d6158c7212019593c27214731b9a7215c17214721506731c7203"),

    LDOrderKind.Redeem ->
      ("1b940418040008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980404040004000e" +
      "205d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d01000402040205feffffffffffffff" +
      "ff010402040005000300020002ff02020400040006084c444f5301010101040205000e691005040004000e36100204a0" +
      "0b08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a7017300" +
      "73011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a5730406084c" +
      "444f5402020202d802d601b2a4730000d60273019595ed93db6508fe730291b1db630872017303938cb2db6308720173" +
      "04000173057306d808d603b2a4730700d604b2a5730800d6057ee4c67203070506d606e4c672010611d6077e997309e4" +
      "c67201040506d608b2db63087201730a00d6098c720801d60ab2db63087204730b0186027209730cd19683070193e5dc" +
      "650cfe02730d730e02730f731093e4c67203060e8cb2db6308a77311000193c27204d07202927ec1720406999a7e9ac1" +
      "a7c17203069d9c72057e99c17201b272067312000672077313938c720a017209927e8c720a02069d9c72057e998c7208" +
      "02b27206731400067207907eb0a57315d9010b4163d802d60d8c720b02d60e8c720b019593c2720d73169a720ec1720d" +
      "720e0673177202"))

  private lazy val templates: Map[LDOrderKind, LDOrderTemplate] =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      LDOrderKind.all.map(kind => kind -> fromTree(kind, ErgoTree.fromHex(pinnedTrees(kind)))).toMap
    }

  /** The template for one order kind, read from its pinned tree. */
  def template(kind: LDOrderKind): LDOrderTemplate = templates(kind)

  /** The pinned tree, sentinels included, as hex. */
  def pinnedTreeHex(kind: LDOrderKind): String = pinnedTrees(kind)

  /**
   * Compiles an order's `.ergo` source with sentinel terms, which is what the pinned tree must equal.
   * For the drift spec and for re-pinning only: compiling a script twice in one JVM can throw.
   */
  def compileSource(kind: LDOrderKind): ErgoTree = {
    val builder = ConstantsBuilder.create()
    namesFor(kind).foreach(name => builder.item(name, sentinel(name).value.asInstanceOf[AnyRef]))
    builder.item(MINER_FEE_PROP, Colls.fromArray(Contract.FEE.propBytes))
    // The scripts hold no address literal, so the network prefix never reaches the tree
    Contract.fromErgoScript(NetworkType.MAINNET, builder.build(),
      ScriptGenerator.mkLithosDexScript(kind.scriptName), Seq.empty[Mutator]).ergoTree
  }

  private def fromTree(kind: LDOrderKind, tree: ErgoTree): LDOrderTemplate = {
    val names = namesFor(kind)
    val constants = tree.constants
    def describe = constants.zipWithIndex.map { case (c, i) => s"$i: ${c.tpe} = ${c.value}" }.mkString("; ")

    // A term that appears twice would need two values that nothing forces to agree, and a large number
    // nobody claims is arithmetic folded out of a sentinel, which no parameter can reach. Either way the
    // template is refused rather than handed out.
    val indices = names.map { name =>
      val at = constants.indices.filter(i => constants(i) == sentinel(name))
      require(at.size == 1,
        s"${kind.scriptName}: $name must compile to exactly one constant, found ${at.size}. Constants: $describe")
      name -> at.head
    }.toMap
    val claimed = indices.values.toSet
    constants.indices.filterNot(claimed).foreach { i =>
      val numeric = constants(i).value match {
        case v: Long    => Some(BigInt(v))
        case v: Int     => Some(BigInt(v))
        case v: CBigInt => Some(BigInt(v.wrappedValue))
        case _          => None
      }
      require(numeric.forall(v => v.abs <= largestLiteral || v == BigInt(Long.MaxValue)),
        s"${kind.scriptName}: constant $i looks derived from a sentinel. Constants: $describe")
    }

    val template = ContractTemplate(
      treeVersion = Some(tree.version),
      name = kind.scriptName,
      description = s"LithosDex ${kind.scriptName}, executed with pool operation ${kind.poolOp} at input ${kind.inputIndex}",
      constTypes = constants.map(_.tpe),
      constValues = Some(constants.indices.map(i => if (claimed(i)) None else Some(constants(i).value))),
      parameters = names.map(n => Parameter(n, descriptions(n), indices(n))).toIndexedSeq,
      expressionTree = tree.toProposition(false))
    LDOrderTemplate(kind, template)
  }

  private def common(terms: LDOrderTerms): Map[String, Constant[SType]] = Map(
    REDEEMER         -> SigmaPropConstant(terms.redeemer).asInstanceOf[Constant[SType]],
    POOL_NFT         -> ByteArrayConstant(terms.poolNFT.getBytes).asInstanceOf[Constant[SType]],
    EXECUTOR_FEE     -> amount(terms.executorFee),
    MAX_MINER_FEE    -> amount(terms.maxMinerFee)
  )

  /** Sell `baseAmount` nanoERG for at least `minQuote` tokens. */
  def swapSell(terms: LDOrderTerms, baseAmount: Long, minQuote: Long): Contract =
    template(LDOrderKind.SwapSell)
      .withValues(common(terms) ++ Map(BASE_AMOUNT -> amount(baseAmount), MIN_QUOTE -> amount(minQuote)))

  /** Sell every token the order box holds for at least `minQuote` nanoERG after the executor fee. */
  def swapBuy(terms: LDOrderTerms, minQuote: Long): Contract =
    template(LDOrderKind.SwapBuy).withValues(common(terms) + (MIN_QUOTE -> amount(minQuote)))

  /**
   * Deposit up to `depositX` nanoERG and up to every token the order box holds, for at least
   * `minShares`. The box must also fund the provision box, which the pool requires to hold at least
   * [[LDHelpers.PROVISION_MIN]].
   */
  def deposit(terms: LDOrderTerms, depositX: Long, minShares: Long): Contract =
    template(LDOrderKind.Deposit)
      .withValues(common(terms) ++ Map(DEPOSIT_X -> amount(depositX), MIN_SHARES -> amount(minShares)))

  /** Close the provision owned by the NFT the order box holds at token 0. */
  def redeem(terms: LDOrderTerms): Contract =
    template(LDOrderKind.Redeem).withValues(common(terms))
}
