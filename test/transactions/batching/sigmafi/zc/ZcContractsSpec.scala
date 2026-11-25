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
    (ZcKind.FractionalVault, None, "99e345c208d09a5fd47fa19ac84085172843faf19ae277d48db581b2655d63a1", "ff5d610814ac7889da8a459870e2db0293f4a68a9c0121a54b62544c56a70b8c"),
    (ZcKind.FractionalMint, None, "e4e27a1ddc8d4e431c0d1b0f0d178cbefa74eea924917c730726e2799d9b259c", "cc52aadf4d985c832d5272bc5dac6cb63a82825571feab204f75c79c08d81655"),
    (ZcKind.DutchAuctionVault, None, "a06947169baf279e8b8520d1d6ab8e2da944b73f1d9bad1c6e66a7aed8a95552", "ca31beaaf7e956b9335f0b7445080e6d6d9e90523da9e02020834579cc09b14e"),
    (ZcKind.WrapVault, None, "181c3ac1ff619d82515c5af3dc93af5360e0ef30e4abab95406537b8d4d38177", "80cb3ac721bcd186fa72dc029feefd8daa76a60a44078f24f8623776554237d0"),
    (ZcKind.WrapMint, None, "95dd499a9ea4c10c14ce474a5ee916f60236b1eec379ae04f170d22b2a651a47", "9972a876f976da80074b4e0d81fc4cc1b731df11ca5f0128f8d072883b48dcba"),
    (ZcKind.Bond, Some(Erg), "319a66e05ae32391584bb6f649e1c91dfb2eead2c496768c53de0a101c1114c5", "1a4cf42f39a1cfd057d3914effeffb71d059be5581393f6e6037bf1d31a4c20b"),
    (ZcKind.Order, Some(Erg), "802a89eb52be746700099523bbba257e51805ea8fbc4c795a19c45b1d650b4fe", "2bd49c8c8602085776b854e106f27115387a8f0020ba6c0332211ea5a222f69a"),
    (ZcKind.LendOffer, Some(Erg), "5fac97abf7d708d6e83b0600404fbd0b0ff33466afb134fb09713f08bdbd1844", "73daef976f7574a8fd350bd804f593eea14577faa6747959aad6efdf3f00b686"),
    (ZcKind.SaleOrder, Some(Erg), "0c110c3f7566607dc6a05864904b2de7eeee5336c7b63f5164bd7e51361567be", "fffc798bf6a2ff9d42edde434a74230837dfefa6398a061f3b4e03d20df4bee9"),
    (ZcKind.PricedAsk, Some(Erg), "740b7956e8e398e2e65ec5656eefc20124a3c7621dd27bbb35c9ee2bdf76db81", "b73cef9ea01cb21153b36ba353139fd5975821f02320dfc609f019067e8ff310"),
    (ZcKind.DutchAuctionMint, Some(Erg), "e0f3e8f3b69a2906800e43adc86fd72192ed979f2a58c9addf5d3c6585aaa66b", "ebe63a2ac4a1e47ff61772392b6f07de7c86970e9730dbc4e83909c1ec5aaf01"),
    (ZcKind.PricedOrder, Some(Erg), "27440806b7461d5a0f5087b9ceff6b77ca8411bf91be5d2508a2fc3e75ffde11", "87869835ff460abd4b0cca45d8052b7d7e7084dedf1f033b133edef7b41e7e10"),
    (ZcKind.LotAuction, Some(Erg), "40f05633d284007d788afaff8e59ffc41dd3ae4e865310cc7277047d8b84313d", "5dbab7cde52f40ba7254646d45523019a7b7e96688ad17ee08751afeae441bc0"),
    (ZcKind.Bond, Some(SigUsd), "3abc94884bb5697e62d07e5f7d762294367475e476e11b20e5a9ee73290afc8c", "1a4cf42f39a1cfd057d3914effeffb71d059be5581393f6e6037bf1d31a4c20b"),
    (ZcKind.Order, Some(SigUsd), "cfe2ecbb0aa6d441ca6d88a5891c6bae04de9700f402adc10a25d8f47cc2dd99", "2bd49c8c8602085776b854e106f27115387a8f0020ba6c0332211ea5a222f69a"),
    (ZcKind.LendOffer, Some(SigUsd), "314a145830e112d22cc1dc58a5cef3f59a2a771138324e23f8f2ce908b71437e", "73daef976f7574a8fd350bd804f593eea14577faa6747959aad6efdf3f00b686"),
    (ZcKind.SaleOrder, Some(SigUsd), "651bd9f9a3b1a2599f551e809e48ce35b0bb411909b371ffadeefb1ca2aabcb3", "fffc798bf6a2ff9d42edde434a74230837dfefa6398a061f3b4e03d20df4bee9"),
    (ZcKind.PricedAsk, Some(SigUsd), "a85a8dc57738c1a1d7c8b68b91829c0c5e541b3acfa9d1fc2aad1d548a0b2525", "b73cef9ea01cb21153b36ba353139fd5975821f02320dfc609f019067e8ff310"),
    (ZcKind.DutchAuctionMint, Some(SigUsd), "7b4773322075a8ac735974e0abddabceb04b0d7ee82f55f48503020e1a74900a", "ebe63a2ac4a1e47ff61772392b6f07de7c86970e9730dbc4e83909c1ec5aaf01"),
    (ZcKind.PricedOrder, Some(SigUsd), "59c98152ab14c3953ef8918eec311aa1e5cb8b86fce9217f79916626b8d8f7db", "87869835ff460abd4b0cca45d8052b7d7e7084dedf1f033b133edef7b41e7e10"),
    (ZcKind.LotAuction, Some(SigUsd), "fcf2ea6ec34bd11288a881f62a3be40f5b3088edc28f0b63255684436ff2b3da", "5dbab7cde52f40ba7254646d45523019a7b7e96688ad17ee08751afeae441bc0"))

  private val expressions: Seq[(ZcExpression, Option[ZcMarket], String)] = Seq(
    (ZcExpression.MaturityLiquidation, None, "caa76cff5dc32ccc9546daa3025f4a051690d5970528ce9198b63fd3a76ba8fb"),
    (ZcExpression.OracleLiquidation, Some(Erg), "bd55b5c986032ad2ad4abe9eb98b8864584b772b8e44a2fc97c35f6f91d552d2"),
    (ZcExpression.CollateralPolicy, Some(Erg), "46e567ce0f55faeba92af61563ff1901bcbf94df6e06fdba4e5deb525ae8d1a5"),
    (ZcExpression.FillGuard, Some(Erg), "ec271fdf4ba21aac5fa69cf39ddd4d84b3956b972b555cd4cdecb5ef111577d2"),
    (ZcExpression.YieldOrderPricing, Some(Erg), "b801b8bb90eee4d58d64810d77e25489e0f44d0a3dc4acfa2d17dd08c6dbb358"),
    (ZcExpression.YieldAskPricing, Some(Erg), "fcf4d7d1f072bb6246d18e00bc94c558e910524b0dd67c6d74bb32e08bd11584"),
    (ZcExpression.GuardedYieldPricing, Some(Erg), "4b14a27a686e11e2375e81224dd99f6b1e0229a7f2deb21dace319c6f436f5b7"),
    (ZcExpression.CurveOrderPricing, Some(Erg), "f36ac43bdb81abe069be8193ad660f43164f35aec6decefeb3e86ae010000cd8"),
    (ZcExpression.OracleLiquidation, Some(SigUsd), "828abc1cf9dd3ae75351c91531d2e9165fe0b3f59e742b4f68ccc55f4006e768"),
    (ZcExpression.CollateralPolicy, Some(SigUsd), "b7e78d141821aaa6e5a5f6826b708c9ba4a2789d8e6a3022a4b98a1650f399f2"),
    (ZcExpression.FillGuard, Some(SigUsd), "8ad1f2f6f7e323b27cd473d9cc939dabc2db99d03eec8e6c2a2efad349fd357d"),
    (ZcExpression.YieldOrderPricing, Some(SigUsd), "4d81f91815c84dcff9cc7df95c29dcf62318fdeb9254095b20771fd1b9825f3d"),
    (ZcExpression.YieldAskPricing, Some(SigUsd), "fb0704f86febc435fc264f90d0e42b69a1e4f8ddf5fdf7cef37e713b7287ed97"),
    (ZcExpression.GuardedYieldPricing, Some(SigUsd), "6f44642286607bb90a222a4b6281381c3fad3ea44e96d6289360c4752fa1900b"),
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
