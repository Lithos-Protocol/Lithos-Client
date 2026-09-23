package lithosdex.contracts

import lfsm.ScriptGenerator
import lithosdex.LDHelpers
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ContextVar, ErgoValue, NetworkType}
import org.ergoplatform.sdk.{ContractTemplate, ErgoId, Parameter}
import sigma.ast.{ByteArrayConstant, Constant, ErgoTree, SType}
import sigma.{Colls, VersionContext}
import work.lithos.mutations.{Contract, Mutator}

/** One of the four contracts every LithosDex deployment is made of, and the ids or hashes it takes. */
sealed abstract class LDContractKind(val scriptName: String, val termNames: Seq[String])

object LDContractKind {
  import LithosDexContracts._

  /** The provision logic. Never a box script: the guard runs it from a context var. */
  case object Provision extends LDContractKind("LD_Provision", Seq(POOL_NFT, VAULT_NFT))
  /** The script every provision box carries. */
  case object ProvisionGuard extends LDContractKind("LD_Provision_Guard", Seq(PROVISION_LOGIC_HASH))
  case object FeeVault extends LDContractKind("LD_FeeVault", Seq(POOL_NFT, PROV_TOKEN))
  case object LiquidityPool extends LDContractKind("LD_LiquidityPool", Seq(VAULT_NFT, PROVISION_GUARD_HASH))

  val all: Seq[LDContractKind] = Seq(Provision, ProvisionGuard, FeeVault, LiquidityPool)
}

/**
 * The four LithosDex contracts, wired to each other.
 *
 * Every deployment differs only in three ids — the pool NFT, the vault NFT and the provision token — and
 * the hashes derived from them. Each contract is built by putting those values into a pinned template,
 * so nothing here compiles at runtime and building any deployment's contracts costs no compiler time.
 *
 * {{{
 *   LD_Provision       (poolNFT, vaultNFT)          - logic, never a box script
 *     -> LD_Provision_Guard (hashedValueBytes)      - what a provision box actually carries
 *          -> LD_LiquidityPool (guard hash, vaultNFT)
 *   LD_FeeVault        (poolNFT, provToken)
 * }}}
 */
object LithosDexContracts {

  final val POOL_NFT             = "CONST_POOL_NFT"
  final val VAULT_NFT            = "CONST_VAULT_NFT"
  final val PROV_TOKEN           = "CONST_PROV_TOKEN"
  final val PROVISION_LOGIC_HASH = "CONST_PROVISION_LOGIC_HASH"
  final val PROVISION_GUARD_HASH = "CONST_PROVISION_GUARD_HASH"

  private val descriptions: Map[String, String] = Map(
    POOL_NFT             -> "Singleton NFT of the pool.",
    VAULT_NFT            -> "Singleton NFT of the fee vault.",
    PROV_TOKEN           -> "Provision token, whose whole supply is minted into the pool at genesis.",
    PROVISION_LOGIC_HASH -> "Blake2b256 of the provision logic's value bytes, which the guard executes.",
    PROVISION_GUARD_HASH -> "Blake2b256 of the provision guard's prop bytes, which provision boxes sit at.")

  // 32 bytes each, like the ids and hashes they stand in for, and distinct from each other and from
  // every literal the scripts declare, so each marks exactly one constant
  private val sentinelBytes: Map[String, Byte] = Map(
    POOL_NFT -> 0x5d, VAULT_NFT -> 0x5e, PROV_TOKEN -> 0x5f,
    PROVISION_LOGIC_HASH -> 0x6c, PROVISION_GUARD_HASH -> 0x6d)

  private[contracts] def sentinel(name: String): Constant[SType] = bytes(Array.fill(32)(sentinelBytes(name)))

  private def bytes(value: Array[Byte]): Constant[SType] = ByteArrayConstant(value).asInstanceOf[Constant[SType]]

  /**
   * Each script compiled with sentinel terms, pinned rather than compiled at runtime. Every deployment's
   * boxes carry these templates, so a compiler that emitted anything different would build contracts no
   * live pool sits at. `LDContractTemplateSpec` compiles the sources and fails on any difference.
   */
  private val pinnedTrees: Map[LDContractKind, String] = Map(
    LDContractKind.Provision ->
      ("1bec030f0e205d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d0200040004000e205e5e" +
      "5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e020104000400020204c0944004d00f020304" +
      "0004000100d809d601e4e30002d602e4c6a7060ed603aea4d9010363aedb63087203d901054d0e938c7205017202d604" +
      "b2a5db6508fe00d605db6308a7d606e4c6a70705d607e4c6a70406d608e4c6a70506d6097300959372017301d1eded93" +
      "8cb2db6308b2a473020073030001730472039683080193c27204c2a792c17204c1a793db63087204720593e4c6720406" +
      "0e720293e4c672040705720692e4c672040406720792e4c6720405067208efe6c67204080e959372017305d1eded7203" +
      "938cb2db6308b2a4730600730700017209afa5d9010a63efaedb6308720ad9010c4d0e938c720c017202959372017308" +
      "d1ed8f8cc7a70199a373099683090193c27204c2a792c17204c1a793db63087204720593e4c672040406720793e4c672" +
      "040506720893e4c67204060e720293e4c6720407057206918cc772040199a3730aefe6c67204080e95937201730bd1ed" +
      "ed7203938cb2db6308b2a4730c00730d000172099683050193c27204c2a792c17204c1a793db63087204720593e4c672" +
      "04060e7202efe6c67204080ed1730e"),

    LDContractKind.ProvisionGuard ->
      ("1b31010e206c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6c6cea02d193cbe4e3400e7300" +
      "d40840"),

    LDContractKind.FeeVault ->
      ("1b940520040006043b9aca000400040004000200050004020402020004020402040201010500050004000e205f5f5f5f" +
      "5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f0502040405000400058084af5f02010300020002" +
      "0004000e205d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d020302040100d810d601e4" +
      "e30002d602e5e301047300d603e4c6a70406d604e4c6a70506d6057301d6069c9c720572057205d607b2a4730200d608" +
      "b2a5db6508fe00d60993c27208c2a7d60adb63087208d60bdb6308a7d60c93b2720a730300b2720b730400d60d860283" +
      "010273057306d60eb2720a730701720dd60fb2720b730801720dd6108c720e02959372017309d804d6119a7202730ad6" +
      "12b0dc0c1db4a4730b721101b4a5730c72118602730d8602730e730fd901123c3d593c6363d809d6148c721201d6158c" +
      "721202d6168c721501d617b2db63087216731000d618e4c672160406d619e4c672160506d61a8c721502d61b8c721402" +
      "d61c7ee4c672160705068602ed8c72140196830701938c7217017311938c721702731290721872039072197204ec8f72" +
      "1872038f7219720493e4c6721a0406720393e4c6721a0506720486029a8c721b017d9d9c721c99720372187206059a8c" +
      "721b027d9d9c721c9972047219720605d613c17208d6148c721202d1eded9683050193c5a7c572077209720c90b1720a" +
      "7313ec938c720e018c720f019372107314ed8c72120191720273159683050192721399c1a78c72140192721373169272" +
      "10998c720f028c72140293e4c672080406720393e4c6720805067204959372017317d801d611e5dc650cfe0273187319" +
      "02731ad1ededed938cb2db63087207731b0001731cec937211731d937211731e7209720cd1731f"),

    LDContractKind.LiquidityPool ->
      ("1bbe10650400040204020404040404000402040005feffffffffffffffff0105feffffffffffffffff01050005000604" +
      "3b9aca00040204000e206d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d0e205e5e5e5e" +
      "5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e04000400040004060580dac40905000500040402" +
      "00050005000500050004a09c010402050004a09c0104040500020104020400050005000500050204020580989abc0405" +
      "0005000502050202020402040005020500050005020580897a0501020304020400040202000500040205000500040004" +
      "020500050005000500050005000204040204020400040004040400040402000500040205000580897a05000500050205" +
      "0204020580989abc040502040004020500050005000100d831d601c5a7d602b2a5730000d603db63087202d604db6308" +
      "a7d605b27203730100d606b27204730200d6078c720601d608b27203730300d609b27204730400d60a8c720901d60be4" +
      "c6a70511d60cc17202d60de4c672020611d60eb2720d730500d60f99720c720ed6108c720502d611b2720d730600d612" +
      "9972107211d613e4e30002d614c1a7d615e4c6a70611d616b27215730700d6179972147216d61899720f7217d6199972" +
      "0e7216d61a997308e4c672020405d61b997309e4c6a70405d61c99721a721bd61ddad9011d0eb0a4730ad9011f41639a" +
      "8c721f01b0db63088c721f02730bd90121414d0ed802d6238c722102d6248c72210195938c722301721d9a72248c7223" +
      "02722401720ad61e8c720902d61f93721d721ed6208c720802d621730cd6229c9c722172217221d623e4c6a70706d624" +
      "e4c672020706d625b27215730d00d6269972117225d627e4c6a70806d628e4c672020806d62999720c7214d62a8c7206" +
      "02d62b997210722ad62c99722a7225d62db2720b730e00d62e997212722cd62f730fd630ed93722472239372287227d6" +
      "317310d1ed96830a0193c5b2a4731100720193c27202c2a793b27203731200b27204731300938c7205017207938c7208" +
      "01720a93b17203731493e4c672020511720b91720c7315ed91720f7316917212731793b1720d7318959372137319d804" +
      "d6327e721906d6337e721b06d6347e722606d635ededededed93721c731a721f937220721e9372249a72239d9c723272" +
      "2272339372289a72279d9c723472227233ec947229731b94722b731c95917218731dd801d6367e731e06ededed929c9c" +
      "7e722c067e7218067e722d069c7ef0722e069a9c7e72170672367e9c7218722d069372329d9c7eb2720b731f00067e72" +
      "2906723693722673207235d801d6367e732106ededed929c9c7e7217067e722e067e722d069c7ef07218069a9c7e722c" +
      "0672367e9c722e722d069372349d9c7eb2720b732200067e722b06723693721973237235959372137324d804d6327e72" +
      "1b06d633b2a5732500d634db63087233d635b27234732600968312017230721f9372197327937226732891721c732990" +
      "7e721c06a19d9c7e72180672327e7217069d9c7e722e0672327e722c0693cbc27233722f938c723501720a938c723502" +
      "732a93b17234732b92c17233732cefe6c67233080e93e4c672330406722393e4c672330506722793e4c67233060e7201" +
      "93e4c672330705721c93dad901360eb0a5732dd9013841639a8c723801b0db63088c723802732ed9013a414d0ed802d6" +
      "3c8c723a02d63d8c723a0195938c723c0172369a723d8c723c02723d017201732f93722099721e7330959372137331d8" +
      "05d632b2a4733200d633b2db63087232733300d6347e721b06d6357e721c06d6369a721e733496830b01723093721973" +
      "35937226733693cbc27232722f938c723301720a938c7233027337ed929c7e72180672349c72357e721706929c7e722e" +
      "0672349c72357e722c0692721a7338939c7339721ce4c67232070593721d7236937220723695937213733ad808d632b2" +
      "a4733b00d633db63087232d634b27233733c00d635b2a5733d00d636db63087235d6378602830102733e733fd638b272" +
      "367340017237d6398c723802968312017230721f937220721eec91721673419172257342938c723401723193b2723673" +
      "43007234937229f0721693722bf0722592c172359ac1723272169272399a8cb272337344017237027225ec938c723801" +
      "7207937239734593e4c672350406722393e4c672350506722793720e7346937211734793721c7348937218734993722e" +
      "734a95937213734bd811d632b2a5734c00d633e4c672320705d634b2a4734d00d635e4c672340705d636b2db63087234" +
      "734e00d637db63087232d638b27237734f00d6397e723506d63a7e723306d63bb2a4735000d63cdb6308723bd63db272" +
      "3c735100d63eb2a5735200d63fdb6308723ed640860283010273537354d641b2723f7355017240d6428c72410296831d" +
      "0172309591721c7356d801d6437e721b06907e721c06a19d9c7e72180672437e7217069d9c7e722e0672437e722c06d8" +
      "02d6437e721b06d6447e721c06ed929c7e72180672439c72447e721706929c7e722e0672439c72447e722c0692721a73" +
      "5793721c997233723594721c7358917233735993cbc27234722f938c723601720a938c723602735a93cbc27232722f93" +
      "8c723801720a938c723802735b93b17237735c92c17232735defe6c67232080e93e4c67232060ee4c67234060e93721d" +
      "9a721e735e937220721e93e4c6723204069972239d9c7239997223e4c672340406723a93e4c6723205069972279d9c72" +
      "39997227e4c672340506723a938c723d01723193b2723f735f00723d92c1723e9ac1723b72169272429a8cb2723c7360" +
      "017240027225ec938c7241017207937242736193e4c6723e0406722393e4c6723e0506722793720e7362937211736373" +
      "64"))

  private lazy val templates: Map[LDContractKind, ContractTemplate] =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      LDContractKind.all.map(kind => kind -> fromTree(kind, ErgoTree.fromHex(pinnedTrees(kind)))).toMap
    }

  /** The template for one contract, read from its pinned tree. */
  def template(kind: LDContractKind): ContractTemplate = templates(kind)

  /** The pinned tree, sentinels included, as hex. */
  def pinnedTreeHex(kind: LDContractKind): String = pinnedTrees(kind)

  /** Index in the contract's constants of the term with this name. */
  def indexOf(kind: LDContractKind, name: String): Int =
    template(kind).parameters.find(_.name == name).map(_.constantIndex)
      .getOrElse(throw new NoSuchElementException(s"${kind.scriptName} has no term $name"))

  /**
   * Compiles a contract's `.ergo` source with sentinel terms, which is what the pinned tree must equal.
   * For the drift spec and for re-pinning only: compiling a script twice in one JVM can throw.
   */
  def compileSource(kind: LDContractKind): ErgoTree = {
    val builder = ConstantsBuilder.create()
    kind.termNames.foreach(name => builder.item(name, sentinel(name).value.asInstanceOf[AnyRef]))
    // The scripts hold no address literal, so the network prefix never reaches the tree
    Contract.fromErgoScript(NetworkType.MAINNET, builder.build(),
      ScriptGenerator.mkLithosDexScript(kind.scriptName), Seq.empty[Mutator]).ergoTree
  }

  private def fromTree(kind: LDContractKind, tree: ErgoTree): ContractTemplate = {
    val constants = tree.constants
    def describe = constants.zipWithIndex.map { case (c, i) => s"$i: ${c.tpe} = ${c.value}" }.mkString("; ")

    // A term that appears twice would need two values that nothing forces to agree
    val indices = kind.termNames.map { name =>
      val at = constants.indices.filter(i => constants(i) == sentinel(name))
      require(at.size == 1,
        s"${kind.scriptName}: $name must compile to exactly one constant, found ${at.size}. Constants: $describe")
      name -> at.head
    }.toMap
    val claimed = indices.values.toSet

    ContractTemplate(
      treeVersion = Some(tree.version),
      name = kind.scriptName,
      description = s"LithosDex ${kind.scriptName}",
      constTypes = constants.map(_.tpe),
      constValues = Some(constants.indices.map(i => if (claimed(i)) None else Some(constants(i).value))),
      parameters = kind.termNames.map(n => Parameter(n, descriptions(n), indices(n))).toIndexedSeq,
      expressionTree = tree.toProposition(false))
  }

  /** `kind` with every term supplied. Refuses a missing term, and any value that is not 32 bytes. */
  private def build(kind: LDContractKind, values: Map[String, Array[Byte]]): Contract = {
    require(values.keySet == kind.termNames.toSet,
      s"${kind.scriptName} takes ${kind.termNames.mkString(", ")}, got ${values.keySet.mkString(", ")}")
    require(values.values.forall(_.length == 32), s"${kind.scriptName} takes 32-byte ids and hashes only")
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      Contract(template(kind).applyTemplate(None, values.map { case (name, value) => name -> bytes(value) }))
    }
  }

  /**
   * LD_Provision — the provision logic.
   *
   * NOTE: this contract is never used as a box's script. Its serialized value bytes are handed to
   * the provision guard at spend time through context var 64, the same trade Collateral_Enforcer makes.
   */
  def mkProvisionLogic(poolNFT: ErgoId, vaultNFT: ErgoId): Contract =
    build(LDContractKind.Provision, Map(POOL_NFT -> poolNFT.getBytes, VAULT_NFT -> vaultNFT.getBytes))

  /**
   * LD_Provision_Guard — the script every provision box carries.
   *
   * @param provisionLogicHash `hashedValueBytes` of provision contract, not its prop bytes: the guard
   *                           hashes the bytes it is handed in a context var and then executes them.
   */
  def mkProvisionGuard(provisionLogicHash: Array[Byte]): Contract =
    build(LDContractKind.ProvisionGuard, Map(PROVISION_LOGIC_HASH -> provisionLogicHash))

  /** LD_FeeVault — the singleton holding flushed fees and the accumulators they paid for. */
  def mkFeeVault(poolNFT: ErgoId, provToken: ErgoId): Contract =
    build(LDContractKind.FeeVault, Map(POOL_NFT -> poolNFT.getBytes, PROV_TOKEN -> provToken.getBytes))

  /**
   * LD_LiquidityPool — the AMM.
   *
   * @param provisionGuardHash `hashedPropBytes` of provision guard. This is what a provision box is
   *                           actually locked under, so it is the prop bytes here rather than the value
   *                           bytes used for the logic.
   */
  def mkLPContract(vaultNFT: ErgoId, provisionGuardHash: Array[Byte]): Contract =
    build(LDContractKind.LiquidityPool, Map(VAULT_NFT -> vaultNFT.getBytes, PROVISION_GUARD_HASH -> provisionGuardHash))

  /**
   * The context var carrying the provision logic into a guard-locked box's spend. Every provision input
   * needs this attached alongside its own CTX_OP.
   */
  def provisionLogicVar(provisionLogic: Contract): ContextVar = {
    ContextVar.of(LDHelpers.PROVISION_LOGIC_VAR,
      ErgoValue.of(Colls.fromArray(provisionLogic.valueBytes), scalaByteType))
  }
}

/**
 * Every LithosDex contract for one deployment, with the hashes already threaded between them.
 *
 * @param poolNFT   singleton NFT of the pool
 * @param vaultNFT  singleton NFT of the fee vault
 * @param provToken id of the provision token, whose whole supply is minted into the pool at genesis
 */
case class LDContracts(poolNFT: ErgoId, vaultNFT: ErgoId, provToken: ErgoId, networkType: NetworkType) {

  /** LD_Provision. Never a box script — see [[LithosDexContracts.mkProvisionLogic]]. */
  val provisionLogic: Contract = LithosDexContracts.mkProvisionLogic(poolNFT, vaultNFT)

  /** LD_Provision_Guard. This is the address a provision box actually sits at. */
  val provisionGuard: Contract = LithosDexContracts.mkProvisionGuard(provisionLogic.hashedValueBytes)

  /** LD_FeeVault. */
  val feeVault: Contract = LithosDexContracts.mkFeeVault(poolNFT, provToken)

  /** LD_LiquidityPool. */
  val liquidityPool: Contract = LithosDexContracts.mkLPContract(vaultNFT, provisionGuard.hashedPropBytes)

  /** Attach to every provision input being spent, alongside its CTX_OP. */
  val provisionLogicVar: ContextVar = LithosDexContracts.provisionLogicVar(provisionLogic)
}

object LDContracts {

  /** Build from whatever [[LDHelpers]] holds for this network. */
  def apply(networkType: NetworkType): LDContracts = {
    LDContracts(
      LDHelpers.getPoolNFT(networkType),
      LDHelpers.getVaultNFT(networkType),
      LDHelpers.getProvToken(networkType),
      networkType
    )
  }

  def apply(ctx: BlockchainContext): LDContracts = apply(ctx.getNetworkType)

  def apply(ctx: BlockchainContext, poolNFT: ErgoId, vaultNFT: ErgoId, provToken: ErgoId): LDContracts =
    LDContracts(poolNFT, vaultNFT, provToken, ctx.getNetworkType)
}
