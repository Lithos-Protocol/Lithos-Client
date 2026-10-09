package tools

import configs.{CandidateConfig, EmissionConfig}
import lfsm.states.PlasmaDictionary
import lfsm.{Deployment, DeploymentIds, DeploymentSpec, LFSMHelpers}
import mining.CandidateTxBuilder
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, InputBox, NetworkType, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.{AvlTree, Coll}
import support.FakeNodeContext
import tools.DeployPlan.FundRequest
import transactions.ProtocolContracts
import transactions.emissions.EmissionTransactions
import transactions.engine.wallet.{FundingAllocation, FundingSource}
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import scala.util.Success

/**
 * What the deployer creates, read back by the client's own code on a mocked node.
 *
 * The goal of a devnet deployment is a Lithos block the client built from a collateral box it joined
 * with itself, so the boxes are not checked against expected values but run through the code that
 * has to accept them: the emission and config readers, the FP control lookup, the client's Join and
 * Activate builders (which sign, so the contracts run), and `loadCollateral` on the result.
 */
class DeployPlanSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  override def afterEach(): Unit = Deployment.clear()

  /** Join and Activate take their funding as arguments, so nothing here ever asks the wallet. */
  private object UnusedWallet extends FundingSource {
    def reserve(value: Long, tokens: Seq[Token]): FundingAllocation = throw new IllegalStateException("unused")
    def reserveCovering(value: Long): FundingAllocation = throw new IllegalStateException("unused")
    def reserveCoveringP2PK(value: Long): FundingAllocation = throw new IllegalStateException("unused")
    def reserveKnown(inputs: Seq[InputUTXO]): FundingAllocation = throw new IllegalStateException("unused")
    def giveBack(boxes: Seq[InputUTXO]): Unit = ()
  }

  /** The node's view of a box, ids as `MutationConversions` recomputes them. */
  private def nodeBox(b: InputBox): NodeBox = NodeBox(
    boxId = b.getId.toString,
    transactionId = b.getTransactionId,
    value = b.getValue,
    index = b.getTransactionIndex.toInt,
    creationHeight = b.getCreationHeight,
    ergoTree = b.getErgoTree.bytesHex,
    assets = b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
    additionalRegisters = NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, i) =>
      s"R${i + 4}" -> v.toHex }.toMap))

  private def indexed(b: InputBox, height: Int = 100): IndexedBox =
    IndexedBox(box = nodeBox(b), address = "deployed", inclusionHeight = height, globalIndex = 1L)

  private def placed(ctx: BlockchainContext, u: UTXO, index: Int): InputUTXO =
    u.toInput(ctx, ErgoId.create("de" * 32), index.toShort)

  /** A node whose token index answers from `byToken`, on the first page only. */
  private def nodeOver(byToken: Map[String, Seq[IndexedBox]]): NodeApi = {
    val api = mock[NodeApi]
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val token = inv.getArgument[String](0)
        val page = inv.getArgument[Paging](1)
        Success(if (page.offset == 0) byToken.getOrElse(token, Seq.empty[IndexedBox]) else Seq.empty[IndexedBox])
      }
    api
  }

  /**
   * An offline context with a deployment installed for its network, and the boxes planned against it.
   * The ids are the fake set, standing in for what step 1 mints.
   */
  private def withDeployment[A](f: (BlockchainContext, NodeWallet, DeploymentIds, ProtocolBoxes) => A): A = {
    val (node, _, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      val network = ctx.getNetworkType
      val ids = DeploymentSpec.fakeIds(network)
      Deployment.install(network, ids)
      f(ctx, wallet, ids, DeployPlan.protocolBoxes(ids, ProtocolContracts(ctx), network))
    }
  }

  // ─── step 1 ───────────────────────────────────────────────────────────────

  "A mint" should "name the first input's id and carry EIP-4 metadata" in {
    withDeployment { (ctx, wallet, _, _) =>
      val first = ErgoId.create("ab" * 32)
      val spec = DeployPlan.tokens.find(_.role == DeploymentIds.Keys.Lit).get
      val out = DeployPlan.mintOutput(wallet.contract, spec, first)
      out.tokens shouldEqual Seq(Token(first, LFSMHelpers.INIT_MINT))
      def text(i: Int) = new String(out.registers(i).getValue.asInstanceOf[Coll[Byte]].toArray, StandardCharsets.UTF_8)
      text(0) shouldEqual spec.name
      text(2) shouldEqual "9"
    }
  }

  "The token plan" should "mint what the contracts assume, one token per role" in {
    val byRole = DeployPlan.tokens.map(t => t.role -> t.amount).toMap
    byRole.keySet shouldEqual DeploymentSpec.fakeIds().tokens.map(_._1).toSet
    byRole(DeploymentIds.Keys.QueueToken) shouldEqual LFSMHelpers.PROP_TOKEN_AMNT
    byRole(DeploymentIds.Keys.CollatToken) shouldEqual LFSMHelpers.PROP_TOKEN_AMNT
    byRole(DeploymentIds.Keys.VoteToken) shouldEqual 5L
    Seq(DeploymentIds.Keys.EmissionNft, DeploymentIds.Keys.EmConfigNft, DeploymentIds.Keys.FpToken,
      DeploymentIds.Keys.MdToken).foreach(r => byRole(r) shouldEqual 1L)
    byRole(DeploymentIds.Keys.Lit) should be >= DeployPlan.EmissionLit
  }

  // ─── step 3, through the readers ──────────────────────────────────────────

  "The planned emission and config boxes" should "read back through EmissionTransactions" in {
    withDeployment { (ctx, wallet, ids, boxes) =>
      val c = ProtocolContracts(ctx)
      val api = nodeOver(Map(
        ids.emissionNft.toString -> Seq(indexed(placed(ctx, boxes.emission, 0).input)),
        ids.emConfigNft.toString -> Seq(indexed(placed(ctx, boxes.config, 1).input))))
      val txs = new EmissionTransactions(wallet, api, EmissionConfig.Default, UnusedWallet)

      val em = txs.readEmission(txs.emissionChain(ctx, 0).confirmed)
      em.currentBlock shouldEqual 0
      em.lenderSet shouldBe empty
      em.head shouldEqual 0L
      em.tail shouldEqual 0L
      em.nft shouldEqual Token(ids.emissionNft, 1L)
      em.queueToken shouldEqual Token(ids.queueToken, LFSMHelpers.PROP_TOKEN_AMNT)
      em.collatToken shouldEqual Token(ids.collatToken, LFSMHelpers.PROP_TOKEN_AMNT)
      em.lit shouldEqual Some(Token(ids.lit, DeployPlan.EmissionLit))

      val cfg = txs.readConfig(txs.configBox(ctx))
      cfg.permitParams shouldEqual LFSMHelpers.PERMIT_PARAMS.toSeq
      cfg.enforcerHash shouldEqual c.enforcer.hashedValueBytes
      cfg.rollupHash shouldEqual c.holding.hashedPropBytes
      cfg.extensionHash.length shouldEqual 32
    }
  }

  "The planned FP control box" should "be the one getFPControlBox finds, holding every proof's hash" in {
    withDeployment { (ctx, _, ids, boxes) =>
      val api = nodeOver(Map(ids.fpToken.toString -> Seq(indexed(placed(ctx, boxes.fpControl, 2).input))))
      val found = LFSMHelpers.getFPControlBox(ctx, api)
      val set = found.parseReg[Coll[Coll[Byte]]](0).toArray.map(_.toArray.toSeq).toSeq
      set shouldEqual ProtocolContracts(ctx).fraudProofs.ordered.map(_.hashedValueBytes.toSeq)
      found.contract.ergoTreeHex shouldEqual
        work.lithos.mutations.Contract.fromAddress(ids.fpControlAddress).ergoTreeHex
    }
  }

  "The planned dictionary genesis" should "start from the digest the client replays from" in {
    withDeployment { (ctx, _, ids, boxes) =>
      val box = placed(ctx, boxes.dictionary, 3)
      box.tokens shouldEqual Seq(Token(ids.mdToken, 1L))
      box.contract.ergoTreeHex shouldEqual ProtocolContracts(ctx).minerDictionary.ergoTreeHex
      box.parseReg[AvlTree](0).digest.toArray shouldEqual PlasmaDictionary.empty().digest
    }
  }

  // ─── the goal: the client joins, activates, and finds its collateral ──────

  "A funded operator" should "join and activate with the client's own builders, and loadCollateral find the box" in {
    withDeployment { (ctx, wallet, ids, boxes) =>
      val txs = new EmissionTransactions(wallet, mock[NodeApi], EmissionConfig.Default, UnusedWallet)
      val em = placed(ctx, boxes.emission, 0)
      val cfg = placed(ctx, boxes.config, 1)
      // What --fund pays, at the minimum DeployPlan allows.
      val fund = DeployPlan.fundOutput(
        FundRequest(wallet.p2pk, DeployPlan.MinJoinNanoErg, DeployPlan.MinJoinLit), ids.lit)
      val funding = fund.toInput(ctx, ErgoId.create("df" * 32), 0.toShort)

      def signs(what: String)(tx: => SignedTransaction): SignedTransaction =
        try tx catch { case ex: Throwable => fail(s"$what over the deployed boxes was refused: ${ex.getMessage}", ex) }

      val join = signs("Join")(txs.genJoin(ctx, em, cfg, wallet.p2pk, Seq(funding)))
      val activate = signs("Activate")(txs.genActivate(ctx,
        InputUTXO(join.getOutputsToSpend.get(0)), cfg, InputUTXO(join.getOutputsToSpend.get(1)),
        retiring = None, funding = Seq.empty, feeOutput = None))

      val collateral = activate.getOutputsToSpend.get(1)
      val api = nodeOver(Map(ids.collatToken.toString -> Seq(indexed(collateral, height = 110))))
      val loaded = new CandidateTxBuilder(wallet, api, CandidateConfig.Default).loadCollateral(ctx)
      withClue("the collateral box the client's own Activate made is not one it would mine with: ") {
        loaded.map(_.id) shouldEqual Seq(collateral.getId.toString)
      }
    }
  }

  // ─── funding ──────────────────────────────────────────────────────────────

  "A fund request" should "be refused below what one self-join needs" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val ok = FundRequest(wallet.p2pk, DeployPlan.MinJoinNanoErg, DeployPlan.MinJoinLit)
    DeployPlan.fundProblem(ok) shouldBe None
    DeployPlan.fundProblem(ok.copy(nanoErg = DeployPlan.MinJoinNanoErg - 1)) shouldBe defined
    DeployPlan.fundProblem(ok.copy(lit = DeployPlan.MinJoinLit - 1)) shouldBe defined
    DeployPlan.MinJoinNanoErg should be > lfsm.CollateralParams.PRINCIPAL_FLOOR + Parameters.MinFee
  }

  it should "parse from the command line form" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val parsed = DeployPlan.parseFund(s"${wallet.p2pk}:3000000000:2000000000000").toOption.get
    (parsed.address.toString, parsed.nanoErg, parsed.lit) shouldEqual
      ((wallet.p2pk.toString, 3000000000L, 2000000000000L))
    DeployPlan.parseFund("nope").isLeft shouldBe true
    DeployPlan.parseFund(s"${wallet.p2pk}:x:1").isLeft shouldBe true
  }

  "The command line" should "list every problem at once" in {
    val problems = DeployProtocol.parseArgs(Seq("--network", "DEVNET", "--bogus")).left.toOption.get
    problems.exists(_.contains("--node is required")) shouldBe true
    problems.exists(_.contains("--network must be")) shouldBe true
    problems.exists(_.contains("--bogus")) shouldBe true
  }

  it should "read the keystore password from --pass-file, and require one of the two" in {
    val ks = java.nio.file.Files.createTempFile("ks", ".json")
    val pf = java.nio.file.Files.createTempFile("pass", ".txt")
    java.nio.file.Files.write(pf, "s3cret\n".getBytes)
    val base = Seq("--node", "http://n", "--api-key", "k", "--keystore", ks.toString, "--network", "TESTNET", "--out", "/tmp/d.json")
    DeployProtocol.parseArgs(base ++ Seq("--pass-file", pf.toString)).toOption.get.pass shouldEqual "s3cret"
    DeployProtocol.parseArgs(base).left.toOption.get.exists(_.contains("--pass-file")) shouldBe true
    DeployProtocol.parseArgs(base ++ Seq("--pass-file", "/nonexistent/x")).left.toOption.get
      .exists(_.contains("cannot be read")) shouldBe true
  }

  it should "refuse MAINNET unless --allow-mainnet is given" in {
    val ks = java.nio.file.Files.createTempFile("ks", ".json")
    val base = Seq("--node", "http://n", "--api-key", "k", "--keystore", ks.toString, "--pass", "p",
      "--network", "MAINNET", "--out", "/tmp/d.json")
    DeployProtocol.parseArgs(base).left.toOption.get.exists(_.contains("--allow-mainnet")) shouldBe true
    DeployProtocol.parseArgs(base :+ "--allow-mainnet").toOption.get.allowMainnet shouldBe true
  }

  it should "take the chain's coinbase lock as --reward-delay, mainnet's 720 by default" in {
    val ks = java.nio.file.Files.createTempFile("ks", ".json")
    val base = Seq("--node", "http://n", "--api-key", "k", "--keystore", ks.toString, "--pass", "p",
      "--network", "TESTNET", "--out", "/tmp/d.json")
    DeployProtocol.parseArgs(base).toOption.get.rewardDelay shouldEqual NodeWallet.MINER_REWARD_DELAY
    DeployProtocol.parseArgs(base ++ Seq("--reward-delay", "10")).toOption.get.rewardDelay shouldEqual 10
    DeployProtocol.parseArgs(base ++ Seq("--reward-delay", "x")).left.toOption.get
      .exists(_.contains("--reward-delay")) shouldBe true
  }

  "The deployer's FP control address" should "be what the mainnet constants compile on mainnet" in {
    DeployPlan.fpControlAddress(NetworkType.MAINNET).toString shouldEqual
      DeploymentIds.mainnet.fpControlAddress.toString
  }
}
