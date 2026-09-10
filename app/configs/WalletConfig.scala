package configs

import play.api.{ConfigLoader, Configuration}

/**
 * How this client's wallet selects, bounds and tidies its own UTXOs.
 *
 * These were compile-time constants scattered across `WalletInventory`, `EngineFunding` and the
 * engine's consolidation wiring. They are the knobs an operator actually needs: a miner with a
 * fragmented wallet wants different selection and consolidation behaviour from one with a handful of
 * large boxes, and neither should require a rebuild.
 *
 * Every default reproduces the constant it replaced, so an absent block changes nothing.
 *
 * @param maxInputs           inputs one selection may take. The ceiling on a single transaction's
 *                            wallet footprint, and what bounds a consolidation's size.
 * @param maxDescriptors      box descriptors held in the selection cache.
 * @param maxDescriptorBytes  memory that cache may occupy. Reached first on a wallet of large boxes.
 * @param maxInputBytes       largest box this client will hydrate. A box above it is skipped rather
 *                            than failing the walk.
 * @param pageSize            boxes per node read while walking the wallet.
 * @param inventoryTimeoutMs  budget for one whole-inventory walk, after which it fails rather than
 *                            returning a prefix that would misreport what the wallet holds.
 * @param reservationTimeoutMs how long a caller waits for funding before giving up. Funding is
 *                            serialised, so this bounds the queue behind a slow node read rather
 *                            than the read itself.
 */
case class WalletConfig(maxInputs: Int,
                        maxDescriptors: Int,
                        maxDescriptorBytes: Long,
                        maxInputBytes: Int,
                        pageSize: Int,
                        inventoryTimeoutMs: Long,
                        reservationTimeoutMs: Long,
                        consolidation: ConsolidationConfig)

/**
 * When this client merges its own wallet boxes.
 *
 * Consolidation runs in the optional lane and is deferred while any critical work is queued, so these
 * bound how often it may try rather than guaranteeing it runs.
 *
 * @param enabled     off by default. A miner who never fragments their wallet gains nothing from it.
 * @param targetUtxos wallet size to aim for. Above it one pass merges the excess; at or below it
 *                    nothing happens.
 * @param intervalMs  shortest gap between passes.
 * @param minInputs   inputs a pass must be able to merge before it is worth building. Merging two
 *                    boxes costs a fee to remove one box, so the floor stops trivial passes.
 * @param transactions independent consolidations one pass may build. Each spends its own boxes and
 *                    pays its own fee, so raising this is how a wallet of thousands is reduced in
 *                    reasonable time: one transaction removes at most `maxInputs - 1` boxes. They do
 *                    not chain, so a rejection costs only its own transaction.
 */
case class ConsolidationConfig(enabled: Boolean, targetUtxos: Int, intervalMs: Long, minInputs: Int,
                               transactions: Int)

object WalletConfig {

  /** Mirrors the `wallet` block in `application.conf`; keep them in step. */
  val Default: WalletConfig = WalletConfig(
    maxInputs = 75,
    maxDescriptors = 2048,
    maxDescriptorBytes = 1024L * 1024L,
    maxInputBytes = 4096,
    pageSize = 100,
    inventoryTimeoutMs = 240000L,
    reservationTimeoutMs = 30000L,
    consolidation = ConsolidationConfig(
      enabled = false,
      targetUtxos = 100,
      intervalMs = 300000L,
      minInputs = 2,
      transactions = 1))

  def apply(config: Configuration): WalletConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"wallet.$key")(ConfigLoader.intLoader).getOrElse(fallback)
    def long(key: String, fallback: Long): Long =
      config.getOptional(s"wallet.$key")(ConfigLoader.longLoader).getOrElse(fallback)
    def bool(key: String, fallback: Boolean): Boolean =
      config.getOptional(s"wallet.$key")(ConfigLoader.booleanLoader).getOrElse(fallback)

    val d = Default
    WalletConfig(
      maxInputs = int("max-inputs", d.maxInputs),
      maxDescriptors = int("max-descriptors", d.maxDescriptors),
      maxDescriptorBytes = long("max-descriptor-bytes", d.maxDescriptorBytes),
      maxInputBytes = int("max-input-bytes", d.maxInputBytes),
      pageSize = int("page-size", d.pageSize),
      inventoryTimeoutMs = long("inventory-walk-timeout-ms", d.inventoryTimeoutMs),
      reservationTimeoutMs = long("reservation-timeout-ms", d.reservationTimeoutMs),
      consolidation = ConsolidationConfig(
        enabled = bool("consolidation.enabled", d.consolidation.enabled),
        targetUtxos = int("consolidation.target-utxos", d.consolidation.targetUtxos),
        intervalMs = long("consolidation.interval-ms", d.consolidation.intervalMs),
        minInputs = int("consolidation.min-inputs", d.consolidation.minInputs),
        transactions = int("consolidation.num-transactions", d.consolidation.transactions)))
  }
}
