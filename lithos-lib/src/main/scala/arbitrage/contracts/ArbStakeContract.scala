package arbitrage.contracts

import lfsm.ScriptGenerator
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ConstantsBuilder, NetworkType}
import sigma.ast.ErgoTree
import sigma.crypto.CryptoConstants
import sigma.data.{CSigmaProp, ProveDlog}
import work.lithos.mutations.{Contract, Mutator}

/**
 * The arb stake script: a miner's key and nothing else, at a tree that is not a plain key's, so no wallet lists
 * a box held there. One tree per key, read from a tree compiled once with a stand-in key; nothing here compiles
 * at runtime.
 */
object ArbStakeContract {

  final val ScriptName = "ArbStake"

  /** The script's one term, the owner's key. */
  final val OWNER = "CONST_OWNER"

  /** Hex of a compressed group element, the whole of a key in a tree. */
  private final val KeyHexLength = 66

  /** The key the pinned tree was compiled with: the group generator. */
  private val sentinel: ProveDlog = ProveDlog(CryptoConstants.dlogGroup.generator)
  private val sentinelHex: String = Hex.toHexString(sentinel.pkBytes)

  /** `ArbStake.ergo` compiled with the stand-in key. */
  final val PinnedTreeHex =
    "1b2d0208cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980101ea027300d17301"

  // The key is the tree's first constant, so every stake tree is these two pieces around its owner's key
  private val keyAt: Int = PinnedTreeHex.indexOf(sentinelHex)
  require(keyAt > 0 && PinnedTreeHex.lastIndexOf(sentinelHex) == keyAt, "the pinned arb stake tree holds one key")
  private val prefix: String = PinnedTreeHex.take(keyAt)
  private val suffix: String = PinnedTreeHex.drop(keyAt + KeyHexLength)

  /** A plain key's tree: header 0x00, then a constant sigma proposition holding one group element. */
  private final val P2PKPrefix = "0008cd"

  /** The stake tree owned by the key whose compressed bytes are `keyHex`. */
  def treeHexFor(keyHex: String): String = {
    require(keyHex.length == KeyHexLength && keyHex.matches("0[23][0-9a-f]{64}"), s"'$keyHex' is not a public key")
    prefix + keyHex + suffix
  }

  /** The stake contract `owner` alone can spend. */
  def forKey(owner: ProveDlog): Contract = Contract(ErgoTree.fromHex(treeHexFor(Hex.toHexString(owner.pkBytes))))

  /** The owner's key, as hex, when `treeHex` is a stake tree. */
  def ownerOf(treeHex: String): Option[String] =
    if (treeHex.length == PinnedTreeHex.length && treeHex.startsWith(prefix) && treeHex.endsWith(suffix))
      Some(treeHex.substring(keyAt, keyAt + KeyHexLength)).filter(_.matches("0[23][0-9a-f]{64}"))
    else None

  def isStake(treeHex: String): Boolean = ownerOf(treeHex).isDefined

  /** The plain-key tree of a stake tree's owner. */
  def ownerP2PK(treeHex: String): Option[String] = ownerOf(treeHex).map(P2PKPrefix + _)

  /** The stake tree of the key a plain-key tree holds. */
  def forP2PK(p2pkTreeHex: String): Option[String] =
    if (p2pkTreeHex.length == P2PKPrefix.length + KeyHexLength && p2pkTreeHex.startsWith(P2PKPrefix))
      Some(p2pkTreeHex.drop(P2PKPrefix.length)).filter(_.matches("0[23][0-9a-f]{64}")).map(treeHexFor)
    else None

  /**
   * Compiles `ArbStake.ergo` with the stand-in key, which is what the pinned tree must equal. For the drift spec
   * and for re-pinning only: compiling a script twice in one JVM can throw.
   */
  def compileSource(): ErgoTree =
    Contract.fromErgoScript(NetworkType.MAINNET, ConstantsBuilder.create().item(OWNER, CSigmaProp(sentinel)).build(),
      ScriptGenerator.mkArbitrageScript(ScriptName), Seq.empty[Mutator]).ergoTree
}
