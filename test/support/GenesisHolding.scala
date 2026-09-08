package support

import lfsm.states.RollupInfoState
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import sigma.data.AvlTreeFlags
import work.lithos.mutations.{InputUTXO, UTXO}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

/**
 * The holding box a genesis transaction leaves behind, at the contract production compiles.
 *
 * Built at `utils.Helpers.holdingContract` rather than a spec base's copy, because a builder driven
 * by these fixtures puts its output at the production script and hands the production logic to
 * context variable 64 — a box under any other guard is refused before anything under test runs.
 */
object GenesisHolding {

  /**
   * @param blockHeight the block being mined. It fills both R7 slots the way genesis does, and is
   *                    the only height the contract permits a top-up to spend this box at.
   */
  def apply(ctx: BlockchainContext, blockHeight: Int,
            value: Long = 60L * Parameters.OneErg,
            txId: String = "ee" * 32): InputUTXO =
    UTXO(utils.Helpers.holdingContract(ctx), value, Seq.empty, Seq(
      PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, PlasmaParameters.default).ergoValue,
      ErgoValue.of(0),
      ErgoValue.of(BigInt(0).bigInteger),
      RollupInfoState.holding(blockHeight.toLong, blockHeight.toLong, 0L).ergoValue))
      .toInput(ctx, ErgoId.create(txId), 0.toShort)
}
