package transactions.rent

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsValue, Json}
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, TxInput, TxOutput}
import work.lithos.mutations.Token

/**
 * Which rent claims in a block are reported as the node's own, paying the lender.
 */
class RentClaimWatchSpec extends AnyFlatSpec with Matchers {

  private val fixture: JsValue = Json.parse(
    scala.io.Source.fromResource("rent/node-claim-1924820.json").mkString)

  private def output(js: JsValue): TxOutput = TxOutput(
    id = (js \ "boxId").as[String],
    value = (js \ "value").as[Long],
    ergoTree = (js \ "ergoTree").as[String],
    registers = (js \ "registers").as[Seq[String]],
    assets = (js \ "assets").as[Seq[JsValue]].map(a =>
      Token(ErgoId.create((a \ "tokenId").as[String]), (a \ "amount").as[Long])),
    txId = (js \ "transactionId").as[String],
    creationHeight = (js \ "creationHeight").as[Int],
    index = (js \ "index").as[Int])

  private val txs: Seq[BlockTx] = (fixture \ "transactions").as[Seq[JsValue]].map { tx =>
    BlockTx((tx \ "id").as[String],
      (tx \ "inputs").as[Seq[JsValue]].map(in => TxInput((in \ "boxId").as[String],
        Some(InputSpendingProof((in \ "extension").as[Map[String, String]])))),
      Seq.empty,
      (tx \ "outputs").as[Seq[JsValue]].map(output))
  }

  private val resolved: Map[String, TxOutput] =
    (fixture \ "resolvedInputs").as[Seq[JsValue]].map(output).map(o => o.id -> o).toMap

  private def block(transactions: Seq[BlockTx]): BlockInfo =
    BlockInfo((fixture \ "blockId").as[String], (fixture \ "height").as[Int], transactions,
      resolvedInputs = resolved)

  private val genesis = txs.head
  private val holdingTree = genesis.outputs.head.ergoTree
  private val finderR8 = genesis.outputs.head.registers(4)
  private val finder = RentClaimWatch.Identity(holdingTree, finderR8)
  private val lenderTree = "0008cd" + (fixture \ "minerPk").as[String]

  /** The identity check compares against this client's compiled holding script. */
  "The holding script this client compiles" should "be the one mainnet's genesis pays into" in {
    transactions.ProtocolContracts.forNetwork(NetworkType.MAINNET).holding.ergoTreeHex shouldBe holdingTree
  }

  "Block 1,924,820" should "report the node's claim to its finder, and nothing else" in {
    val claims = RentClaimWatch.nodeClaims(block(txs), finder)
    claims should have size 1
    claims.head.txId should startWith("d13f5199")
    claims.head.boxes shouldBe 17
    claims.head.nanoErg shouldBe 1678750000L
  }

  it should "report nothing to a miner that did not find it" in {
    RentClaimWatch.nodeClaims(block(txs), finder.copy(finderR8 = "0e20" + "00" * 32)) shouldBe empty
  }

  /**
   * This client's sweep puts its proceeds first and its recreations after.
   */
  "This client's own sweep" should "not be reported even when both its ends sit at the lender's key" in {
    val claim = txs.find(_.id.startsWith("d13f5199")).get
    val recreations = claim.outputs.init.zipWithIndex.map { case (o, i) =>
      o.copy(index = i + 1, ergoTree = if (i == claim.outputs.size - 2) lenderTree else o.ergoTree)
    }
    // Recreations name outputs 1.. and the proceeds output 0 is unnamed, as `StorageRent.assembled` lays out.
    val ours = BlockTx("ee" * 32,
      claim.inputs.zipWithIndex.map { case (in, i) => TxInput(in.id, Some(InputSpendingProof(
        Map("127" -> f"03${(i + 1) * 2}%02x")))) },
      Seq.empty,
      claim.outputs.last.copy(index = 0) +: recreations)
    ours.outputs.head.ergoTree shouldBe lenderTree
    ours.outputs.last.ergoTree shouldBe lenderTree
    RentClaimWatch.nodeClaims(block(Seq(genesis, ours)), finder) shouldBe empty
  }

  "The storage index" should "decode what var 127 carries" in {
    RentClaimWatch.storageIndex("0300") shouldBe Some(0)
    RentClaimWatch.storageIndex("0302") shouldBe Some(1)
    RentClaimWatch.storageIndex("0320") shouldBe Some(16)
    RentClaimWatch.storageIndex("03c801") shouldBe Some(100)
    RentClaimWatch.storageIndex("0400") shouldBe None
    RentClaimWatch.storageIndex("03") shouldBe None
    RentClaimWatch.storageIndex("zz") shouldBe None
  }
}
