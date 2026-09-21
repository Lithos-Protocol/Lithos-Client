package configs

import play.api.Configuration

final case class MiningStatsConfig(enabled: Boolean = true, historyDays: Int = 30,
                                    pruningEnabled: Boolean = true, refreshIntervalMs: Int = 10000,
                                    staleAfterMs: Int = 120000, blocksPerRefresh: Int = 20,
                                    readTimeoutMs: Int = 3000, readBudgetMs: Int = 20000)
object MiningStatsConfig {
  def apply(config: Configuration): MiningStatsConfig = {
    val d = MiningStatsConfig()
    def int(name: String, default: Int): Int = config.getOptional[Int](s"stats.mining.$name").getOrElse(default)
    MiningStatsConfig(config.getOptional[Boolean]("stats.mining.enabled").getOrElse(d.enabled),
      int("historyDays", d.historyDays),
      config.getOptional[Boolean]("stats.mining.pruning.enabled").getOrElse(d.pruningEnabled),
      int("refreshIntervalMs", d.refreshIntervalMs), int("staleAfterMs", d.staleAfterMs),
      int("blocksPerRefresh", d.blocksPerRefresh), int("readTimeoutMs", d.readTimeoutMs), int("readBudgetMs", d.readBudgetMs))
  }
  def validate(v: ConfigValidator): Unit = {
    v.bool("stats.mining.enabled")
    v.bool("stats.mining.pruning.enabled")
    v.range("stats.mining.historyDays", v.int("stats.mining.historyDays"), 1, 36500, "days")
    val refresh = v.range("stats.mining.refreshIntervalMs", v.int("stats.mining.refreshIntervalMs"), 1000, 3600000, "milliseconds")
    val stale = v.range("stats.mining.staleAfterMs", v.int("stats.mining.staleAfterMs"), 1001, 86400000, "milliseconds")
    if (stale.getOrElse(120000) <= refresh.getOrElse(10000))
      v.problem("stats.mining.staleAfterMs", "must exceed stats.mining.refreshIntervalMs")
    v.range("stats.mining.blocksPerRefresh", v.int("stats.mining.blocksPerRefresh"), 1, 100, "blocks")
    v.range("stats.mining.readTimeoutMs", v.int("stats.mining.readTimeoutMs"), 100, 30000, "milliseconds")
    v.range("stats.mining.readBudgetMs", v.int("stats.mining.readBudgetMs"), 1000, 120000, "milliseconds")
  }
}
