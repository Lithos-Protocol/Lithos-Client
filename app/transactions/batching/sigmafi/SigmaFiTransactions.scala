package transactions.batching.sigmafi

import mutations.NodeWallet
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import sigmafi.contracts.SigmaFiContracts
import transactions.batching.Batcher
import transactions.candidate.BlockTxMessages.{CandidateTx, Supersede}
import transactions.candidate.{CandidateBundle, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * One signed liquidation of a batch of bonds.
 *
 * @param bonds    in input order: bond i is input i and settles into output i
 * @param feeBox   the last output, holding the batching fees less any miner fee
 * @param fees     the batching fees the bonds gave up, before any miner fee
 */
final case class SigmaFiLiquidation(signed: SignedTransaction, bonds: Vector[SigmaFiBond], feeBox: InputUTXO,
                                    fees: Long) {
  def txId: String = CandidateTx.txId(signed)

  def candidateTx: CandidateTx = CandidateTx.signed(signed, SigmaFiTransactions.Kind)

  /** For this miner's block: supersedes `competitors` and credits the fee box, which the holding top-up takes. */
  def bundle(competitors: Set[String]): CandidateBundle =
    CandidateBundle(Vector(candidateTx), if (competitors.isEmpty) Seq.empty else Seq(Supersede(competitors)),
      Seq(CapitalEntry(CapitalOrigin.ExecutorReward, feeBox, txId)))
}

/**
 * Builds liquidations of matured bonds under the maturity liquidation script.
 *
 * Each bond carries its settlement output index as context variable 1 and the script's bytes as variable 0.
 * The selected vault preserves collateral token order, value less the reserve, R4 the bond's id,
 * R5 its asset and R6 the liquidation marker, independently of the bond's input position.
 * Each bond singleton is burned; the lender's circulating unit is never a funding input.
 * Every fee goes to one last output. No wallet box is spent, so the transaction balances exactly.
 */
object SigmaFiTransactions {

  /** Names the builder, so a refused transaction says where it came from. */
  final val Kind = "sigmafi-liquidation"

  /** The vault box `bond` settles into: everything it holds but its batching fee. */
  def vaultBox(bond: SigmaFiBond): UTXO =
    UTXO(SigmaFiContracts.vault, bond.box.value - bond.batchingFee,
      bond.collateral.map(a => Token(ErgoId.create(a.tokenId), a.amount)),
      Seq(bytes(ErgoId.create(bond.boxId).getBytes), bytes(bond.asset.getBytes), ErgoValue.of(1.toByte)))

  /**
   * Liquidates `bonds` in a block at `height`, sending their fees less `minerFee` to `feeTo`. Signed with HEIGHT
   * at `height`, since appkit signs at the tip and a bond maturing in the next block would refuse. Throws when a
   * bond names another script or is not due at `height`, its fee is negative or leaves its vault box under
   * 0.001 ERG, the fees cannot fund the miner fee and a box of their own, or an output holds less than the
   * node's minimum for its size.
   */
  def liquidate(ctx: BlockchainContext, wallet: NodeWallet, bonds: Seq[(SigmaFiBond, InputUTXO)], feeTo: Contract,
                minerFee: Long, height: Int): SigmaFiLiquidation = {
    require(bonds.nonEmpty, "nothing to liquidate")
    bonds.foreach { case (b, _) =>
      require(b.liquidation.contains(SigmaFiContracts.MaturityLiquidation),
        s"bond ${b.boxId} names a liquidation script other than the maturity liquidation")
      require(b.batchingFee >= 0 && b.box.value - b.batchingFee >= SigmaFiContracts.MinBox,
        s"bond ${b.boxId}'s batching fee leaves its vault box under 0.001 ERG")
    }
    val fees = bonds.map(_._1.batchingFee).foldLeft(0L)(Math.addExact)
    require(fees - minerFee >= SigmaFiContracts.MinBox,
      s"the batching fees, $fees nanoERG, do not cover a $minerFee nanoERG miner fee and a box of their own")
    val script = bytes(SigmaFiContracts.MaturityLiquidation.bytes)
    require(minerFee >= 0, "miner fee cannot be negative")
    require(bonds.map(_._1.boxId).distinct.size == bonds.size, "duplicate bonds")
    val inputs = bonds.zipWithIndex.map { case ((b, box), i) =>
      require(box.id.toString == b.boxId, "bond input does not match its terms")
      require(height.toLong >= b.maturity, "bond has not matured")
      box.withCtxVar(0.toByte, script).withCtxVar(1.toByte, ErgoValue.of(i))
    }
    val outputs = bonds.map { case (b, _) => vaultBox(b) } :+ UTXO(feeTo, fees - minerFee)
    val unsigned = work.lithos.mutations.TxBuilder(ctx).setInputs(inputs: _*).setOutputs(outputs: _*)
      .setPreHeader(ctx.createPreHeader().height(height).build())
      .buildTx(minerFee, wallet.p2pk, bonds.map { case (b, _) => Token(b.asset, 1L) })
    // Signing runs every bond's script and its liquidation, so a spend either refuses fails here
    val signed = Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
    SigmaFiLiquidation(signed, bonds.map(_._1).toVector, InputUTXO(signed.getOutputsToSpend.get(bonds.size)), fees)
  }

  private def bytes(b: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(b), scalaByteType)
}
