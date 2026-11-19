package transactions.batching.sigmafi.zc

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}

/**
 * The pinned ZC trees against Sigma-Finance-V2's published reference at the pinned commit. Every value below is
 * copied from its `docs/ZC_STANDARD_REFERENCE.md`; a repin copies the new `contracts.tsv` and these tables
 * together. A tree that moves without its literal moving is a contract this client would mistake for another.
 */
class ZcContractsSpec extends AnyFlatSpec with Matchers {

  import ZcMarket.{Erg, SigUsd}

  private val trees: Seq[(ZcKind, Option[ZcMarket], String, String)] = Seq(
    (ZcKind.BondVault, None, "cb57a165ecf3965c5f1b5efe152d03b71e4d3b29f86dc06f669024bc23a62230", "bf0e24eba9bf9fd6b18fd917fbd2da9c2bebda32de99ff3d9da5edb366e64686"),
    (ZcKind.FractionalVault, None, "b30f86de09e3790ef04baea6fc0b4fa64f2e0fee7d8f0293772c5404f35db129", "ca59788f7da0aa81a858de10b5b5d4f4244f0dc86c994d1c81bb7ebdbb3e03de"),
    (ZcKind.FractionalMint, None, "8dd62428770e639a323ff4a31b05f5649b7f261756e4446ed0a266463af030f3", "cc52aadf4d985c832d5272bc5dac6cb63a82825571feab204f75c79c08d81655"),
    (ZcKind.DutchAuctionVault, None, "cf1ec765a640213aba46dd9b6970976c5fdf5639dba20923e14e3410a1937244", "10213eef250efcb9285586de46921397adf8c05638ececb1c4c77c89cb56dbe7"),
    (ZcKind.WrapVault, None, "181c3ac1ff619d82515c5af3dc93af5360e0ef30e4abab95406537b8d4d38177", "80cb3ac721bcd186fa72dc029feefd8daa76a60a44078f24f8623776554237d0"),
    (ZcKind.WrapMint, None, "893c20eedb43e82ebf05f0f345cec7acb4d47c33b7dbf556fad27b2d6ac2595e", "b2f5569cea01a13d249e23612ae3b6bc224c390b0d48fd2c0573456b1510cb1f"),
    (ZcKind.Bond, Some(Erg), "db4b0a884a94013ae8913ba68193a5e8d608681384986f285ddcd692ded0d2d8", "69f2c17fb98adada1f454dbcbf06c200ea6a52f8586905653411643e25ba5300"),
    (ZcKind.Order, Some(Erg), "7319d94bcacacc0eb7afad1cf5c26bc188f1e2c18b54c02ef745f2a6e354704a", "2bd49c8c8602085776b854e106f27115387a8f0020ba6c0332211ea5a222f69a"),
    (ZcKind.LendOffer, Some(Erg), "bb37c9110b8d5ca17a43c03966a602c8c3954e4ab8eb75840a51ef5c132eaf35", "73daef976f7574a8fd350bd804f593eea14577faa6747959aad6efdf3f00b686"),
    (ZcKind.SaleOrder, Some(Erg), "0c110c3f7566607dc6a05864904b2de7eeee5336c7b63f5164bd7e51361567be", "fffc798bf6a2ff9d42edde434a74230837dfefa6398a061f3b4e03d20df4bee9"),
    (ZcKind.PricedAsk, Some(Erg), "740b7956e8e398e2e65ec5656eefc20124a3c7621dd27bbb35c9ee2bdf76db81", "b73cef9ea01cb21153b36ba353139fd5975821f02320dfc609f019067e8ff310"),
    (ZcKind.DutchAuctionMint, Some(Erg), "06399c19b5e781954bfba36e3658fd0005725f1061dc893ed4de20fd960d466d", "ebe63a2ac4a1e47ff61772392b6f07de7c86970e9730dbc4e83909c1ec5aaf01"),
    (ZcKind.PricedOrder, Some(Erg), "0eec0d4e92ac849b953d92e4aefb834ecb399739aae97430ee933a4caaad76e8", "87869835ff460abd4b0cca45d8052b7d7e7084dedf1f033b133edef7b41e7e10"),
    (ZcKind.LotAuction, Some(Erg), "40f05633d284007d788afaff8e59ffc41dd3ae4e865310cc7277047d8b84313d", "5dbab7cde52f40ba7254646d45523019a7b7e96688ad17ee08751afeae441bc0"),
    (ZcKind.Bond, Some(SigUsd), "b49a6abbeb9ee1adf9f88b9b1b984f9a447ac748006561a96056648b7494968f", "69f2c17fb98adada1f454dbcbf06c200ea6a52f8586905653411643e25ba5300"),
    (ZcKind.Order, Some(SigUsd), "00cba01f3c756bf3a53fc1c7f3d5b166c52fe1bbabc75590f77b8db4a88bcb4c", "2bd49c8c8602085776b854e106f27115387a8f0020ba6c0332211ea5a222f69a"),
    (ZcKind.LendOffer, Some(SigUsd), "1543ce71df4ddc422fdcf4ebc30ccc744ca19ec59351989bac20c08237cdd4fc", "73daef976f7574a8fd350bd804f593eea14577faa6747959aad6efdf3f00b686"),
    (ZcKind.SaleOrder, Some(SigUsd), "651bd9f9a3b1a2599f551e809e48ce35b0bb411909b371ffadeefb1ca2aabcb3", "fffc798bf6a2ff9d42edde434a74230837dfefa6398a061f3b4e03d20df4bee9"),
    (ZcKind.PricedAsk, Some(SigUsd), "a85a8dc57738c1a1d7c8b68b91829c0c5e541b3acfa9d1fc2aad1d548a0b2525", "b73cef9ea01cb21153b36ba353139fd5975821f02320dfc609f019067e8ff310"),
    (ZcKind.DutchAuctionMint, Some(SigUsd), "b045f9608633a1ac066a6532149c7f3da16a354ecd89ac66f1b489ba7918e474", "ebe63a2ac4a1e47ff61772392b6f07de7c86970e9730dbc4e83909c1ec5aaf01"),
    (ZcKind.PricedOrder, Some(SigUsd), "11fc799eb01d23a5bc3ad183e3b3ee56dec98c158d7cdef58d61c8720dc8b971", "87869835ff460abd4b0cca45d8052b7d7e7084dedf1f033b133edef7b41e7e10"),
    (ZcKind.LotAuction, Some(SigUsd), "fcf2ea6ec34bd11288a881f62a3be40f5b3088edc28f0b63255684436ff2b3da", "5dbab7cde52f40ba7254646d45523019a7b7e96688ad17ee08751afeae441bc0"))

  private val expressions: Seq[(ZcExpression, Option[ZcMarket], String)] = Seq(
    (ZcExpression.MaturityLiquidation, None, "caa76cff5dc32ccc9546daa3025f4a051690d5970528ce9198b63fd3a76ba8fb"),
    (ZcExpression.OracleLiquidation, Some(Erg), "bd55b5c986032ad2ad4abe9eb98b8864584b772b8e44a2fc97c35f6f91d552d2"),
    (ZcExpression.CollateralPolicy, Some(Erg), "46e567ce0f55faeba92af61563ff1901bcbf94df6e06fdba4e5deb525ae8d1a5"),
    (ZcExpression.FillGuard, Some(Erg), "ec271fdf4ba21aac5fa69cf39ddd4d84b3956b972b555cd4cdecb5ef111577d2"),
    (ZcExpression.YieldOrderPricing, Some(Erg), "b801b8bb90eee4d58d64810d77e25489e0f44d0a3dc4acfa2d17dd08c6dbb358"),
    (ZcExpression.YieldAskPricing, Some(Erg), "ab67833075cfc912fc051e6c5ec2a90f2a084595d3c6f9a3eba1944a97221b4c"),
    (ZcExpression.GuardedYieldPricing, Some(Erg), "b2c2b210761aba2dfd156b0603cbaf69f2e2c09fd4edeb24ebfd66558d94f1d5"),
    (ZcExpression.CurveOrderPricing, Some(Erg), "f36ac43bdb81abe069be8193ad660f43164f35aec6decefeb3e86ae010000cd8"),
    (ZcExpression.OracleLiquidation, Some(SigUsd), "828abc1cf9dd3ae75351c91531d2e9165fe0b3f59e742b4f68ccc55f4006e768"),
    (ZcExpression.CollateralPolicy, Some(SigUsd), "b7e78d141821aaa6e5a5f6826b708c9ba4a2789d8e6a3022a4b98a1650f399f2"),
    (ZcExpression.FillGuard, Some(SigUsd), "8ad1f2f6f7e323b27cd473d9cc939dabc2db99d03eec8e6c2a2efad349fd357d"),
    (ZcExpression.YieldOrderPricing, Some(SigUsd), "4d81f91815c84dcff9cc7df95c29dcf62318fdeb9254095b20771fd1b9825f3d"),
    (ZcExpression.YieldAskPricing, Some(SigUsd), "fad3fb94179767d314df3254bc0449a3e27d1c76e7de603ee2cad4cd5811b65c"),
    (ZcExpression.GuardedYieldPricing, Some(SigUsd), "538b3f355e33418b1303b299fbb429794704e3f276ea12d781b0cb51d0520f4a"),
    (ZcExpression.CurveOrderPricing, Some(SigUsd), "4e806088778d56053696aabd334d698b78c147bc9cff6d3b52f2df2a6e883b28"))

  "The pinned ZC set" should "hold exactly the reference's 22 box contracts and 15 expressions" in {
    ZcContracts.scripts.map(s => (s.kind, s.market)).toSet shouldBe trees.map(t => (t._1, t._2)).toSet
    ZcContracts.scripts should have size 22
    ZcContracts.expressions.map(e => (e.expression, e.market)).toSet shouldBe expressions.map(e => (e._1, e._2)).toSet
    ZcContracts.expressions should have size 15
  }

  it should "give every box contract the reference's tree hash and template hash" in {
    trees.foreach { case (kind, market, treeHash, templateHash) =>
      val s = ZcContracts.script(kind, market)
      withClue(s.label) {
        s.treeHash shouldBe treeHash
        s.templateHash shouldBe templateHash
      }
    }
  }

  it should "give every expression the reference's hash, and find it again by that hash" in {
    expressions.foreach { case (e, market, hash) =>
      withClue(ZcContracts.expression(e, market).label) {
        ZcContracts.expression(e, market).hash shouldBe hash
        ZcContracts.expressionByHash(hash).map(x => (x.expression, x.market)) shouldBe Some(e -> market)
      }
    }
  }

  "classify" should "name each box contract by its exact tree, in either case" in {
    ZcContracts.scripts.foreach { s =>
      ZcContracts.classify(s.ergoTreeHex) shouldBe Some(s)
      ZcContracts.classify(s.ergoTreeHex.toUpperCase) shouldBe Some(s)
    }
  }

  it should "refuse a P2PK tree and a tree one byte off a contract" in {
    ZcContracts.classify(ZcFixtures.party(1)) shouldBe None
    val bond = ZcContracts.script(ZcKind.Bond, Some(Erg)).ergoTreeHex
    // Same length, one constant byte changed: the length check passes and the exact match must not
    val forged = bond.take(bond.length - 2) + (if (bond.endsWith("00")) "01" else "00")
    ZcContracts.classify(forged) shouldBe None
    ZcContracts.classify(bond + "00") shouldBe None
  }
}
