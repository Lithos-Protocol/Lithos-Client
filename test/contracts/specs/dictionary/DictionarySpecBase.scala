package contracts.specs.dictionary

import contracts.specs.harness.ContractSpecBase
import lfsm.LFSMHelpers
import lfsm.contracts.DictionaryContracts
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import scorex.utils.Longs
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Compiled once and shared. The sigma compiler mutates `_sourceContext` on shared AST nodes, so
 * compiling a script twice — or from two suites at once — throws "can only be set once".
 */
case class DictionarySet(dictionary: Contract, dataGuard: Contract, dataLogic: Contract)

object DictionarySpecBase {
  private var cache: Option[DictionarySet] = None

  def compiled(ctx: BlockchainContext, mdToken: ErgoId): DictionarySet = synchronized {
    cache.getOrElse {
      // mkMinerDataContract returns the guard and injects the logic's hash itself. The logic is
      // compiled again here so specs can hand it to context var 64; both must use identical
      // constants or the guard rejects it.
      val all = DictionarySet(
        DictionaryContracts.mkMinerDictionaryContract(ctx, mdToken),
        DictionaryContracts.mkMinerDataContract(ctx, mdToken),
        DictionaryContracts.mkMinerDataLogicContract(ctx, mdToken))
      cache = Some(all)
      all
    }
  }
}

/**
 * The MinerDictionary singleton and the MinerData guard/logic pair it creates.
 *
 * Registration mints a credential token taking the dictionary box's own id, so every scenario here
 * has the dictionary at `INPUTS(0)` and reads `credentialId` off that input rather than off a
 * constant — the same shape as the rollup NFT in `CollateralSpec`.
 */
trait DictionarySpecBase extends ContractSpecBase {

  /**
   * Belongs to nothing in the protocol, on purpose. The placeholder ids in `LFSMHelpers` collide with
   * each other, so reaching for one here quietly produces a second proposition token.
   */
  protected val mdToken: ErgoId =
    ErgoId.create("4d44544f4b454e00000000000000000000000000000000000000000000000001")

  protected def dictionaryContract(ctx: BlockchainContext): Contract =
    DictionarySpecBase.compiled(ctx, mdToken).dictionary

  /** What a MinerData box actually carries. */
  protected def dataContract(ctx: BlockchainContext): Contract =
    DictionarySpecBase.compiled(ctx, mdToken).dataGuard

  /** The rules the guard runs out of context var 64. Never on a box. */
  protected def dataLogic(ctx: BlockchainContext): Contract =
    DictionarySpecBase.compiled(ctx, mdToken).dataLogic

  protected val dictValue: Long = Parameters.OneErg / 100L // 0.01 ERG, as the emission box
  protected val dataValue: Long = LFSMHelpers.MIN_DATA_BOX_AMNT
  protected val dataLifetime: Long = LFSMHelpers.DATA_LIFETIME
  protected val evictDelay: Long = LFSMHelpers.EVICT_DELAY
  protected val registerSlack: Long = LFSMHelpers.REGISTER_SLACK
  protected val nispWindow: Long = LFSMHelpers.NISP_WINDOW
  protected val rollupLifetime: Long = LFSMHelpers.ROLLUP_LIFETIME

  /** How wide a change must be spaced from the commitment it replaces. */
  protected val commitSpacing: Long = nispWindow + rollupLifetime

  // ─── the 40-byte dictionary entry ─────────────────────────────────────────

  protected val entrySize: Int = 40

  protected def entryBytes(credentialId: ErgoId, validUntil: Long): Array[Byte] =
    credentialId.getBytes ++ Longs.toByteArray(validUntil)

  protected def credentialOf(entry: Array[Byte]): Array[Byte] = entry.slice(0, 32)

  protected def validUntilOf(entry: Array[Byte]): Long = Longs.fromByteArray(entry.slice(32, 40))

  // ─── values and registers ─────────────────────────────────────────────────

  protected def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  /** R4 of a MinerData box: `Coll[(Int, Long)]`, index 0 newest. */
  protected def commitmentsValue(commitments: Seq[(Int, Long)]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(commitments.toArray), ErgoType.pairType(scalaIntType, scalaLongType))

  // ─── boxes ────────────────────────────────────────────────────────────────

  protected def dictionaryUTXO(ctx: BlockchainContext,
                               tree: Tree,
                               value: Long = dictValue,
                               token: ErgoId = mdToken): UTXO =
    UTXO(dictionaryContract(ctx), value, Seq(Token(token, 1L)), Seq(tree.ergoValue))

  protected def dataUTXO(ctx: BlockchainContext,
                         credentialId: ErgoId,
                         minerHash: Array[Byte],
                         commitments: Seq[(Int, Long)],
                         value: Long = dataValue,
                         creationHeight: Option[Int] = None,
                         contract: Contract = null): UTXO = {
    val box = UTXO(if (contract == null) dataContract(ctx) else contract, value,
      Seq(Token(credentialId, 1L)),
      Seq(commitmentsValue(commitments), bytesValue(minerHash)))
    creationHeight.map(box.setCreationHeight).getOrElse(box)
  }

  // ─── context vars ─────────────────────────────────────────────────────────

  protected def bytesVar(id: Byte, bytes: Array[Byte]): ContextVar =
    ContextVar.of(id, bytesValue(bytes))

  protected def opVar(id: Byte, op: Byte): ContextVar = ContextVar.of(id, ErgoValue.of(op))

  /**
   * The context extension a MinerData box carries on every path: the signer script the guard executes,
   * the op byte the logic dispatches on, and the logic itself.
   *
   * Attached in ascending id order, which for `0, 1, 64` is also the order appkit sends them in. Three
   * entries sits in the band where signing order and wire order can disagree, so this is load bearing.
   */
  protected def dataVars(ctx: BlockchainContext, signer: Contract, op: Byte): Seq[ContextVar] =
    Seq(
      bytesVar(0.toByte, signer.valueBytes),
      opVar(1.toByte, op),
      bytesVar(64.toByte, dataLogic(ctx).valueBytes))

  protected def dataInput(ctx: BlockchainContext,
                          box: UTXO,
                          signer: Contract,
                          op: Byte,
                          index: Int = 1): InputUTXO =
    inputAt(box, ctx, index).setCtxVars(dataVars(ctx, signer, op): _*)
}
