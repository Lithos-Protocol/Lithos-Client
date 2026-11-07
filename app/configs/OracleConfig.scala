package configs

import play.api.Configuration

import scala.util.control.NonFatal

final case class OracleFeedConfig(id: String, name: String, network: String, poolNft: String,
                                   ergoTreeHash: String, baseAsset: String, quoteAsset: String,
                                   baseUnit: String, baseDecimals: Int, maxAgeBlocks: Int)
final case class OracleConfig(feeds: Seq[OracleFeedConfig], defaultHistoryLimit: Int,
                               readTimeoutMs: Int, readBudgetMs: Int)

object OracleConfig {
  final val MaxHistoryLimit = 200
  // Pool identity: oracle-stats/status-page/src/config.ts. Guard confirmed against the mainnet NFT box.
  val UsdV2: OracleFeedConfig = OracleFeedConfig("erg-usd", "USD v2", "MAINNET",
    "6a2b821b5727e85beb5e78b4efb9f0250d59cd48481d2ded2c23e91ba1d07c66",
    "540337996549d4c40c9b94291cca12c96cf2d790ba4e0318d97a6910bb49bb89",
    "ERG", "USD", "nanoERG", 9, 30)
  val Default: OracleConfig = OracleConfig(Seq(UsdV2), 100, 3000, 10000)

  def apply(config: Configuration): OracleConfig = {
    def bounded(key: String, value: Int, min: Int, max: Int): Int = {
      if (value < min || value > max) Configs.fail(key, s"must be in $min..$max")
      value
    }
    val feeds = config.getOptional[Seq[Configuration]]("oracles.feeds").map(_.zipWithIndex.map { case (c, i) =>
      val key = s"oracles.feeds[$i]"
      def str(field: String): String = c.getOptional[String](field).filter(_.nonEmpty)
        .getOrElse(Configs.fail(s"$key.$field", "is required and must be nonempty"))
      val f = OracleFeedConfig(str("id"), str("name"), str("network").toUpperCase,
        str("poolNft").toLowerCase, str("ergoTreeHash").toLowerCase, str("baseAsset"), str("quoteAsset"),
        str("baseUnit"), c.getOptional[Int]("baseDecimals").getOrElse(9), c.getOptional[Int]("maxAgeBlocks").getOrElse(30))
      if (!f.id.matches("[a-z][a-z0-9-]{0,47}")) Configs.fail(s"$key.id", "must be a lowercase feed id of at most 48 characters")
      if (!Set("MAINNET", "TESTNET")(f.network)) Configs.fail(s"$key.network", "must be MAINNET or TESTNET")
      Seq("poolNft" -> f.poolNft, "ergoTreeHash" -> f.ergoTreeHash).foreach { case (field, value) =>
        if (!value.matches("[0-9a-f]{64}")) Configs.fail(s"$key.$field", "must be a 32-byte hex identifier")
      }
      if (Seq(f.name, f.baseAsset, f.quoteAsset, f.baseUnit).exists(_.length > 64))
        Configs.fail(key, "labels must be at most 64 characters")
      bounded(s"$key.baseDecimals", f.baseDecimals, 0, 18)
      bounded(s"$key.maxAgeBlocks", f.maxAgeBlocks, 1, 100000)
      f
    }).getOrElse(Default.feeds)
    if (feeds.size > 32 || feeds.map(f => f.network -> f.id).distinct.size != feeds.size)
      Configs.fail("oracles.feeds", "must contain at most 32 feeds, with unique ids per network")
    OracleConfig(feeds,
      bounded("oracles.defaultHistoryLimit", config.getOptional[Int]("oracles.defaultHistoryLimit").getOrElse(100), 1, MaxHistoryLimit),
      bounded("oracles.readTimeoutMs", config.getOptional[Int]("oracles.readTimeoutMs").getOrElse(3000), 100, 30000),
      bounded("oracles.readBudgetMs", config.getOptional[Int]("oracles.readBudgetMs").getOrElse(10000), 1000, 60000))
  }

  def validate(config: Configuration): Unit = try { apply(config); () } catch {
    case e: ConfigValidationException => throw e
    case NonFatal(e) => Configs.fail("oracles", s"invalid oracle configuration: ${e.getMessage}")
  }
}
