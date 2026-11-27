package tools

import lfsm.{Deployment, DeploymentDescriptor, DeploymentIds}
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import node.rest.RestNodeApi
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import tools.DeployPlan.FundRequest
import transactions.ProtocolContracts
import org.ergoplatform.ErgoTreePredef
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/** A step that did not complete, named so the exit message says where a rerun has to pick up. */
final class DeployFailure(val step: String, cause: Throwable)
  extends RuntimeException(s"step '$step' failed: ${cause.getMessage}", cause)

/**
 * Creates a complete Lithos deployment on a chain that has none, for devnets and new testnets.
 *
 * {{{
 * sbt "runMain tools.DeployProtocol --node http://127.0.0.1:9153 --api-key hello \
 *   --keystore <keystore.json> --pass-file <file> --network TESTNET --out deployment.json \
 *   [--fund <address>:<nanoERG>:<LIT>]... [--force] [--allow-mainnet] [--timeout-seconds 1800] [--reward-delay 720]"
 * }}}
 *
 * Signs with the client's own wiring (`SecretStorage` -> `NodeWallet`, `TxBuilder`,
 * `ProtocolContracts`) and never the node wallet's payment endpoints, so every box is built by the
 * same code the client later reads it with, and the node never picks inputs or change for it.
 *
 * Each step is one transaction, broadcast and then waited on until its first output is in the UTXO
 * set: the next step spends what the last one created, and a step built on an unconfirmed parent
 * that is then dropped would leave the chain half-deployed with nothing saying so. Any failure exits
 * non-zero naming the step. See DEVNET.md.
 */
object DeployProtocol {

  private val logger: Logger = LoggerFactory.getLogger("DeployProtocol")

  final case class Args(nodeUrl: String,
                        apiKey: String,
                        keystore: Path,
                        pass: String,
                        network: NetworkType,
                        out: Path,
                        funds: Seq[FundRequest],
                        force: Boolean,
                        allowMainnet: Boolean,
                        timeoutSeconds: Int,
                        rewardDelay: Int)

  val Usage: String =
    """usage: tools.DeployProtocol --node <url> --api-key <key> --keystore <keystore.json> --pass-file <file>
      |                            --network <MAINNET|TESTNET> --out <deployment.json>
      |                            [--fund <address>:<nanoERG>:<LIT base units>]... [--force] [--allow-mainnet]
      |                            [--timeout-seconds <n>] [--reward-delay <blocks>]""".stripMargin

  /** Every problem with the command line at once, or the arguments. */
  def parseArgs(args: Seq[String]): Either[Seq[String], Args] = {
    val problems = Seq.newBuilder[String]
    var values = Map.empty[String, String]
    var funds = Vector.empty[String]
    var force = false
    var allowMainnet = false
    var rest = args.toList
    val valued = Set("--node", "--api-key", "--keystore", "--pass", "--pass-file", "--network", "--out", "--fund",
      "--timeout-seconds", "--reward-delay")
    while (rest.nonEmpty) rest match {
      case "--force" :: tail => force = true; rest = tail
      case "--allow-mainnet" :: tail => allowMainnet = true; rest = tail
      case "--fund" :: v :: tail => funds :+= v; rest = tail
      case k :: v :: tail if valued.contains(k) => values += k -> v; rest = tail
      case k :: tail => problems += s"unknown or incomplete argument '$k'"; rest = tail
      case Nil => ()
    }

    def req(k: String): Option[String] = values.get(k) match {
      case None => problems += s"$k is required"; None
      case some => some
    }

    val node = req("--node")
    val key = req("--api-key")
    val keystore = req("--keystore").map(Paths.get(_))
    keystore.filterNot(Files.isRegularFile(_)).foreach(p => problems += s"--keystore $p does not exist")
    // The keystore password from a file (its first line), so it is in neither the process list nor the
    // shell history; --pass is kept for scripts that already guard both.
    val pass = (values.get("--pass-file"), values.get("--pass")) match {
      case (Some(file), _) => Try(scala.io.Source.fromFile(file)).map(s => try s.getLines().next().trim finally s.close())
        .toOption.filter(_.nonEmpty).orElse { problems += s"--pass-file $file cannot be read or is empty"; None }
      case (None, Some(p)) => Some(p)
      case (None, None) => problems += "--pass-file (or --pass) is required"; None
    }
    val network = req("--network").flatMap { n =>
      Try(NetworkType.valueOf(n.trim.toUpperCase)).toOption.orElse {
        problems += s"--network must be MAINNET or TESTNET, got $n"; None
      }
    }
    // Mainnet is the one network where this mints look-alike protocol tokens with a real key; the
    // client refuses a mainnet descriptor without allowOnMainnet, and the deployer matches it.
    if (network.contains(NetworkType.MAINNET) && !allowMainnet)
      problems += "--network MAINNET is refused unless --allow-mainnet is given: this mints tokens named like " +
        "the protocol's on the real chain"
    val out = req("--out").map(Paths.get(_))
    val timeout = values.get("--timeout-seconds").map(s => Try(s.toInt).toOption.filter(_ > 0).getOrElse {
      problems += s"--timeout-seconds must be a positive integer, got $s"; 0
    }).getOrElse(1800)
    // a private chain may lock coinbases for fewer blocks than mainnet's 720; the reward script embeds the delay
    val rewardDelay = values.get("--reward-delay").map(s => Try(s.toInt).toOption.filter(_ > 0).getOrElse {
      problems += s"--reward-delay must be a positive integer, got $s"; 0
    }).getOrElse(NodeWallet.MINER_REWARD_DELAY)
    val parsed = funds.map(DeployPlan.parseFund)
    parsed.collect { case Left(p) => p }.foreach(problems += _)
    val requests = parsed.collect { case Right(r) => r }
    requests.flatMap(DeployPlan.fundProblem).foreach(problems += _)
    network.foreach { n =>
      requests.filter(_.address.getNetworkType != n)
        .foreach(r => problems += s"--fund ${r.address} is not a $n address")
    }

    val all = problems.result()
    if (all.nonEmpty) Left(all)
    else Right(Args(node.get, key.get, keystore.get, pass.get, network.get, out.get, requests, force, allowMainnet,
      timeout, rewardDelay))
  }

  def main(raw: Array[String]): Unit = {
    val args = parseArgs(raw.toSeq) match {
      case Left(problems) =>
        System.err.println(problems.mkString("\n") + "\n\n" + Usage)
        sys.exit(2)
      case Right(a) => a
    }
    val outcome = Try {
      val url = { val t = args.nodeUrl.trim.stripSuffix("/"); t + "/" }
      val storage = Try(SecretStorage.loadFrom(args.keystore.toString))
        .getOrElse(throw new DeployFailure("keystore", new IllegalArgumentException(s"cannot load ${args.keystore}")))
      Try(storage.unlock(args.pass)).failed.foreach(ex => throw new DeployFailure("keystore", ex))
      val client = RestApiErgoClient.create(url, args.network, args.apiKey,
        RestApiErgoClient.getDefaultExplorerUrl(args.network))
      val wallet = Try(NodeWallet(client.execute(ctx =>
        ctx.newProverBuilder().withSecretStorage(storage).withEip3Secret(0).build())))
        .fold(ex => throw new DeployFailure("connect", ex), identity)
      new Deployer(client, RestNodeApi(url, Some(args.apiKey)), wallet, args.network,
        timeoutMs = args.timeoutSeconds * 1000L, rewardDelay = args.rewardDelay).run(args.out, args.funds, args.force)
    }
    outcome match {
      case Success(d) =>
        logger.info(s"Deployment complete: ${args.out} (emission box ${d.emissionBoxId.getOrElse("?")})")
        sys.exit(0)
      case Failure(f: DeployFailure) =>
        logger.error(f.getMessage, f.getCause)
        System.err.println(s"DeployProtocol: ${f.getMessage}")
        sys.exit(1)
      case Failure(ex) =>
        logger.error("DeployProtocol failed", ex)
        System.err.println(s"DeployProtocol: ${ex.getMessage}")
        sys.exit(1)
    }
  }
}

/**
 * The steps, against one node and one signing wallet.
 *
 * The ERG it spends is tracked here rather than re-read from the node between steps: every step's
 * change comes back to the deployer, and only boxes this run has seen confirmed are spent, so no
 * step can pick up a box an earlier step already consumed in the mempool. Boxes carrying tokens are
 * never used as plain funding, so change never sweeps a minted token somewhere it was not meant to go.
 */
class Deployer(client: ErgoClient,
               api: NodeApi,
               wallet: NodeWallet,
               network: NetworkType,
               timeoutMs: Long = 1800000L,
               pollMs: Long = 2000L,
               rewardDelay: Int = NodeWallet.MINER_REWARD_DELAY) {

  private val logger: Logger = LoggerFactory.getLogger("DeployProtocol")

  private val fee: Long = Parameters.MinFee

  /** Token-free boxes the deployer may spend, oldest knowledge first. */
  private var funding: Vector[InputUTXO] = Vector.empty

  private def step[A](name: String)(f: => A): A =
    Try(f) match {
      case Success(a) => a
      case Failure(d: DeployFailure) => throw d
      case Failure(ex) => throw new DeployFailure(name, ex)
    }

  def run(out: Path, funds: Seq[FundRequest], force: Boolean): DeploymentDescriptor = {
    step("preflight")(preflight(force))

    // 1. Mint. One transaction per token: a token's id is the first input's box id, so one
    //    transaction can mint exactly one.
    val minted: Map[String, InputUTXO] = DeployPlan.tokens.map { spec =>
      spec.role -> step(s"mint ${spec.role}")(mint(spec))
    }.toMap
    val id = (role: String) => minted(role).tokens.head.id

    // 2. Compile against the minted ids. The dictionary genesis id is not compiled into anything, and
    //    is not known until step 3 confirms, so the installed set carries a placeholder for it.
    val ids = DeploymentIds(
      lit = id(DeploymentIds.Keys.Lit),
      emissionNft = id(DeploymentIds.Keys.EmissionNft),
      emConfigNft = id(DeploymentIds.Keys.EmConfigNft),
      queueToken = id(DeploymentIds.Keys.QueueToken),
      collatToken = id(DeploymentIds.Keys.CollatToken),
      fpToken = id(DeploymentIds.Keys.FpToken),
      mdToken = id(DeploymentIds.Keys.MdToken),
      voteToken = id(DeploymentIds.Keys.VoteToken),
      mdGenesisId = "00" * 32,
      mdGenesisHeight = 0,
      fpControlAddress = DeployPlan.fpControlAddress(network))
    val contracts = step("compile") {
      Deployment.install(network, ids)
      ProtocolContracts.forNetwork(network)
    }
    logger.info(s"Compiled against the minted ids; emission guard ${contracts.guard.ergoTreeHex.take(16)}...")

    // 3. The protocol boxes, one transaction.
    val created = step("protocol boxes")(createProtocolBoxes(ids, contracts, minted))
    val dictionaryId = created.getOutputsToSpend.get(3).getId.toString
    val genesisHeight = step("dictionary inclusion height")(inclusionHeight(dictionaryId))

    // 4. The descriptor.
    val descriptor = DeploymentDescriptor(
      network = network,
      ids = ids.copy(mdGenesisId = dictionaryId, mdGenesisHeight = genesisHeight),
      emissionBoxId = Some(created.getOutputsToSpend.get(0).getId.toString),
      configBoxId = Some(created.getOutputsToSpend.get(1).getId.toString),
      fpControlBoxId = Some(created.getOutputsToSpend.get(2).getId.toString),
      deployerAddress = Some(wallet.p2pk.toString),
      height = Some(client.execute(_.getHeight)))
    step("descriptor") {
      Files.write(out, descriptor.toJson.getBytes(StandardCharsets.UTF_8))
      logger.info(s"Wrote $out")
    }

    // 5. Fund operators.
    if (funds.nonEmpty) step("fund")(fund(funds, ids.lit))
    descriptor
  }

  // ─── preflight ────────────────────────────────────────────────────────────

  /**
   * The indexer is required: the client finds every protocol box by token through it, and this tool
   * reads the dictionary's inclusion height from it. A wallet already holding tokens named like this
   * deployment's means a deployment exists, and a second one would compete with it for the client's
   * attention; `--force` is the operator saying they know.
   */
  private def preflight(force: Boolean): Unit = {
    val info = api.info().get
    logger.info(s"Node ${info.name} ${info.appVersion} at height ${info.fullHeight.getOrElse(-1)}, " +
      s"deployer ${wallet.p2pk}")
    require(api.indexerEnabled, "the node's extra index is off; start it with ergo.node.extraIndex = true")

    val held = api.walletBalances().map(_.assets.map(_.tokenId)).getOrElse(Seq.empty) ++
      api.addressBalance(wallet.p2pk.toString).toOption.toSeq.flatMap(b =>
        (b.confirmed.toSeq ++ b.unconfirmed.toSeq).flatMap(_.tokens.map(_.tokenId)))
    val named = if (held.isEmpty) Seq.empty else api.tokensByIds(held.distinct).getOrElse(Seq.empty)
    val clashes = named.filter(t => DeployPlan.tokenNames.contains(t.name.toLowerCase))
    if (clashes.nonEmpty) {
      val list = clashes.map(t => s"${t.name} (${t.id})").mkString(", ")
      if (force) logger.warn(s"--force: deploying again although the wallet holds $list")
      else throw new IllegalStateException(s"the wallet already holds $list; a deployment exists. " +
        "Rerun with --force to make another")
    }

    funding = client.execute(ctx => loadFunding(ctx))
    logger.info(s"${funding.size} spendable box(es), ${funding.map(_.value).sum} nanoERG")
  }

  /**
   * Plain boxes under any key the prover holds, and coinbase boxes past their lock (`rewardDelay`
   * blocks, mainnet's 720 unless the chain says otherwise), which on a devnet is where the deployer's
   * ERG comes from. The reward script embeds the delay, so a chain with a shorter lock also has a
   * different reward tree; both follow `rewardDelay`. Token-free only.
   */
  private def loadFunding(ctx: BlockchainContext): Vector[InputUTXO] = {
    val height = ctx.getHeight
    val live = MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)
    def all(tree: String): Seq[IndexedBox] = {
      var page = Paging(0, 100)
      var acc = Vector.empty[IndexedBox]
      var more = true
      while (more && acc.size < 5000) {
        val got = api.unspentBoxesByErgoTree(tree, page, SortDirection.Desc, live).get
        acc ++= got
        more = got.size == page.limit
        page = page.next
      }
      acc
    }
    val plain = wallet.signableTrees.toSeq.flatMap(all)
    val rewardTrees = (wallet.prover.getAddress +: wallet.addresses).map(a =>
      Contract(ErgoTreePredef.rewardOutputScript(rewardDelay, a.getPublicKey)).ergoTreeHex).distinct
    val rewards = rewardTrees.flatMap(all)
      .filter(b => b.box.creationHeight + rewardDelay < height)
    (plain ++ rewards).filter(_.box.assets.isEmpty).map(_.toInputUTXO(ctx)).distinct.toVector
  }

  /** Funding boxes covering `value`, largest first, removed from the pool. */
  private def take(value: Long): Seq[InputUTXO] = {
    val sorted = funding.sortBy(-_.value)
    var sum = 0L
    val chosen = sorted.takeWhile { b => val need = sum < value; if (need) sum += b.value; need }
    if (sum < value)
      throw new IllegalStateException(s"the deployer holds ${funding.map(_.value).sum} nanoERG in token-free " +
        s"boxes and this step needs $value. Fund ${wallet.p2pk} and rerun")
    funding = funding.filterNot(chosen.contains)
    chosen
  }

  // ─── broadcasting ─────────────────────────────────────────────────────────

  /**
   * Builds, signs, sends and waits. Change goes to the deployer's own P2PK, and the token-free outputs
   * there return to the funding pool once confirmed.
   */
  private def submit(label: String, inputs: Seq[InputUTXO], outputs: Seq[UTXO]): SignedTransaction = {
    val signed = client.execute { ctx =>
      val unsigned = TxBuilder(ctx).setInputs(inputs: _*).setOutputs(outputs: _*).buildTx(fee, wallet.p2pk)
      wallet.sign(unsigned)
    }
    val txId = api.sendTransaction(signed.toJson(false, false)).get
    logger.info(s"$label: sent $txId")
    awaitConfirmed(label, signed)
    logger.info(s"$label: confirmed $txId")
    val mine = wallet.contract.ergoTreeHex
    funding ++= signed.getOutputsToSpend.asScala.map(InputUTXO(_))
      .filter(o => o.contract.ergoTreeHex == mine && o.tokens.isEmpty)
    signed
  }

  /** Polls `/utxo/byId` for the first output until it is in the UTXO set. */
  private def awaitConfirmed(label: String, tx: SignedTransaction): Unit = {
    val boxId = tx.getOutputsToSpend.get(0).getId.toString
    val deadline = System.currentTimeMillis() + timeoutMs
    var confirmed = false
    while (!confirmed) {
      confirmed = api.boxById(boxId).toOption.flatten.isDefined
      if (!confirmed) {
        if (System.currentTimeMillis() > deadline)
          throw new IllegalStateException(s"${tx.getId} was not confirmed within ${timeoutMs / 1000}s; " +
            "is a node mining this chain?")
        Thread.sleep(pollMs)
      }
    }
  }

  /**
   * The height the index records for a confirmed box. The index runs behind the UTXO set by a
   * little, so a box `awaitConfirmed` has just seen may not be indexed yet: polled, like the
   * confirmation itself, until the deadline.
   */
  private def inclusionHeight(boxId: String): Int = {
    val deadline = System.currentTimeMillis() + timeoutMs
    var found: Option[Int] = None
    while (found.isEmpty) {
      found = api.indexedBoxById(boxId).toOption.flatten.map(_.inclusionHeight)
      if (found.isEmpty) {
        if (System.currentTimeMillis() > deadline)
          throw new IllegalStateException(s"the index does not hold confirmed box $boxId after ${timeoutMs / 1000}s; " +
            "is the node started with ergo.node.extraIndex = true?")
        Thread.sleep(pollMs)
      }
    }
    found.get
  }

  // ─── the steps ────────────────────────────────────────────────────────────

  private def mint(spec: TokenSpec): InputUTXO = {
    val inputs = take(DeployPlan.MintBoxValue + fee + Parameters.MinChangeValue)
    val out = DeployPlan.mintOutput(wallet.contract, spec, inputs.head.id)
    val signed = submit(s"mint ${spec.role} (${spec.name}, ${spec.amount})", inputs, Seq(out))
    val box = InputUTXO(signed.getOutputsToSpend.get(0))
    require(box.tokens.headOption.exists(t => t.id == inputs.head.id && t.amount == spec.amount),
      s"the mint of ${spec.role} did not produce ${spec.amount} of ${inputs.head.id}")
    logger.info(s"${spec.role} = ${inputs.head.id}")
    box
  }

  /**
   * The four protocol boxes, plus one box keeping the LIT the emission box does not hold and the five
   * vote tokens with the deployer.
   */
  private def createProtocolBoxes(ids: DeploymentIds,
                                  contracts: transactions.CompiledContracts,
                                  minted: Map[String, InputUTXO]): SignedTransaction = {
    val boxes = DeployPlan.protocolBoxes(ids, contracts, network)
    val remainder = UTXO(wallet.contract, DeployPlan.MintBoxValue,
      Seq(Token(ids.lit, DeployPlan.TotalLit - DeployPlan.EmissionLit), Token(ids.voteToken, DeployPlan.VoteTokens)))
    val outputs = boxes.all :+ remainder
    val tokenInputs = DeployPlan.tokens.map(t => minted(t.role))
    val carried = tokenInputs.map(_.value).sum
    val needed = outputs.map(_.value).sum + fee + Parameters.MinChangeValue - carried
    val signed = submit("protocol boxes", tokenInputs ++ take(math.max(needed, 0L)), outputs)
    val o = signed.getOutputsToSpend.asScala.map(_.getId.toString)
    logger.info(s"emission box ${o(0)}, config box ${o(1)}, FP control box ${o(2)}, dictionary genesis ${o(3)}")
    signed
  }

  /** One transaction paying every operator its ERG and LIT, taking the LIT from the remainder box. */
  private def fund(funds: Seq[FundRequest], litId: ErgoId): Unit = {
    val litHolder = client.execute { ctx =>
      api.unspentBoxesByTokenId(litId.toString, Paging(0, 50), SortDirection.Desc, MempoolOptions.ConfirmedOnly).get
        .find(b => b.ergoTree == wallet.contract.ergoTreeHex)
        .map(_.toInputUTXO(ctx))
        .getOrElse(throw new IllegalStateException(s"no confirmed box of the deployer's holds LIT $litId"))
    }
    val litNeeded = funds.map(_.lit).sum
    val litHeld = litHolder.tokens.filter(_.id == litId).map(_.amount).sum
    require(litNeeded <= litHeld, s"funding asks for $litNeeded LIT and the deployer holds $litHeld")
    val outputs = funds.map(DeployPlan.fundOutput(_, litId))
    val needed = outputs.map(_.value).sum + fee + Parameters.MinChangeValue - litHolder.value
    submit(s"fund ${funds.map(_.address).mkString(", ")}", litHolder +: take(math.max(needed, 0L)), outputs)
    funds.foreach(f => logger.info(s"Funded ${f.address} with ${f.nanoErg} nanoERG and ${f.lit} LIT"))
  }
}
