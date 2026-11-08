package transactions.batching.sigmafi

import contracts.specs.harness.ContractSpecBase
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import sigma.ast.{Constant, ErgoTree, SType}
import sigmafi.contracts._
import support.LDNodeFixtures
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * SigmaFi boxes as the node reports them, built at the pinned contracts. Each box gets its own creating
 * transaction id, so boxes made in one test are distinct.
 */
trait SigmaFiFixtures extends ContractSpecBase {

  protected val Erg: Long = 1000000000L
  protected val MinBox: Long = SigmaFiContracts.MinBox

  protected val tokenA: ErgoId = ErgoId.create("aa" * 32)
  protected val tokenB: ErgoId = ErgoId.create("bb" * 32)
  protected val assetId: ErgoId = ErgoId.create("c1" * 32)

  protected val liq: LiquidationScript = SigmaFiContracts.MaturityLiquidation

  protected def hashOf(c: Contract): Array[Byte] = c.hashedPropBytes

  protected def nodeBox(ctx: BlockchainContext, utxo: UTXO, index: Int = 0, txId: String = "ab" * 32): NodeBox =
    LDNodeFixtures.nodeBox(ctx, utxo, index, txId)

  /** R4 asset, R5 borrower hash, R6 (repayment, reserve, principal ERG, Long maturity), R7 principal tokens, R8 liquidation. */
  protected def bondRegisters(asset: ErgoId, borrowerHash: Array[Byte], repayment: Long, fee: Long, maturity: Long,
                              liqHash: Array[Byte] = liq.hash): Seq[ErgoValue[_]] =
    Seq(ErgoValue.of(asset.getBytes), ErgoValue.of(borrowerHash), ErgoValue.of(Array(repayment, fee, Erg, maturity)),
      SigmaFiUserTransactions.principalValue(Seq.empty), ErgoValue.of(liqHash))

  protected def bondUTXO(repayToken: Option[ErgoId], borrower: Contract, maturity: Int, value: Long = 5 * Erg,
                         collateral: Seq[Token] = Seq.empty, repayment: Long = 2 * Erg, fee: Long = 50000000L,
                         asset: ErgoId = assetId, liqHash: Array[Byte] = liq.hash): UTXO =
    UTXO(SigmaFiContracts.bond(repayToken), value, Token(asset, 1L) +: collateral,
      bondRegisters(asset, hashOf(borrower), repayment, fee, maturity, liqHash))

  protected def orderTerms(borrower: Contract, schedule: ZCSchedule, principalErg: Long = Erg,
                           principalTokens: Seq[(ErgoId, Long)] = Seq.empty, repayment: Long = 2 * Erg,
                           fee: Long = 50000000L): ZCOrderTerms = {
    val repayToken = principalTokens.headOption.map(_._1)
    ZCOrderTerms(SigmaFiContracts.bond(repayToken).hashedPropBytes, hashOf(borrower), principalErg, principalTokens,
      repayment, schedule, liq.hash)
  }

  protected def vaultUTXO(bondId: ErgoId, asset: ErgoId, value: Long = 2 * Erg, tokens: Seq[Token] = Seq.empty): UTXO =
    UTXO(SigmaFiContracts.vault, value, tokens, Seq(ErgoValue.of(bondId.getBytes), ErgoValue.of(asset.getBytes), ErgoValue.of(1.toByte)))

  /** `tree` with constant `index` replaced, built the way instances are, so its template hash is unchanged. */
  protected def forge(tree: ErgoTree, index: Int, value: Any): ErgoTree =
    sigma.VersionContext.withVersions(3, 3) {
      ErgoTree(tree.header, tree.constants.updated(index,
        Constant[SType](value.asInstanceOf[SType#WrappedType], tree.constants(index).tpe)), tree.root.right.get)
    }

  /** The index of the one constant in `tree` holding `value` at type `tpe`. */
  protected def constantIndex(tree: ErgoTree, tpe: SType, value: Any): Int = {
    val found = tree.constants.indices.filter(i => tree.constants(i).tpe == tpe && tree.constants(i).value == value)
    found should have size 1
    found.head
  }
}
