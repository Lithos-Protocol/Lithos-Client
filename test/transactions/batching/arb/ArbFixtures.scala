package transactions.batching.arb

import lithosdex.LDHelpers
import mutations.NodeWallet
import node.MutationConversions._
import node.model.NodeBox
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.ast.ErgoTree
import support.LDNodeFixtures
import transactions.batching.ergodex.{ErgoDexContracts, ErgoDexPool}
import transactions.batching.lithosdex.{DexContracts, LDDeployments}
import work.lithos.mutations.{Contract, Token, UTXO}

import scala.io.Source

/**
 * Pools for cycle specs, every one at a real pool contract so a leg through it signs only if that contract
 * accepts it. ErgoDEX pools are built at the shared native and token pool scripts, which name no ids, under
 * made-up tokens. The LithosDex ones are the canonical ERG:LIT pool at the production deployment and the
 * recorded LIT/RSN token/token pool `5495…`, whose X is LIT and Y is RSN.
 */
object ArbFixtures {

  final val Erg = 1000000000L

  /** A 32-byte id made from `seed`, for tokens and NFTs no contract names. */
  def id(seed: Int): String = f"$seed%064x"

  val Lit: String = LDHelpers.getTokenY(NetworkType.MAINNET).toString
  val CanonicalNft: String = LDHelpers.getPoolNFT(NetworkType.MAINNET).toString

  /** LP held by a made-up ErgoDEX pool: nearly all of it, as in a real one. */
  private val LpHeld = Long.MaxValue - 1000000000000L

  private val nativeTree = Contract(ErgoTree.fromHex(ErgoDexContracts.NativePoolErgoTree))
  private val tokenTree = Contract(ErgoTree.fromHex(ErgoDexContracts.TokenPoolErgoTree))

  private def lpOf(nft: String): ErgoId = ErgoId.create("ee" + nft.drop(2))

  /** An ErgoDEX ERG/token pool under `nft`, holding `erg` and `tokens` of `token`, at fee `fee` over 1,000. */
  def nativePool(ctx: BlockchainContext, nft: String, token: String, erg: Long, tokens: Long,
                 fee: Int = 997): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(nativeTree, erg,
      Seq(Token(ErgoId.create(nft), 1L), Token(lpOf(nft), LpHeld), Token(ErgoId.create(token), tokens)),
      Seq(ErgoValue.of(fee))), 0, nft)

  /** An ErgoDEX token/token pool under `nft`: X then Y, with storage ERG no swap moves. */
  def tokenPool(ctx: BlockchainContext, nft: String, x: String, y: String, xAmount: Long, yAmount: Long,
                fee: Int = 997): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(tokenTree, 10000000L,
      Seq(Token(ErgoId.create(nft), 1L), Token(lpOf(nft), LpHeld), Token(ErgoId.create(x), xAmount),
        Token(ErgoId.create(y), yAmount)),
      Seq(ErgoValue.of(fee))), 0, nft)

  /** The canonical ERG:LIT pool with these reserves. */
  def canonicalPool(ctx: BlockchainContext, erg: Long, lit: Long): NodeBox =
    LDNodeFixtures.poolBox(ctx, erg, lit, txId = "c1" * 32)

  /** The recorded LIT/RSN token/token pool, as it stood before the trade `ld-t2t-x-in` records. */
  lazy val litRsnPool: NodeBox = {
    val source = Source.fromResource("arb/ld-t2t-x-in.json")
    try NodeCodecs.box(new com.google.gson.JsonParser().parse(source.mkString).getAsJsonObject.getAsJsonObject("before"))
    finally source.close()
  }

  lazy val Rsn: String = litRsnPool.assets(2).tokenId

  /** A plain wallet box of `value`, as the wallet would hand it out as the stake. */
  def stakeBox(ctx: BlockchainContext, wallet: NodeWallet, value: Long, seed: Int = 7): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, value), 0, id(seed))

  /** `box` as discovery would read it. */
  def entry(ctx: BlockchainContext, box: NodeBox): ArbPoolEntry = {
    val assets = box.assets.map(a => a.tokenId -> a.amount)
    if (box.ergoTree == ErgoDexContracts.NativePoolErgoTree || box.ergoTree == ErgoDexContracts.TokenPoolErgoTree)
      ArbPoolEntry(ErgoDexArbPool(ErgoDexPool.parse(box).get), box)
    else if (box.ergoTree.equalsIgnoreCase(DexContracts(ctx).liquidityPool.ergoTreeHex))
      ArbPoolEntry(LithosDexArbPool(box.toInputUTXO(ctx), DexContracts(ctx)), box)
    else LDDeployments.verify(NetworkType.MAINNET, box.ergoTree, assets)
      .map(contracts => ArbPoolEntry(LithosDexArbPool(box.toInputUTXO(ctx), contracts), box))
      .getOrElse(ArbPoolEntry(LithosDexT2TArbPool(box.toInputUTXO(ctx), LDDeployments.verifyT2T(box.ergoTree, assets).get), box))
  }

  /**
   * BBC's ErgoDEX ERG pool and its LithosDex ERG pool as mainnet held them at height 1,909,293. The LithosDex box
   * holds 10,000,001 nanoERG, one over its contract's floor, so no swap can take ERG out of it.
   */
  lazy val bbcPools: (NodeBox, NodeBox) = {
    val source = Source.fromResource("arb/bbc-pools.json")
    try {
      val doc = new com.google.gson.JsonParser().parse(source.mkString).getAsJsonObject
      NodeCodecs.box(doc.getAsJsonObject("ergodex")) -> NodeCodecs.box(doc.getAsJsonObject("lithosdex"))
    } finally source.close()
  }

  /** The index discovery would build at `height` over `boxes`. */
  def index(ctx: BlockchainContext, boxes: Seq[NodeBox], height: Int = 1908200): ArbIndex =
    ArbDiscovery.index(height, boxes.map(entry(ctx, _)))

  /** The pool states an index starts from, by NFT. */
  def states(index: ArbIndex): Map[String, ArbPool] = index.pools.map { case (nft, e) => nft -> e.pool }
}
