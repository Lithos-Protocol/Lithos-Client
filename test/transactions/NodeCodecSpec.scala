package transactions

import com.google.gson.{JsonObject, JsonParser}
import lfsm.LFSMHelpers
import node.model.{IndexedBox, WalletBox}
import node.rest.NodeCodecs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.OfflineContext
import node.MutationConversions._

import scala.io.Source

/** Verifies node codecs against captured wallet, box-index, and block-index responses. */
class NodeCodecSpec extends AnyFlatSpec with Matchers {

  private def load(name: String): Seq[JsonObject] = {
    val src = Source.fromResource(s"node/$name")
    val text = try src.mkString finally src.close()
    val arr = new JsonParser().parse(text).getAsJsonArray
    (0 until arr.size()).map(arr.get(_).getAsJsonObject)
  }

  private lazy val walletBoxes: Seq[WalletBox] = load("wallet_boxes_unspent.json").map(NodeCodecs.walletBox)
  private lazy val indexedBoxes: Seq[IndexedBox] = load("unspent_by_token_id.json").map(NodeCodecs.indexedBox)

  // /wallet/boxes/unspent

  "walletBox" should "decode every entry of a real response" in {
    walletBoxes should have size 5
    all(walletBoxes.map(_.boxId.length)) shouldEqual 64
    all(walletBoxes.map(_.spent)) shouldBe false
    all(walletBoxes.map(_.onchain)) shouldBe true
  }

  it should "carry the value and creation height selection depends on" in {
    // Wallet selection depends on both decoded values and creation heights.
    val byValue = walletBoxes.map(_.box.value).sorted
    byValue shouldEqual Seq(6000000L, 6000000L, 8000000L, 1999000003L, 2000000000L)
    all(walletBoxes.map(_.box.creationHeight)) should be > 0
  }

  it should "decode a box with no assets as an empty sequence, not a null" in {
    // Asset-free boxes must decode to an iterable empty collection.
    val ergOnly = walletBoxes.filter(_.box.assets.isEmpty)
    ergOnly should have size 4
    all(ergOnly.map(_.box.assets)) shouldBe empty
  }

  it should "decode the token on the one box that carries one" in {
    val withToken = walletBoxes.filter(_.box.assets.nonEmpty)
    withToken should have size 1
    withToken.head.box.assets.head.tokenId shouldEqual
      "4f2ad850df6d8006af630a84201d57696e3ba9b3b3b49e1f865ff6051dbbcd8a"
    withToken.head.box.assets.head.amount shouldEqual 1000L
  }

  it should "distinguish the two ergoTrees in the response" in {
    // Wallet loading filters boxes by exact signable script.
    val trees = walletBoxes.map(_.box.ergoTree).distinct
    trees should have size 2
    trees.count(_.startsWith("0008cd")) shouldEqual 1
  }

  it should "convert into an InputUTXO the selectors can use" in {
    // Exercise the representation consumed by input selection.
    OfflineContext.withCtx { ctx =>
      val utxos = walletBoxes.map(_.box.toInputUTXO(ctx))
      utxos.map(_.value).sum shouldEqual walletBoxes.map(_.box.value).sum
      all(utxos.map(_.id.toString.length)) shouldEqual 64
      utxos.count(_.tokens.nonEmpty) shouldEqual 1
    }
  }

  // /blockchain/box/unspent/byTokenId/{id}

  "indexedBox" should "decode a real collateral-shaped response" in {
    indexedBoxes should have size 1
    val b = indexedBoxes.head
    b.boxId shouldEqual "c3f5e5decb6e41144a153daf25de8da5a38add009620b33f06e7214ba35cd545"
    b.value shouldEqual 1000000L
    b.inclusionHeight shouldEqual 460278
    b.globalIndex shouldEqual 3518858L
  }

  it should "report an unspent box as unspent" in {
    // Null spend fields must remain absent for an unspent box.
    indexedBoxes.head.spentTransactionId shouldBe None
    indexedBoxes.head.spendingHeight shouldBe None
    indexedBoxes.head.spendingProof shouldBe None
  }

  it should "keep the token order the identity checks rely on" in {
    // Identity checks require the NFT first and the fungible token second.
    val assets = indexedBoxes.head.assets
    assets should have size 2
    assets.head.amount shouldEqual 1L
    assets(1).amount shouldEqual 100L
    assets.map(_.tokenId).distinct should have size 2
  }

  it should "decode all six registers, in order" in {
    // Collateral and queue logic read R4-R9 positionally.
    val regs = indexedBoxes.head.box.additionalRegisters
    regs.values should have size 6
    (4 to 9).foreach { i =>
      withClue(s"R$i: ") { regs.get(i) should not be empty }
    }
    regs.get(8) shouldEqual Some("0400")
    regs.isEmpty shouldBe false
  }

  it should "expose a contiguous `ordered` view, which is what the box filters count" in {
    // The ordered view stops at the first register gap so malformed boxes are filtered out.
    val regs = indexedBoxes.head.box.additionalRegisters
    regs.ordered should have size 6
    regs.ordered.head shouldEqual regs.get(4).get
    regs.ordered.last shouldEqual regs.get(9).get

    val gapped = node.model.NodeRegisters(Map("R4" -> "0400", "R6" -> "0402"))
    gapped.ordered should have size 1
  }

  it should "survive conversion into an InputUTXO with its registers intact" in {
    // Collateral and queue paths read registers after this conversion.
    OfflineContext.withCtx { ctx =>
      val utxo = indexedBoxes.head.toInputUTXO(ctx)
      utxo.value shouldEqual 1000000L
      utxo.tokens should have size 2
      utxo.tokens.head.amount shouldEqual 1L
      utxo.registers.size should be >= 5   // the register-count guard in loadCollateral
      utxo.id.toString shouldEqual indexedBoxes.head.boxId
    }
  }

  it should "expose an ergoTree that a script comparison can be made against" in {
    // Script identity checks compare this value with compiled `ergoTreeHex`.
    val tree = indexedBoxes.head.ergoTree
    tree should fullyMatch regex "[0-9a-f]+"
    tree.length % 2 shouldEqual 0
  }

  // Indexed-box shape requirements

  "A box carrying a single NFT" should "satisfy the carriesOne shape the client filters on" in {
    // Collateral, queue, and emission lookups identify boxes by a leading amount-one NFT.
    val first = indexedBoxes.head.assets.head
    first.amount shouldEqual 1L
    LFSMHelpers.COLLAT_TOKEN.toString.length shouldEqual first.tokenId.length
  }

  // /blockchain/block/byHeaderIds

  private lazy val indexedBlocks: Seq[node.model.IndexedBlock] =
    load("indexed_blocks_by_header_ids.json").map(NodeCodecs.indexedBlock)

  /** Ensures indexed blocks decode their top-level `transactions` array. */
  "indexedBlock" should "decode the transactions of a real byHeaderIds response" in {
    indexedBlocks should have size 2
    indexedBlocks.map(_.header.height) shouldEqual Seq(500001, 500002)
    all(indexedBlocks.map(_.blockTransactions.size)) should be > 0
  }

  /** Ensures indexed inputs retain the boxes required for genesis authentication. */
  it should "carry full input boxes, which is what genesis authentication reads" in {
    val inputs = indexedBlocks.flatMap(_.blockTransactions).flatMap(_.inputs)
    inputs should not be empty
    all(inputs.map(_.boxId.length)) should be > 0
    all(inputs.map(_.ergoTree.length)) should be > 0
    // Genesis authentication reads collateral tokens from resolved inputs.
    inputs.foreach(_.assets.foreach(_.amount should be >= 0L))
  }

  it should "resolve every input into the block view the reducer receives" in {
    val block = state.messages.NodeSync.blockInfo(indexedBlocks.head)
    val spent = block.txs.flatMap(_.inputs).map(_.id)
    spent should not be empty
    spent.foreach(id => block.inputBox(id) should be(defined))
  }
}
