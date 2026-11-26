package transactions.batching.arb

import com.google.gson.{JsonObject, JsonParser}
import ergodexcl.ErgoDexClContracts
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import node.rest.NodeCodecs
import org.ergoplatform.appkit.scalaapi.scalaLongType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import sigma.ast.ErgoTree
import support.LDNodeFixtures
import utils.BoxMinimum
import work.lithos.mutations.{Contract, Token, UTXO}

import scala.collection.JavaConverters._
import scala.io.Source

/**
 * ErgoDEX CL launch fixtures: the 80 mainnet transactions that touch a position at the launch position script, from
 * 1,913,316 to 1,923,141, in a compact form (boxes as `id`, `v`, `a`, `r`, `c`, `tree`; inputs carry their context
 * extension as `x`), and the config box every swap in them read, unspent since 1,913,306.
 */
object ClFixtures {

  private def resource(name: String): String = {
    val source = Source.fromResource(s"arb/ergodex-cl-launch/$name")
    try source.mkString finally source.close()
  }

  /** Each transaction with the height it was mined at, in chain order. */
  lazy val spends: Vector[(Int, JsonObject)] =
    new JsonParser().parse(resource("position-spends.json")).getAsJsonArray.asScala.toVector.map { e =>
      val o = e.getAsJsonObject
      o.get("height").getAsInt -> o.getAsJsonObject("tx")
    }

  /** The position script as read from chain. */
  lazy val positionTreeHex: String = resource("position-tree.hex").trim

  /** The config box, as the node reports it. */
  lazy val configBox: NodeBox = NodeCodecs.box(new JsonParser().parse(resource("config-box.json")).getAsJsonObject)

  /** The config as a leg reads it, fee boxes sized at the context's rate. */
  def config(ctx: BlockchainContext, box: NodeBox = configBox): ClConfig =
    ClConfig.read(ctx, box, BoxMinimum.perByte(ctx)).get

  /** A compact box of transaction `txId` at `index`; an input's creating transaction is not recorded, so its id stays as read. */
  def box(o: JsonObject, txId: String, index: Int): NodeBox = NodeBox(
    boxId = o.get("id").getAsString,
    transactionId = txId,
    value = o.get("v").getAsLong,
    index = index,
    creationHeight = o.get("c").getAsInt,
    ergoTree = o.get("tree").getAsString,
    assets = o.getAsJsonArray("a").asScala.toSeq.map { a =>
      val pair = a.getAsJsonArray
      NodeAsset(pair.get(0).getAsString, pair.get(1).getAsLong)
    },
    additionalRegisters = Option(o.getAsJsonObject("r")).fold(NodeRegisters.empty)(r =>
      NodeRegisters(r.entrySet().asScala.map(e => e.getKey -> e.getValue.getAsString).toMap)))

  def inputs(tx: JsonObject): Vector[JsonObject] = tx.getAsJsonArray("i").asScala.toVector.map(_.getAsJsonObject)

  def outputs(tx: JsonObject): Vector[NodeBox] =
    tx.getAsJsonArray("o").asScala.toVector.zipWithIndex.map { case (o, k) => box(o.getAsJsonObject, tx.get("id").getAsString, k) }

  /** An input's context variables by id, as serialized constants. */
  def extension(input: JsonObject): Map[String, String] =
    Option(input.getAsJsonObject("x")).fold(Map.empty[String, String])(_.entrySet().asScala
      .map(e => e.getKey -> e.getValue.getAsString).toMap)

  /** Output `index` of the transaction whose id starts with `txPrefix`: its id rebuilds from its contents. */
  def output(txPrefix: String, index: Int): NodeBox =
    outputs(spends.map(_._2).find(_.get("id").getAsString.startsWith(txPrefix)).get)(index)

  /** ERG/SigRSV at a 1% LP fee, 505.66 ERG and 4,746,294 SigRSV, as it stood after 1,923,141. */
  def ergRsv: NodeBox = output("865e8c80", 1)

  /** A token/token position holding 1,000,000 nanoERG, as it stood after 1,921,797. */
  def tokenPair: NodeBox = output("5238d22f", 1)

  /** ERG/CRUX at 0.3%, drained to ERG: none of its token left to sell. */
  def drained: NodeBox = output("a16d39a9", 1)

  /** A position at the pinned script under `nft`: ERG/`token` with `erg` net ERG, or token/token with `xToken`. */
  def position(ctx: BlockchainContext, nft: String, y: (String, Long), a: Long, b: Long, feePpm: Long,
               erg: Long = 0L, xToken: Option[(String, Long)] = None, fees: (Long, Long) = (0L, 0L),
               txId: String = ""): NodeBox = {
    val (yId, yNet) = y
    val value = xToken.fold(ErgoDexClArbPool.LockedErg + erg + fees._1)(_ => 1000000L)
    val tokens = Token(ErgoId.create(nft), 1L) +:
      (xToken.map { case (id, n) => Token(ErgoId.create(id), n + 1L + fees._1) }.toSeq :+
        Token(ErgoId.create(yId), yNet + 1L + fees._2))
    LDNodeFixtures.nodeBox(ctx, UTXO(Contract(ErgoTree.fromHex(ErgoDexClContracts.PositionErgoTree)), value, tokens,
      Seq(longs(a, b, feePpm), longs(fees._1, fees._2))), 0, if (txId.isEmpty) nft else txId)
  }

  /** `configBox` with R5 and R8 replaced where given, held as output 0 of `txId`. */
  def configWith(ctx: BlockchainContext, txId: String, r5: Option[Seq[Long]] = None,
                 r8: Option[String] = None): NodeBox = {
    val values = configBox.additionalRegisters.values ++
      r5.map(v => "R5" -> longs(v: _*).toHex) ++ r8.map(hex => "R8" -> hex)
    val changed = configBox.copy(additionalRegisters = NodeRegisters(values))
    import node.MutationConversions._
    LDNodeFixtures.nodeBox(ctx, changed.toUTXO, 0, txId)
  }

  def longs(v: Long*): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v.toArray), scalaLongType)
}
