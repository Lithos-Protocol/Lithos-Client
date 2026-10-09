package lfsm

import com.google.gson.{GsonBuilder, JsonObject, JsonParser}
import lfsm.contracts.RollupContracts
import org.ergoplatform.appkit.{Address, NetworkType}
import org.ergoplatform.sdk.ErgoId

import scala.util.{Failure, Success, Try}

/**
 * Every id a Lithos deployment compiles into its contracts or finds its singletons by.
 *
 * A value rather than a set of per-network constants so that a private chain can run the protocol
 * against tokens it minted itself. The contracts chain these ids into each other's hashes (the guard
 * carries the emission NFT, the collateral contract the config NFT and LIT, Evaluation the FP token),
 * so they are only meaningful as one set: mixing a minted collateral token with the public LIT would
 * compile a collateral contract no emission box can ever pay into.
 *
 * `mdGenesisHeight` is the dictionary genesis box's inclusion height, not its creation height, as
 * [[LFSMHelpers.getMDGenesisHeight]] has always read it.
 */
final case class DeploymentIds(lit: ErgoId,
                               emissionNft: ErgoId,
                               emConfigNft: ErgoId,
                               queueToken: ErgoId,
                               collatToken: ErgoId,
                               fpToken: ErgoId,
                               mdToken: ErgoId,
                               voteToken: ErgoId,
                               mdGenesisId: String,
                               mdGenesisHeight: Int,
                               fpControlAddress: Address) {

  /** The eight token ids, named as the descriptor names them. */
  def tokens: Seq[(String, ErgoId)] = Seq(
    DeploymentIds.Keys.Lit -> lit,
    DeploymentIds.Keys.EmissionNft -> emissionNft,
    DeploymentIds.Keys.EmConfigNft -> emConfigNft,
    DeploymentIds.Keys.QueueToken -> queueToken,
    DeploymentIds.Keys.CollatToken -> collatToken,
    DeploymentIds.Keys.FpToken -> fpToken,
    DeploymentIds.Keys.MdToken -> mdToken,
    DeploymentIds.Keys.VoteToken -> voteToken)

  /**
   * A canonical string for the whole set, used as a cache key.
   *
   * `ErgoId` and `Address` are Java types whose equality this code does not control, so caching
   * compiled contracts on the case class itself could serve one deployment's trees to another.
   */
  def fingerprint: String =
    (tokens.map { case (k, v) => s"$k=$v" } ++
      Seq(s"mdGenesisId=$mdGenesisId", s"mdGenesisHeight=$mdGenesisHeight",
        s"fpControlAddress=$fpControlAddress")).mkString(";")

  /** Written into the descriptor object `out`, under [[DeploymentIds.Keys]]. */
  def writeTo(out: JsonObject): JsonObject = {
    tokens.foreach { case (k, v) => out.addProperty(k, v.toString) }
    out.addProperty(DeploymentIds.Keys.MdGenesisId, mdGenesisId)
    out.addProperty(DeploymentIds.Keys.MdGenesisHeight, mdGenesisHeight)
    out.addProperty(DeploymentIds.Keys.FpControlAddress, fpControlAddress.toString)
    out
  }
}

object DeploymentIds {

  /** Descriptor keys, stable so that other tools can read `collatToken`, `litId` and the box ids. */
  object Keys {
    final val Lit = "litId"
    final val EmissionNft = "emissionNft"
    final val EmConfigNft = "emConfigNft"
    final val QueueToken = "queueToken"
    final val CollatToken = "collatToken"
    final val FpToken = "fpToken"
    final val MdToken = "mdToken"
    final val VoteToken = "voteToken"
    final val MdGenesisId = "mdGenesisId"
    final val MdGenesisHeight = "mdGenesisHeight"
    final val FpControlAddress = "fpControlAddress"
  }

  private val Hex64 = "^[0-9a-fA-F]{64}$".r

  /**
   * The public mainnet deployment, from the constants in [[LFSMHelpers]].
   *
   * Mainnet has no FP control address constant in [[LFSMHelpers]]: the box lives under
   * `FP_Control_Mainnet`, which takes no constants. Its address is pinned here as a constant, so that
   * reading any mainnet id never requires compiling a contract; `DeployPlanSpec` holds the compiled
   * script to it.
   */
  final val FpControlMainnetAddress: String =
    "GPp4BycDwAwoCGQnA3QC1UQyqNuB2UxG6KMiRFioBqwo1kAUaqKwQZL1RQ8nZz1jN4EmYt6XVuWUXMzxiXja5FwxiUmCcxHGfrNjeSbVroaxrnKqHMqKwsgzMUAz5yMErBNW8x84n3c9BzcF"

  lazy val mainnet: DeploymentIds = DeploymentIds(
    lit = LFSMHelpers.LIT_ID_MAINNET,
    emissionNft = LFSMHelpers.EMISSION_NFT_MAINNET,
    emConfigNft = LFSMHelpers.EMCONFIG_NFT_MAINNET,
    queueToken = LFSMHelpers.QUEUE_TOKEN_MAINNET,
    collatToken = LFSMHelpers.COLLAT_TOKEN_MAINNET,
    fpToken = LFSMHelpers.FP_TOKEN_MAINNET,
    mdToken = LFSMHelpers.MD_TOKEN_MAINNET,
    voteToken = LFSMHelpers.VOTE_TOKEN_MAINNET,
    mdGenesisId = LFSMHelpers.MD_GENESIS_ID_MAINNET,
    mdGenesisHeight = LFSMHelpers.MD_GENESIS_HEIGHT_MAINNET,
    fpControlAddress = Address.create(FpControlMainnetAddress))

  /** The public testnet deployment, from the constants in [[LFSMHelpers]]. */
  lazy val testnet: DeploymentIds = DeploymentIds(
    lit = LFSMHelpers.LIT_ID_TESTNET,
    emissionNft = LFSMHelpers.EMISSION_NFT_TESTNET,
    emConfigNft = LFSMHelpers.EMCONFIG_NFT_TESTNET,
    queueToken = LFSMHelpers.QUEUE_TOKEN_TESTNET,
    collatToken = LFSMHelpers.COLLAT_TOKEN_TESTNET,
    fpToken = LFSMHelpers.FP_TOKEN_TESTNET,
    mdToken = LFSMHelpers.MD_TOKEN_TESTNET,
    voteToken = LFSMHelpers.VOTE_TOKEN_TESTNET,
    mdGenesisId = LFSMHelpers.MD_GENESIS_ID_TESTNET,
    mdGenesisHeight = LFSMHelpers.MD_GENESIS_HEIGHT_TESTNET,
    fpControlAddress = LFSMHelpers.FP_CONTROL_TESTNET)

  /** The constants for `network`, which is what every getter answers when no override is installed. */
  def constants(network: NetworkType): DeploymentIds = network match {
    case NetworkType.MAINNET => mainnet
    case NetworkType.TESTNET => testnet
  }

  /**
   * The ids out of a descriptor object, every one checked before any is used.
   *
   * Each problem is collected rather than the first thrown, so an operator fixing a hand-edited file
   * sees all of it at once. The FP control address must be one of `network`'s, because an address
   * of the other network decodes to the same tree and would be accepted silently everywhere else.
   */
  def read(o: JsonObject, network: NetworkType): Either[Seq[String], DeploymentIds] = {
    val problems = Seq.newBuilder[String]

    def str(key: String): Option[String] =
      Option(o.get(key)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString) match {
        case None => problems += s"$key is missing or not a string"; None
        case some => some
      }

    def hex64(key: String): Option[String] = str(key).flatMap { s =>
      if (Hex64.pattern.matcher(s).matches()) Some(s.toLowerCase)
      else { problems += s"$key must be 64 hex characters, got ${s.length}: '$s'"; None }
    }

    def id(key: String): Option[ErgoId] = hex64(key).map(ErgoId.create)

    val lit = id(Keys.Lit)
    val emissionNft = id(Keys.EmissionNft)
    val emConfigNft = id(Keys.EmConfigNft)
    val queueToken = id(Keys.QueueToken)
    val collatToken = id(Keys.CollatToken)
    val fpToken = id(Keys.FpToken)
    val mdToken = id(Keys.MdToken)
    val voteToken = id(Keys.VoteToken)
    val mdGenesisId = hex64(Keys.MdGenesisId)

    val mdGenesisHeight = Option(o.get(Keys.MdGenesisHeight))
      .flatMap(e => Try(e.getAsInt).toOption) match {
      case Some(h) if h >= 0 => Some(h)
      case Some(h) => problems += s"${Keys.MdGenesisHeight} must not be negative, got $h"; None
      case None => problems += s"${Keys.MdGenesisHeight} is missing or not an integer"; None
    }

    val fpControl = str(Keys.FpControlAddress).flatMap { s =>
      Try(Address.create(s)) match {
        case Success(a) if a.getNetworkType == network => Some(a)
        case Success(a) =>
          problems += s"${Keys.FpControlAddress} is a ${a.getNetworkType} address but the deployment is for $network"
          None
        case Failure(ex) =>
          problems += s"${Keys.FpControlAddress} is not an address: ${ex.getMessage}"
          None
      }
    }

    val ids = for {
      l <- lit; e <- emissionNft; c <- emConfigNft; q <- queueToken; ct <- collatToken
      f <- fpToken; m <- mdToken; v <- voteToken; g <- mdGenesisId; h <- mdGenesisHeight; a <- fpControl
    } yield DeploymentIds(l, e, c, q, ct, f, m, v, g, h, a)

    val distinct = ids.toSeq.flatMap { d =>
      val dupes = d.tokens.groupBy(_._2.toString).collect { case (_, ks) if ks.size > 1 => ks.map(_._1) }
      // Two roles sharing a token id would let one box pass as the other's singleton.
      dupes.map(ks => s"${ks.mkString(" and ")} name the same token")
    }
    problems ++= distinct

    val all = problems.result()
    ids match {
      case Some(d) if all.isEmpty => Right(d)
      case _ => Left(all)
    }
  }
}

/**
 * What the deployer writes and the client reads: the ids, the network they were minted on, and the
 * protocol boxes the deployer created, so a hook can find a Lithos block without compiling anything.
 *
 * The box ids and heights are informational for the client, which finds every box by its token. They
 * are optional so a descriptor written by hand for a public network still loads.
 */
final case class DeploymentDescriptor(network: NetworkType,
                                      ids: DeploymentIds,
                                      emissionBoxId: Option[String] = None,
                                      configBoxId: Option[String] = None,
                                      fpControlBoxId: Option[String] = None,
                                      deployerAddress: Option[String] = None,
                                      height: Option[Int] = None) {

  def toJson: String = {
    val o = new JsonObject
    o.addProperty(DeploymentDescriptor.Keys.Network, network.toString)
    ids.writeTo(o)
    emissionBoxId.foreach(o.addProperty(DeploymentDescriptor.Keys.EmissionBoxId, _))
    configBoxId.foreach(o.addProperty(DeploymentDescriptor.Keys.ConfigBoxId, _))
    fpControlBoxId.foreach(o.addProperty(DeploymentDescriptor.Keys.FpControlBoxId, _))
    deployerAddress.foreach(o.addProperty(DeploymentDescriptor.Keys.DeployerAddress, _))
    height.foreach(h => o.addProperty(DeploymentDescriptor.Keys.Height, h))
    new GsonBuilder().setPrettyPrinting().create().toJson(o)
  }
}

object DeploymentDescriptor {

  object Keys {
    final val Network = "network"
    final val EmissionBoxId = "emissionBoxId"
    final val ConfigBoxId = "configBoxId"
    final val FpControlBoxId = "fpControlBoxId"
    final val DeployerAddress = "deployerAddress"
    final val Height = "height"
  }

  private val Hex64 = "^[0-9a-fA-F]{64}$".r

  /**
   * A descriptor from its JSON text, with every problem listed. The network comes from the file
   * itself, so a descriptor minted on one network cannot be read as the other's.
   */
  def parse(json: String): Either[Seq[String], DeploymentDescriptor] =
    Try(new JsonParser().parse(json)) match {
      case Failure(ex) => Left(Seq(s"not JSON: ${ex.getMessage}"))
      case Success(e) if !e.isJsonObject => Left(Seq("not a JSON object"))
      case Success(e) =>
        val o = e.getAsJsonObject
        val networkRaw = Option(o.get(Keys.Network)).flatMap(v => Try(v.getAsString).toOption)
        networkRaw.flatMap(n => Try(NetworkType.valueOf(n.trim.toUpperCase)).toOption) match {
          case None =>
            Left(Seq(s"${Keys.Network} must be MAINNET or TESTNET, got ${networkRaw.getOrElse("nothing")}"))
          case Some(network) =>
            val problems = Seq.newBuilder[String]

            def optHex(key: String): Option[String] =
              Option(o.get(key)).flatMap(v => Try(v.getAsString).toOption) match {
                case Some(s) if Hex64.pattern.matcher(s).matches() => Some(s.toLowerCase)
                case Some(s) => problems += s"$key must be 64 hex characters, got '$s'"; None
                case None => None
              }

            val emission = optHex(Keys.EmissionBoxId)
            val config = optHex(Keys.ConfigBoxId)
            val fpControl = optHex(Keys.FpControlBoxId)
            val deployer = Option(o.get(Keys.DeployerAddress)).flatMap(v => Try(v.getAsString).toOption)
            val height = Option(o.get(Keys.Height)).flatMap(v => Try(v.getAsInt).toOption)

            DeploymentIds.read(o, network) match {
              case Left(more) => Left(problems.result() ++ more)
              case Right(ids) =>
                val all = problems.result()
                if (all.nonEmpty) Left(all)
                else Right(DeploymentDescriptor(network, ids, emission, config, fpControl, deployer, height))
            }
        }
    }

  def load(path: java.nio.file.Path): Either[Seq[String], DeploymentDescriptor] =
    Try(new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)) match {
      case Failure(ex) => Left(Seq(s"could not read $path: ${ex.getMessage}"))
      case Success(text) => parse(text)
    }
}

/**
 * The deployment this JVM compiles against when it is not the network's own.
 *
 * Process-wide because the ids reach the contracts through `LFSMHelpers.get*(network)`, which a
 * hundred call sites use with nothing but a network in hand; threading a value through all of them
 * would touch every builder for a feature mainnet never uses. Scoped to one network so a test, or a
 * tool, that builds a context for the other network still sees that network's constants.
 *
 * Installed once at startup, before anything compiles (`NodeConfig` does it). A second install with
 * different ids is refused rather than applied, because contracts already compiled and boxes already
 * found under the first set would silently disagree with everything compiled after.
 */
object Deployment {

  @volatile private var installed: Option[(NetworkType, DeploymentIds)] = None

  /** The override for `network`, if one is installed for it. */
  def overrideFor(network: NetworkType): Option[DeploymentIds] =
    installed.collect { case (n, ids) if n == network => ids }

  /** What every getter answers for `network`: the override when installed, the constants otherwise. */
  def ids(network: NetworkType): DeploymentIds =
    overrideFor(network).getOrElse(DeploymentIds.constants(network))

  def current: Option[(NetworkType, DeploymentIds)] = installed

  def install(network: NetworkType, ids: DeploymentIds): Unit = synchronized {
    installed match {
      case Some((n, existing)) if n != network || existing.fingerprint != ids.fingerprint =>
        throw new IllegalStateException(
          s"a deployment override is already installed for $n; refusing to replace it while the JVM runs")
      case _ => installed = Some(network -> ids)
    }
  }

  def install(descriptor: DeploymentDescriptor): Unit = install(descriptor.network, descriptor.ids)

  /** Removes the override. For specs and for the deployer between runs, never for a running client. */
  def clear(): Unit = synchronized { installed = None }
}
