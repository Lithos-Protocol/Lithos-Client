package configs

import play.api.{ConfigLoader, Configuration}

/**
 * How this client looks for boxes whose storage rent is due.
 *
 * Discovery walks the chain by inclusion height rather than reading the whole UTXO set: a box
 * created at height `h` becomes collectable at `h + StoragePeriod`, so the walk trails four years
 * behind the tip and every box it meets there is old enough by construction.
 *
 * @param startHeight    inclusion height the walk begins at. Boxes created below it are never seen,
 *                       so it is set to wherever collection has already caught up rather than to
 *                       zero — the early chain is long since swept and walking it costs node reads
 *                       for nothing.
 * @param scanIntervalMs gap between scan passes. Discovery is background work and must never be on
 *                       the path a block is built on.
 * @param blocksPerScan  blocks one pass reads. Two node calls each, so this is what bounds the load
 *                       a pass puts on the node while the walk is catching up.
 */
case class RentConfig(startHeight: Int, scanIntervalMs: Int, blocksPerScan: Int)

object RentConfig {

  /** Mirrors the `stratum.candidate.sources.rent` block in `application.conf`; keep them in step. */
  val Default: RentConfig = RentConfig(
    startHeight = 800000,
    scanIntervalMs = 30000,
    blocksPerScan = 200)

  def apply(config: Configuration): RentConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"stratum.candidate.sources.rent.$key")(ConfigLoader.intLoader)
        .getOrElse(fallback)

    RentConfig(
      startHeight = int("startHeight", Default.startHeight),
      scanIntervalMs = int("scanIntervalMs", Default.scanIntervalMs),
      blocksPerScan = int("blocksPerScan", Default.blocksPerScan))
  }
}
