package configs

import play.api.Configuration

/** Live statistics are optional and never participate in mining admission. */
final case class StatsConfig(enabled: Boolean = true,
                             refreshIntervalMs: Int = 1000,
                             staleAfterMs: Int = 15000,
                             dex: DexStatsConfig = DexStatsConfig(),
                             storage: StatsStorageConfig = StatsStorageConfig(),
                             mining: MiningStatsConfig = MiningStatsConfig())

final case class DexStatsConfig(enabled: Boolean = true, refreshIntervalMs: Int = 30000,
                                staleAfterMs: Int = 120000, readTimeoutMs: Int = 3000,
                                refreshBudgetMs: Int = 20000, historyPages: Int = 10,
                                timestampLookups: Int = 128)

final case class StatsStorageConfig(enabled: Boolean = true,
                                    backend: String = "leveldb", path: String = ".lithos/stats",
                                    flushIntervalMs: Int = 5000, sampleIntervalMinutes: Int = 5,
                                    pruningEnabled: Boolean = true, retentionDays: Int = 30,
                                    pruneIntervalMs: Int = 60000, pruneBatchSize: Int = 256)

object StatsStorageConfig {
  def apply(config: Configuration): StatsStorageConfig = {
    val defaults = StatsStorageConfig()
    StatsStorageConfig(
      enabled = config.getOptional[Boolean]("stats.storage.enabled").getOrElse(defaults.enabled),
      backend = config.getOptional[String]("stats.storage.backend").getOrElse(defaults.backend),
      path = config.getOptional[String]("stats.storage.path").getOrElse(defaults.path),
      flushIntervalMs = config.getOptional[Int]("stats.storage.flushIntervalMs").getOrElse(defaults.flushIntervalMs),
      sampleIntervalMinutes = config.getOptional[Int]("stats.storage.sampleIntervalMinutes").getOrElse(defaults.sampleIntervalMinutes),
      pruningEnabled = config.getOptional[Boolean]("stats.storage.pruning.enabled").getOrElse(defaults.pruningEnabled),
      retentionDays = config.getOptional[Int]("stats.storage.pruning.retentionDays").getOrElse(defaults.retentionDays),
      pruneIntervalMs = config.getOptional[Int]("stats.storage.pruning.intervalMs").getOrElse(defaults.pruneIntervalMs),
      pruneBatchSize = config.getOptional[Int]("stats.storage.pruning.batchSize").getOrElse(defaults.pruneBatchSize))
  }

  def validate(validator: ConfigValidator): Unit = {
    validator.bool("stats.storage.enabled")
    validator.bool("stats.storage.pruning.enabled")
    validator.string("stats.storage.backend").foreach { backend =>
      storage.KeyValueStore.factoryFor(backend).left.foreach(error =>
        validator.problem("stats.storage.backend", error.message))
    }
    validator.string("stats.storage.path").foreach { path =>
      if (path.trim.isEmpty || scala.util.Try(java.nio.file.Paths.get(path)).isFailure)
        validator.problem("stats.storage.path", "must be a nonempty filesystem path")
    }
    validator.range("stats.storage.flushIntervalMs", validator.int("stats.storage.flushIntervalMs"), 1000, 3600000, "milliseconds")
    validator.range("stats.storage.sampleIntervalMinutes", validator.int("stats.storage.sampleIntervalMinutes"), 1, 1440, "minutes")
    validator.range("stats.storage.pruning.retentionDays", validator.int("stats.storage.pruning.retentionDays"), 1, 36500, "days")
    validator.range("stats.storage.pruning.intervalMs", validator.int("stats.storage.pruning.intervalMs"), 1000, 3600000, "milliseconds")
    validator.range("stats.storage.pruning.batchSize", validator.int("stats.storage.pruning.batchSize"), 1, 1440, "observations")
  }
}

object StatsConfig {
  val Default: StatsConfig = StatsConfig()

  def apply(config: Configuration): StatsConfig = {
    val result = StatsConfig(
      config.getOptional[Boolean]("stats.enabled").getOrElse(Default.enabled),
      config.getOptional[Int]("stats.refreshIntervalMs").getOrElse(Default.refreshIntervalMs),
      config.getOptional[Int]("stats.staleAfterMs").getOrElse(Default.staleAfterMs),
      DexStatsConfig(
        config.getOptional[Boolean]("stats.dex.enabled").getOrElse(Default.dex.enabled),
        config.getOptional[Int]("stats.dex.refreshIntervalMs").getOrElse(Default.dex.refreshIntervalMs),
        config.getOptional[Int]("stats.dex.staleAfterMs").getOrElse(Default.dex.staleAfterMs),
        config.getOptional[Int]("stats.dex.readTimeoutMs").getOrElse(Default.dex.readTimeoutMs),
        config.getOptional[Int]("stats.dex.refreshBudgetMs").getOrElse(Default.dex.refreshBudgetMs),
        config.getOptional[Int]("stats.dex.historyPages").getOrElse(Default.dex.historyPages),
        config.getOptional[Int]("stats.dex.timestampLookups").getOrElse(Default.dex.timestampLookups)),
      StatsStorageConfig(config), MiningStatsConfig(config))
    if (result.refreshIntervalMs < 100 || result.refreshIntervalMs > 60000)
      Configs.fail("stats.refreshIntervalMs", "must be between 100 and 60000 milliseconds")
    if (result.staleAfterMs <= result.refreshIntervalMs || result.staleAfterMs > 3600000)
      Configs.fail("stats.staleAfterMs", "must exceed refreshIntervalMs and be at most 3600000 milliseconds")
    val d = result.dex
    Seq(("refreshIntervalMs", d.refreshIntervalMs, 1000, 3600000),
      ("staleAfterMs", d.staleAfterMs, 1001, 86400000), ("readTimeoutMs", d.readTimeoutMs, 100, 30000),
      ("refreshBudgetMs", d.refreshBudgetMs, 100, 120000), ("historyPages", d.historyPages, 1, 50),
      ("timestampLookups", d.timestampLookups, 0, 250)).foreach { case (key, value, lo, hi) =>
      if (value < lo || value > hi) Configs.fail(s"stats.dex.$key", s"must be between $lo and $hi")
    }
    if (d.staleAfterMs <= d.refreshIntervalMs)
      Configs.fail("stats.dex.staleAfterMs", "must exceed stats.dex.refreshIntervalMs")
    val validator = new ConfigValidator(config)
    StatsStorageConfig.validate(validator)
    MiningStatsConfig.validate(validator)
    validator.finish()
    result
  }
}
