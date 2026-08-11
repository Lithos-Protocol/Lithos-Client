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

/**
 * The two node responses the wallet and collateral paths are built on, decoded from real captures.
 *
 * `LITHOS-CLIENT-RULES.MD` §4: an API codec written against a spec is unverified until a real
 * response exercises it. These are that exercise — captured from a live testnet node rather than
 * written from the openapi document, so a field the spec describes and the node does not emit (or
 * emits differently) fails here rather than in a build that silently drops a box.
 *
 * The registers on the byTokenId capture are the shape `WalletManager` and `EmissionTransactions`
 * both index into, which is why it matters that they survive the round trip rather than merely
 * parse.
 */
class NodeCodecSpec extends AnyFlatSpec with Matchers {

  private def load(name: String): Seq[JsonObject] = {
    val src = Source.fromResource(s"node/$name")
    val text = try src.mkString finally src.close()
    val arr = new JsonParser().parse(text).getAsJsonArray
    (0 until arr.size()).map(arr.get(_).getAsJsonObject)
  }

  private lazy val walletBoxes: Seq[WalletBox] = load("wallet_boxes_unspent.json").map(NodeCodecs.walletBox)
  private lazy val indexedBoxes: Seq[IndexedBox] = load("unspent_by_token_id.json").map(NodeCodecs.indexedBox)

  // ─── /wallet/boxes/unspent ────────────────────────────────────────────────

  "walletBox" should "decode every entry of a real response" in {
    walletBoxes should have size 5
    all(walletBoxes.map(_.boxId.length)) shouldEqual 64
    all(walletBoxes.map(_.spent)) shouldBe false
    all(walletBoxes.map(_.onchain)) shouldBe true
  }

  it should "carry the value and creation height selection depends on" in {
    // `dustFirstIrredundant` sorts on value and `spendableRewards` filters on creation height, so
    // both have to survive the decode. A zero from a missed field looks like a dust box.
    val byValue = walletBoxes.map(_.box.value).sorted
    byValue shouldEqual Seq(6000000L, 6000000L, 8000000L, 1999000003L, 2000000000L)
    all(walletBoxes.map(_.box.creationHeight)) should be > 0
  }

  it should "decode a box with no assets as an empty sequence, not a null" in {
    // The mixed-token selector calls `b.tokens.exists(...)` on every box in the pool.
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
    // One P2PK and one that is not. `loadAll` filters on exactly this against the prover's signable
    // set, so a decode that flattened them would hand out unspendable boxes.
    val trees = walletBoxes.map(_.box.ergoTree).distinct
    trees should have size 2
    trees.count(_.startsWith("0008cd")) shouldEqual 1
  }

  it should "convert into an InputUTXO the selectors can use" in {
    // The decode is only half the path; `toInputUTXO` is what selection actually consumes.
    OfflineContext.withCtx { ctx =>
      val utxos = walletBoxes.map(_.box.toInputUTXO(ctx))
      utxos.map(_.value).sum shouldEqual walletBoxes.map(_.box.value).sum
      all(utxos.map(_.id.toString.length)) shouldEqual 64
      utxos.count(_.tokens.nonEmpty) shouldEqual 1
    }
  }

  // ─── /blockchain/box/unspent/byTokenId/{id} ───────────────────────────────

  "indexedBox" should "decode a real collateral-shaped response" in {
    indexedBoxes should have size 1
    val b = indexedBoxes.head
    b.boxId shouldEqual "c3f5e5decb6e41144a153daf25de8da5a38add009620b33f06e7214ba35cd545"
    b.value shouldEqual 1000000L
    b.inclusionHeight shouldEqual 460278
    b.globalIndex shouldEqual 3518858L
  }

  it should "report an unspent box as unspent" in {
    // `spentTransactionId` is null in the capture. Decoding that as Some("") would make every live
    // box look consumed, and the collateral set would come back empty.
    indexedBoxes.head.spentTransactionId shouldBe None
    indexedBoxes.head.spendingHeight shouldBe None
    indexedBoxes.head.spendingProof shouldBe None
  }

  it should "keep the token order the identity checks rely on" in {
    // `carriesOne` looks at assets.head and the LIT amount at index 1. Order is load bearing: the
    // NFT identifies the box and the second entry is what it holds.
    val assets = indexedBoxes.head.assets
    assets should have size 2
    assets.head.amount shouldEqual 1L
    assets(1).amount shouldEqual 100L
    assets.map(_.tokenId).distinct should have size 2
  }

  it should "decode all six registers, in order" in {
    // R4-R9 present. The collateral and queue paths index registers positionally — R4 fee value,
    // R5 lender, R7 queue position — so a decode that dropped or reordered one would build a
    // transaction against the wrong fields and be rejected on chain.
    val regs = indexedBoxes.head.box.additionalRegisters
    regs.values should have size 6
    (4 to 9).foreach { i =>
      withClue(s"R$i: ") { regs.get(i) should not be empty }
    }
    regs.get(8) shouldEqual Some("0400")
    regs.isEmpty shouldBe false
  }

  it should "expose a contiguous `ordered` view, which is what the box filters count" in {
    // `loadCollateral` requires `ordered.size >= 5` and `configBox` `>= 4`. `ordered` takes while
    // defined from R4, so a gap silently truncates it — a box with R4 and R6 but no R5 counts as
    // one register, not two, and is filtered out rather than mis-parsed. That is the safe
    // direction, and it is worth pinning that it behaves that way.
    val regs = indexedBoxes.head.box.additionalRegisters
    regs.ordered should have size 6
    regs.ordered.head shouldEqual regs.get(4).get
    regs.ordered.last shouldEqual regs.get(9).get

    val gapped = node.model.NodeRegisters(Map("R4" -> "0400", "R6" -> "0402"))
    gapped.ordered should have size 1
  }

  it should "survive conversion into an InputUTXO with its registers intact" in {
    // This is the conversion `loadCollateral` and `queueBoxAt` both run before anything reads a
    // register, so an ordering or count change shows up here rather than as a rejected genesis
    // transaction.
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
    // Every identity check in the client compares this hex against a compiled contract's
    // `ergoTreeHex`, so it has to come back as lowercase hex of the serialised tree.
    val tree = indexedBoxes.head.ergoTree
    tree should fullyMatch regex "[0-9a-f]+"
    tree.length % 2 shouldEqual 0
  }

  // ─── the shape assumptions the client makes about these ───────────────────

  "A box carrying a single NFT" should "satisfy the carriesOne shape the client filters on" in {
    // `carriesOne` is `assets.headOption.exists(a => a.tokenId == id && a.amount == 1L)`. The
    // capture's first asset is an amount-1 NFT, which is the shape every collateral, queue and
    // emission lookup depends on.
    val first = indexedBoxes.head.assets.head
    first.amount shouldEqual 1L
    LFSMHelpers.COLLAT_TOKEN.toString.length shouldEqual first.tokenId.length
  }
}
