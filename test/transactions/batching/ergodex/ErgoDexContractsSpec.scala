package transactions.batching.ergodex

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.hash.Blake2b256

class ErgoDexContractsSpec extends AnyFlatSpec with Matchers {

  private case class LiveOrder(expected: String, ergoTree: String)

  private val liveOrders = Seq(
    LiveOrder("swapSellV3",
      "19910521040005808088fccdbcc32305bd93abb2a8aeb81005ccb3930404ca0f08cd0334a7e6292a6829ddb60db95748" +
      "055d44495cf01fbd99510f18e766551daf39fd0404040604020400010105c4c71504000e20f40afb6f877c40a30c8637" +
      "dd5362227285738174151ce66d6684bc1b727ab6cf0e240008cd0334a7e6292a6829ddb60db95748055d44495cf01fbd" +
      "99510f18e766551daf39fd0e209a06d9e545a41fd51eeffc5e20d818073bf820c635e2a9d922269913e0de369d058ea4" +
      "0c0101010105f015060100040404020e209a06d9e545a41fd51eeffc5e20d818073bf820c635e2a9d922269913e0de36" +
      "9d0101040406010104d00f0e691005040004000e36100204a00b08cd0279be667ef9dcbbac55a06295ce870b07029bfc" +
      "db2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193" +
      "c2b2a57301007473027303830108cdeeac93b1a5730405000500058092f4010100d804d601b2a4730000d6027301d603" +
      "7302d6049c73037e730405eb027305d195ed92b1a4730693b1db630872017307d806d605db63087201d606b2a5730800" +
      "d607db63087206d608b27207730900d6098c720802d60a95730a9d9c7e997209730b067e7202067e7203067e720906ed" +
      "ededededed938cb27205730c0001730d93c27206730e938c720801730f92720a7e7310069573117312d801d60b997e73" +
      "13069d9c720a7e7203067e72020695ed91720b731492b172077315d801d60cb27207731600ed938c720c017317927e8c" +
      "720c0206720b7318909c7e8cb2720573190002067e7204069c9a720a731a9a9c7ec17201067e731b067e72040690b0ad" +
      "a5d9010b639593c2720b731cc1720b731d731ed9010b599a8c720b018c720b02731f7320"),
    LiveOrder("swapBuyV1",
      "19b1031508cd02c26cd1022fdcaf78186522895a31a0a32a1ab2f74eb02b1f7019150f0e042b05040004040406040205" +
      "8080b4ccd4dfc6030581ace4a184f5a817040004000e201f01dc8e29806d96ca0b79f8e798cd8cfce51c0e676aaedf6a" +
      "b3464b37da9dfd05b4ea6004ca0f060101040404d00f04ca0f0e691005040004000e36100204a00b08cd0279be667ef9" +
      "dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d196" +
      "83030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a5730405000500058092f4010100d8" +
      "02d6017300d602b2a4730100eb027201d195ed92b1a4730293b1db630872027303d804d603db63087202d604b2a57304" +
      "00d6059d9c7e99c17204c1a7067e7305067e730606d6068cb2db6308a773070002edededed938cb27203730800017309" +
      "93c27204d072019272057e730a06909c9c7ec17202067e7206067e730b069c9a7205730c9a9c7e8cb27203730d000206" +
      "7e730e067e9c72067e730f050690b0ada5d90107639593c272077310c1720773117312d90107599a8c7207018c720702" +
      "73137314"),
    LiveOrder("swapSellV1",
      "19f9031808cd033cd97bdbaf6c78160aad0c8d5e3f598ef6ab03080e8f070c26d63c36625121ea04000580c2d72f0404" +
      "04060402040004000e20dc234b4f8e7844fbd2a6c10f41b2e51c5422341b8648e83ed0295e5eb3f409130e205ec37187" +
      "0836e08bd0dccdb69dff880a1bdd177a02c1a60fa177b27fee75ce5805dcd34a05bab5a9b2a8bced22058080b4ccd4df" +
      "c603040404ca0f06010104d00f0580c2d72f04ca0f0e691005040004000e36100204a00b08cd0279be667ef9dcbbac55" +
      "a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193" +
      "a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a5730405000500058092f4010100d803d60173" +
      "00d602b2a4730100d6037302eb027201d195ed92b1a4730393b1db630872027304d804d604db63087202d605b2a57305" +
      "00d606b2db63087205730600d6077e8c72060206edededededed938cb2720473070001730893c27205d07201938c7206" +
      "0173099272077e730a06927ec172050699997ec1a7069d9c72077e730b067e730c067e720306909c9c7e8cb27204730d" +
      "0002067e7203067e730e069c9a7207730f9a9c7ec17202067e7310067e9c73117e7312050690b0ada5d90108639593c2" +
      "72087313c1720873147315d90108599a8c7208018c72080273167317"),
  )

  "Pinned templates" should "all be well-formed and distinct" in {
    ErgoDexContracts.orders.foreach { order =>
      withClue(order.name) {
        order.templateHex.length          should be > 0
        order.templateHex.length % 2      shouldBe 0
        order.templateHex                 should fullyMatch regex "[0-9a-f]+"
        order.templateHash.length         shouldBe 64
      }
    }
    val hashes = ErgoDexContracts.orders.map(_.templateHash)
    hashes.distinct.size shouldBe hashes.size
  }

  it should "identify a real mainnet order box by its template" in {
    liveOrders.foreach { live =>
      val template = sigma.ast.ErgoTree.fromHex(live.ergoTree).template
      val hash = Hex.toHexString(Blake2b256(template))
      withClue(s"live ${live.expected} box") {
        ErgoDexContracts.byTemplateHash.get(hash).map(_.version) shouldBe
          ErgoDexContracts.orders.find(_.name.endsWith(versionOf(live.expected))).map(_.version)
        ErgoDexContracts.byTemplateHash(hash).kind shouldBe kindOf(live.expected)
      }
    }
  }

  it should "reject a tree that is not an ErgoDEX order" in {
    val unrelated = sigma.ast.ErgoTree.fromHex(ErgoDexContracts.NativePoolErgoTree).template
    ErgoDexContracts.byTemplateHash.get(Hex.toHexString(Blake2b256(unrelated))) shouldBe None
  }

  "Pool scripts" should "parse and differ from each other" in {
    val native = sigma.ast.ErgoTree.fromHex(ErgoDexContracts.NativePoolErgoTree)
    val token  = sigma.ast.ErgoTree.fromHex(ErgoDexContracts.TokenPoolErgoTree)
    native.template should not equal token.template
    ErgoDexContracts.poolErgoTree(PoolShape.Native) shouldBe ErgoDexContracts.NativePoolErgoTree
    ErgoDexContracts.poolErgoTree(PoolShape.Token) shouldBe ErgoDexContracts.TokenPoolErgoTree
  }

  private def versionOf(fixture: String): String =
    if (fixture.endsWith("V3")) "v3" else "v1"

  private def kindOf(fixture: String): OrderKind =
    if (fixture.startsWith("swapSell")) OrderKind.SwapSell else OrderKind.SwapBuy
}
