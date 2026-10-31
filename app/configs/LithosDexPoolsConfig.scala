package configs

import org.slf4j.LoggerFactory
import play.api.{ConfigLoader, Configuration}

/**
 * `lithosdex.pools`: how the API and web panel list LithosDex pools.
 *
 * @param hidden pool NFTs left out of the pool list, in lower case. Nothing else about them changes: their own
 *               routes still answer, and their orders are filled unless `batching.lithosdex.deniedPools` names them
 */
case class LithosDexPoolsConfig(hidden: Set[String])

object LithosDexPoolsConfig {

  private val logger = LoggerFactory.getLogger("LithosDexPoolsConfig")

  /** Mirrors `lithosdex.pools` in application.conf; keep the two in step. */
  val Default: LithosDexPoolsConfig = LithosDexPoolsConfig(hidden = Set(
    "eb123f49f083f5b68d10d7037a45eb11d3371210ab10bc66757e29432d661ee6"))

  /** An entry that is not a 64-character id is dropped with a warning: hiding a pool is never worth a failed start. */
  def apply(config: Configuration): LithosDexPoolsConfig = {
    val listed = config.getOptional[Seq[String]]("lithosdex.pools.hidden")(ConfigLoader.seqStringLoader)
      .getOrElse(Default.hidden.toSeq).map(_.trim.toLowerCase)
    val (ids, bad) = listed.partition(_.matches("[0-9a-f]{64}"))
    bad.foreach(b => logger.warn(s"lithosdex.pools.hidden: '$b' is not a 64-character pool NFT id; ignoring it"))
    LithosDexPoolsConfig(ids.toSet)
  }
}
