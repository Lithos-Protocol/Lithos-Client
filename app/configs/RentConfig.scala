package configs

import play.api.{ConfigLoader, Configuration}

/**
 * How this client looks for boxes whose storage rent is due.
 *
 * Discovery walks the chain by inclusion height rather than reading the whole UTXO set. A box
 * created at height `h` becomes collectable at `h + StoragePeriod`, and is included at or after `h`,
 * so the walk trails four years behind the tip and reads a little past what is already due.
 *
 * @param startBehindBlocks blocks behind the due frontier the walk begins at, measured from the tip
 *                       the client starts at. Other collectors sweep boxes as they fall due, so the
 *                       chain further back holds nothing to collect and walking it costs node reads.
 * @param scanIntervalMs gap between scan passes. Discovery is background work and must never be on
 *                       the path a block is built on.
 * @param blocksPerScan  blocks one pass reads. Two node calls each, so this is what bounds the load
 *                       a pass puts on the node while the walk is catching up.
 * @param lookaheadBlocks blocks the walk reads past those whose boxes are already due. A box is
 *                       usually included a few blocks after its declared creation height, and other
 *                       collectors take it the block it falls due; boxes met early are held until then.
 */
case class RentConfig(startBehindBlocks: Int, scanIntervalMs: Int, blocksPerScan: Int,
                      lookaheadBlocks: Int)

object RentConfig {

  /** Mirrors the `stratum.candidate.sources.rent` block in `application.conf`; keep them in step. */
  val Default: RentConfig = RentConfig(
    startBehindBlocks = 720,
    scanIntervalMs = 30000,
    blocksPerScan = 200,
    lookaheadBlocks = 100)

  def apply(config: Configuration): RentConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"stratum.candidate.sources.rent.$key")(ConfigLoader.intLoader)
        .getOrElse(fallback)

    RentConfig(
      startBehindBlocks = int("startBehindBlocks", Default.startBehindBlocks),
      scanIntervalMs = int("scanIntervalMs", Default.scanIntervalMs),
      blocksPerScan = int("blocksPerScan", Default.blocksPerScan),
      lookaheadBlocks = int("lookaheadBlocks", Default.lookaheadBlocks))
  }
}
