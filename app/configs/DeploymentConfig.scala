package configs

import lfsm.{Deployment, DeploymentDescriptor}
import org.ergoplatform.appkit.NetworkType
import org.slf4j.Logger
import play.api.{ConfigLoader, Configuration}

import java.nio.file.{Files, Paths}

/**
 * Which Lithos deployment this client compiles its contracts against.
 *
 * Empty `file` is the network's own deployment, the constants in `LFSMHelpers`, which is every
 * production client. A descriptor points the client at tokens a private chain minted with
 * `tools.DeployProtocol`, so a devnet can mine Lithos blocks without real LIT.
 *
 * @param file           path to a deployment descriptor JSON; empty means none
 * @param allowOnMainnet a descriptor on a MAINNET client is refused unless this is set. A mislaid
 *                       devnet file would otherwise repoint a mainnet miner at contracts no real
 *                       emission box pays into, and it would mine solo without saying why.
 */
case class DeploymentConfig(file: String, allowOnMainnet: Boolean) {

  def configured: Boolean = file.trim.nonEmpty
}

object DeploymentConfig {

  final val FileKey = "node.deployment.file"
  final val AllowOnMainnetKey = "node.deployment.allowOnMainnet"

  /** Mirrors the `node.deployment` block in `application.conf`; keep them in step. */
  val Default: DeploymentConfig = DeploymentConfig(file = "", allowOnMainnet = false)

  def apply(config: Configuration): DeploymentConfig = DeploymentConfig(
    file = config.getOptional(FileKey)(ConfigLoader.stringLoader).getOrElse(Default.file),
    allowOnMainnet = config.getOptional(AllowOnMainnetKey)(ConfigLoader.booleanLoader)
      .getOrElse(Default.allowOnMainnet))

  /**
   * Every reason the descriptor named by `cfg` cannot be used on `network`, empty when it can (or when
   * none is configured). Shared by the startup validation pass and by [[install]], so the refusal an
   * operator sees in the config report is the same one that would stop the install.
   */
  def problems(cfg: DeploymentConfig, network: Option[NetworkType]): Seq[String] =
    if (!cfg.configured) Seq.empty
    else {
      val path = Paths.get(cfg.file.trim)
      if (!Files.isRegularFile(path)) Seq(s"'${cfg.file}' does not exist or is not a file")
      else DeploymentDescriptor.load(path) match {
        case Left(found) => found
        case Right(d) =>
          network.toSeq.flatMap { n =>
            (if (d.network != n)
              Seq(s"the descriptor was written for ${d.network} but node.networkType is $n")
            else Seq.empty) ++
              (if (n == NetworkType.MAINNET && !cfg.allowOnMainnet)
                Seq(s"refusing a deployment descriptor on MAINNET: set $AllowOnMainnetKey = true only if " +
                  "this client really should mine against contracts other than the public ones")
              else Seq.empty)
          }
      }
    }

  /** For `Configs.validateAll`, which reads the network as a string before anything is built. */
  def validate(v: ConfigValidator): Unit = {
    val file = v.string(FileKey).getOrElse(Default.file)
    val allow = v.bool(AllowOnMainnetKey).getOrElse(Default.allowOnMainnet)
    val network = v.string("node.networkType")
      .flatMap(n => scala.util.Try(NetworkType.valueOf(n.trim.toUpperCase)).toOption)
    problems(DeploymentConfig(file, allow), network).foreach(p => v.problem(FileKey, p))
  }

  /**
   * Loads and installs the configured descriptor, or leaves the constants in place, and says which.
   * Runs from `NodeConfig`, which is constructed before anything compiles a contract, so no tree can
   * be built against the constants and then outlive the switch.
   */
  def install(cfg: DeploymentConfig, network: NetworkType, logger: Logger): Option[DeploymentDescriptor] =
    if (!cfg.configured) {
      logger.info(s"deployment: ${network.toString.toLowerCase} constants")
      None
    } else {
      val found = problems(cfg, Some(network))
      if (found.nonEmpty) Configs.fail(FileKey, found.mkString("; "))
      val descriptor = DeploymentDescriptor.load(Paths.get(cfg.file.trim))
        .fold(p => Configs.fail(FileKey, p.mkString("; ")), identity)
      Deployment.install(descriptor)
      logger.info(s"deployment: ${cfg.file.trim}")
      logger.info(s"deployment ids: LIT ${descriptor.ids.lit}, emission NFT ${descriptor.ids.emissionNft}, " +
        s"collateral token ${descriptor.ids.collatToken}")
      Some(descriptor)
    }
}
