package node.rest

import com.google.gson.{JsonElement, JsonObject}
import node.model._
import node.rest.JsonOps._
import node.rest.NodeCodecs._
import node.{NodeApi, NodeError}

import scala.util.{Failure, Success, Try}

object RestNodeApi {

  def apply(baseUrl: String, apiKey: Option[String] = None): RestNodeApi =
    new RestNodeApi(NodeHttp(NodeHttpConfig(baseUrl, apiKey)))

  def apply(config: NodeHttpConfig): RestNodeApi = new RestNodeApi(NodeHttp(config))
}

class RestNodeApi(http: NodeHttp) extends NodeApi {

  private def obj(e: JsonElement): JsonObject = e.getAsJsonObject

  private def decode[A](endpoint: String)(f: => A): Try[A] =
    Try(f).recoverWith { case t: Throwable => Failure(NodeError.Decoding(endpoint, t)) }

  private def one[A](path: String, params: Seq[(String, String)] = Nil)(f: JsonObject => A): Try[A] =
    http.getJson(path, params).flatMap(e => decode(path)(f(obj(e))))

  private def oneOpt[A](path: String, params: Seq[(String, String)] = Nil)(f: JsonObject => A): Try[Option[A]] =
    http.getJsonOpt(path, params).flatMap {
      case None    => Success(None)
      case Some(e) => decode(path)(Some(f(obj(e))))
    }

  private def many[A](path: String, params: Seq[(String, String)] = Nil)(f: JsonObject => A): Try[Seq[A]] =
    http.getJson(path, params).flatMap(e => decode(path)(asObjects(e).map(f)))

  private def manyStrings(path: String, params: Seq[(String, String)] = Nil): Try[Seq[String]] =
    http.getJson(path, params).flatMap(e => decode(path)(asStrings(e)))

  private def postOne[A](path: String, body: String)(f: JsonObject => A): Try[A] =
    http.postJson(path, body).flatMap(e => decode(path)(f(obj(e))))

  private def postMany[A](path: String, body: String)(f: JsonObject => A): Try[Seq[A]] =
    http.postJson(path, body).flatMap(e => decode(path)(asObjects(e).map(f)))

  private def page(p: Paging): Seq[(String, String)] =
    Seq("offset" -> p.offset.toString, "limit" -> p.limit.toString)

  private def mempoolParams(m: MempoolOptions): Seq[(String, String)] = Seq(
    "includeUnconfirmed" -> m.includeUnconfirmed.toString,
    "excludeMempoolSpent" -> m.excludeMempoolSpent.toString
  )

  private def rangeParams(r: ConfirmationRange): Seq[(String, String)] = Seq(
    "minConfirmations" -> r.minConfirmations.toString,
    "maxConfirmations" -> r.maxConfirmations.toString,
    "minInclusionHeight" -> r.minInclusionHeight.toString,
    "maxInclusionHeight" -> r.maxInclusionHeight.toString
  )

  private def optParams(pairs: (String, Option[Any])*): Seq[(String, String)] =
    pairs.collect { case (k, Some(v)) => k -> v.toString }

  private def requireIndexer[A](endpoint: String)(f: => Try[A]): Try[A] =
    if (indexerEnabled) f else Failure(NodeError.IndexerDisabled(endpoint))

  def info(): Try[NodeInfo] = one("/info")(NodeCodecs.info)

  def isOnline: Boolean = http.reachable

  def headerIds(fromHeight: Option[Int], toHeight: Option[Int]): Try[Seq[String]] =
    manyStrings("/blocks", optParams("fromHeight" -> fromHeight, "toHeight" -> toHeight))

  def headerIdsAtHeight(height: Int): Try[Seq[String]] = manyStrings(s"/blocks/at/$height")

  def headerIdsByHeights(heights: Seq[Int]): Try[Seq[String]] =
    http.postJson("/blocks/headerIds", intArray(heights).toString)
      .flatMap(e => decode("/blocks/headerIds")(asStrings(e)))

  def sendBlock(blockJson: String): Try[String] = http.postText("/blocks", blockJson)

  def chainSlice(fromHeight: Option[Int], toHeight: Option[Int]): Try[Seq[NodeHeader]] =
    many("/blocks/chainSlice", optParams("fromHeight" -> fromHeight, "toHeight" -> toHeight))(NodeCodecs.header)

  def lastHeaders(count: Int): Try[Seq[NodeHeader]] = many(s"/blocks/lastHeaders/$count")(NodeCodecs.header)

  def block(headerId: String): Try[Option[NodeBlock]] = oneOpt(s"/blocks/$headerId")(NodeCodecs.block)

  def blockAt(height: Int): Try[Seq[NodeBlock]] =
    headerIdsAtHeight(height).flatMap { ids =>
      ids.foldLeft(Try(Seq.empty[NodeBlock])) { (acc, id) =>
        acc.flatMap(bs => block(id).map(ob => bs ++ ob.toSeq))
      }
    }

  def header(headerId: String): Try[Option[NodeHeader]] = oneOpt(s"/blocks/$headerId/header")(NodeCodecs.header)

  def blockTransactions(headerId: String): Try[Option[NodeBlockTransactions]] =
    oneOpt(s"/blocks/$headerId/transactions")(NodeCodecs.blockTransactions)

  def txProof(headerId: String, txId: String): Try[Option[NodeMerkleProof]] =
    oneOpt(s"/blocks/$headerId/proofFor/$txId")(NodeCodecs.blockTxProof)

  def modifier(modifierId: String): Try[Option[String]] = http.getTextOpt(s"/blocks/modifier/$modifierId")

  def popowHeaderById(headerId: String): Try[Option[PopowHeader]] =
    oneOpt(s"/nipopow/popowHeaderById/$headerId")(NodeCodecs.popowHeader)

  def popowHeaderByHeight(height: Int): Try[Option[PopowHeader]] =
    oneOpt(s"/nipopow/popowHeaderByHeight/$height")(NodeCodecs.popowHeader)

  def nipopowProof(minChainLength: Int, suffixLength: Int): Try[NipopowProof] =
    one(s"/nipopow/proof/$minChainLength/$suffixLength")(NodeCodecs.nipopowProof)

  def nipopowProofByHeaderId(minChainLength: Int, suffixLength: Int, headerId: String): Try[NipopowProof] =
    one(s"/nipopow/proof/$minChainLength/$suffixLength/$headerId")(NodeCodecs.nipopowProof)

  def sendTransaction(txJson: String): Try[String] = http.postText("/transactions", txJson)

  def checkTransaction(txJson: String): Try[String] = http.postText("/transactions/check", txJson)

  def unconfirmedTransactions(paging: Paging): Try[Seq[NodeTransaction]] =
    many("/transactions/unconfirmed", page(paging))(NodeCodecs.transaction)

  def unconfirmedTransactionIds(): Try[Seq[String]] = manyStrings("/transactions/unconfirmed/transactionIds")

  def unconfirmedTransactionById(txId: String): Try[Option[NodeTransaction]] =
    oneOpt(s"/transactions/unconfirmed/byTransactionId/$txId")(NodeCodecs.transaction)

  def unconfirmedTransactionsByIds(txIds: Seq[String]): Try[Seq[NodeTransaction]] =
    postMany("/transactions/unconfirmed/byTransactionIds", stringArray(txIds).toString)(NodeCodecs.transaction)

  def unconfirmedTransactionsByErgoTree(ergoTree: String, paging: Paging): Try[Seq[NodeTransaction]] =
    http.postJson("/transactions/unconfirmed/byErgoTree", quoted(ergoTree), page(paging))
      .flatMap(e => decode("/transactions/unconfirmed/byErgoTree")(asObjects(e).map(transaction)))

  def unconfirmedInputByBoxId(boxId: String): Try[Option[NodeTransaction]] =
    oneOpt(s"/transactions/unconfirmed/inputs/byBoxId/$boxId")(NodeCodecs.transaction)

  def unconfirmedOutputByBoxId(boxId: String): Try[Option[NodeBox]] =
    oneOpt(s"/transactions/unconfirmed/outputs/byBoxId/$boxId")(NodeCodecs.box)

  def unconfirmedOutputsByErgoTree(ergoTree: String, paging: Paging): Try[Seq[NodeBox]] =
    http.postJson("/transactions/unconfirmed/outputs/byErgoTree", quoted(ergoTree), page(paging))
      .flatMap(e => decode("/transactions/unconfirmed/outputs/byErgoTree")(asObjects(e).map(box)))

  def unconfirmedOutputsByTokenId(tokenId: String): Try[Seq[NodeBox]] =
    many(s"/transactions/unconfirmed/outputs/byTokenId/$tokenId")(NodeCodecs.box)

  def unconfirmedOutputsByRegisters(registers: NodeRegisters, paging: Paging): Try[Seq[NodeBox]] =
    http.postJson("/transactions/unconfirmed/outputs/byRegisters", encodeRegisters(registers).toString, page(paging))
      .flatMap(e => decode("/transactions/unconfirmed/outputs/byRegisters")(asObjects(e).map(box)))

  def poolHistogram(): Try[PoolHistogram] = one("/transactions/poolHistogram")(NodeCodecs.poolHistogram)

  def recommendedFee(waitTimeMinutes: Int, txSizeBytes: Int): Try[Long] =
    http.getText("/transactions/getFee",
      Seq("waitTime" -> waitTimeMinutes.toString, "txSize" -> txSizeBytes.toString)).flatMap(s =>
      decode("/transactions/getFee")(s.toLong))

  def expectedWaitTime(fee: Long, txSizeBytes: Int): Try[Int] =
    http.getText("/transactions/waitTime",
      Seq("fee" -> fee.toString, "txSize" -> txSizeBytes.toString)).flatMap(s =>
      decode("/transactions/waitTime")(s.toInt))

  def boxById(boxId: String): Try[Option[NodeBox]] = oneOpt(s"/utxo/byId/$boxId")(NodeCodecs.box)

  def boxByIdBinary(boxId: String): Try[Option[SerializedNodeBox]] =
    oneOpt(s"/utxo/byIdBinary/$boxId")(NodeCodecs.serializedBox)

  def boxWithPoolById(boxId: String): Try[Option[NodeBox]] = oneOpt(s"/utxo/withPool/byId/$boxId")(NodeCodecs.box)

  def boxWithPoolByIdBinary(boxId: String): Try[Option[SerializedNodeBox]] =
    oneOpt(s"/utxo/withPool/byIdBinary/$boxId")(NodeCodecs.serializedBox)

  def boxesWithPoolByIds(boxIds: Seq[String]): Try[Seq[NodeBox]] =
    postMany("/utxo/withPool/byIds", stringArray(boxIds).toString)(NodeCodecs.box)

  def boxesBinaryProof(boxIds: Seq[String]): Try[Option[BoxesBinaryProof]] =
    http.postJsonOpt("/utxo/getBoxesBinaryProof", stringArray(boxIds).toString).flatMap {
      case None    => Success(None)
      case Some(e) => decode("/utxo/getBoxesBinaryProof")(Some(NodeCodecs.boxesBinaryProof(obj(e))))
    }

  def snapshotsInfo(): Try[SnapshotsInfo] = one("/utxo/getSnapshotsInfo")(NodeCodecs.snapshotsInfo)

  def genesisBoxes(): Try[Seq[NodeBox]] = many("/utxo/genesis")(NodeCodecs.box)

  def walletStatus(): Try[WalletStatus] = one("/wallet/status")(NodeCodecs.walletStatus)

  def initWallet(pass: String, mnemonicPass: Option[String]): Try[InitWalletResult] = {
    val body = new JsonObject
    body.addProperty("pass", pass)
    mnemonicPass.foreach(p => body.addProperty("mnemonicPass", p))
    postOne("/wallet/init", body.toString)(o => InitWalletResult(o.str("mnemonic")))
  }

  def restoreWallet(pass: String, mnemonic: String, mnemonicPass: Option[String], usePre1627KeyDerivation: Boolean): Try[Unit] = {
    val body = new JsonObject
    body.addProperty("pass", pass)
    body.addProperty("mnemonic", mnemonic)
    mnemonicPass.foreach(p => body.addProperty("mnemonicPass", p))
    body.addProperty("usePre1627KeyDerivation", java.lang.Boolean.valueOf(usePre1627KeyDerivation))
    http.postUnit("/wallet/restore", body.toString)
  }

  def checkWallet(mnemonic: String, mnemonicPass: Option[String]): Try[Boolean] = {
    val body = new JsonObject
    body.addProperty("mnemonic", mnemonic)
    mnemonicPass.foreach(p => body.addProperty("mnemonicPass", p))
    postOne("/wallet/check", body.toString)(_.bool("matched"))
  }

  def unlockWallet(pass: String): Try[Unit] = {
    val body = new JsonObject
    body.addProperty("pass", pass)
    http.postUnit("/wallet/unlock", body.toString)
  }

  def lockWallet(): Try[Unit] = http.getUnit("/wallet/lock")

  def rescanWallet(fromHeight: Int): Try[Unit] = {
    val body = new JsonObject
    body.addProperty("fromHeight", java.lang.Integer.valueOf(fromHeight))
    http.postUnit("/wallet/rescan", body.toString)
  }

  def walletAddresses(): Try[Seq[String]] = manyStrings("/wallet/addresses")

  def updateChangeAddress(address: String): Try[Unit] = http.postUnit("/wallet/updateChangeAddress", quoted(address))

  def deriveKey(derivationPath: String): Try[DerivedKey] = {
    val body = new JsonObject
    body.addProperty("derivationPath", derivationPath)
    postOne("/wallet/deriveKey", body.toString)(o => DerivedKey(o.str("address")))
  }

  def deriveNextKey(): Try[DerivedNextKey] =
    one("/wallet/deriveNextKey")(o => DerivedNextKey(o.optStr("derivationPath").getOrElse(""), o.str("address")))

  def walletBalances(): Try[WalletBalances] = one("/wallet/balances")(NodeCodecs.walletBalances)

  def walletBalancesWithUnconfirmed(): Try[WalletBalances] = one("/wallet/balances/withUnconfirmed")(NodeCodecs.walletBalances)

  def walletBoxes(range: ConfirmationRange, paging: Paging): Try[Seq[WalletBox]] =
    many("/wallet/boxes", rangeParams(range) ++ page(paging))(NodeCodecs.walletBox)

  def walletUnspentBoxes(range: ConfirmationRange, paging: Paging): Try[Seq[WalletBox]] =
    many("/wallet/boxes/unspent", rangeParams(range) ++ page(paging))(NodeCodecs.walletBox)

  def collectBoxes(request: BoxesRequest): Try[Seq[NodeBox]] =
    http.postJson("/wallet/boxes/collect", encodeBoxesRequest(request).toString).flatMap { e =>
      decode("/wallet/boxes/collect") {
        val root = obj(e)
        if (root.has("boxes")) root.objects("boxes").map(box) else asObjects(e).map(box)
      }
    }

  def walletTransactions(minInclusionHeight: Option[Int], maxInclusionHeight: Option[Int],
                         minConfirmations: Option[Int], maxConfirmations: Option[Int]): Try[Seq[WalletTransaction]] =
    many("/wallet/transactions", optParams(
      "minInclusionHeight" -> minInclusionHeight, "maxInclusionHeight" -> maxInclusionHeight,
      "minConfirmations" -> minConfirmations, "maxConfirmations" -> maxConfirmations))(NodeCodecs.walletTransaction)

  def walletTransactionById(txId: String): Try[Option[WalletTransaction]] =
    oneOpt("/wallet/transactionById", Seq("id" -> txId))(NodeCodecs.walletTransaction)

  def walletTransactionsByScanId(scanId: Int, minInclusionHeight: Option[Int], maxInclusionHeight: Option[Int],
                                 minConfirmations: Option[Int], maxConfirmations: Option[Int],
                                 includeUnconfirmed: Boolean): Try[Seq[WalletTransaction]] =
    many(s"/wallet/transactionsByScanId/$scanId", optParams(
      "minInclusionHeight" -> minInclusionHeight, "maxInclusionHeight" -> maxInclusionHeight,
      "minConfirmations" -> minConfirmations, "maxConfirmations" -> maxConfirmations) ++
      Seq("includeUnconfirmed" -> includeUnconfirmed.toString))(NodeCodecs.walletTransaction)

  def generateTransaction(request: TransactionGenerationRequest): Try[String] =
    http.postJson("/wallet/transaction/generate", encodeGenerationRequest(request).toString).map(_.toString)

  def generateUnsignedTransaction(request: TransactionGenerationRequest): Try[String] =
    http.postJson("/wallet/transaction/generateUnsigned", encodeGenerationRequest(request).toString).map(_.toString)

  def signTransaction(request: TransactionSigningRequest): Try[String] =
    http.postJson("/wallet/transaction/sign", encodeSigningRequest(request).toString).map(_.toString)

  def sendTransactionRequest(request: TransactionGenerationRequest): Try[String] =
    http.postText("/wallet/transaction/send", encodeGenerationRequest(request).toString)

  def sendPayment(payments: Seq[PaymentRequest]): Try[String] =
    http.postText("/wallet/payment/send", arrayOf(payments.map(encodePaymentRequest)).toString)

  def privateKey(request: PrivateKeyRequest): Try[DlogSecret] = {
    val body = new JsonObject
    body.addProperty("address", request.address)
    postOne("/wallet/getPrivateKey", body.toString)(o => DlogSecret(o.str("secret")))
  }

  def generateCommitments(requestJson: String): Try[String] =
    http.postJson("/wallet/generateCommitments", requestJson).map(_.toString)

  def extractHints(requestJson: String): Try[String] =
    http.postJson("/wallet/extractHints", requestJson).map(_.toString)

  def candidate(): Try[MiningCandidateMsg] = one("/mining/candidate")(NodeCodecs.candidate)

  def candidateWithTxs(txs: Seq[NodeTransaction]): Try[MiningCandidateMsg] =
    postOne("/mining/candidateWithTxs", arrayOf(txs.map(encodeTransaction)).toString)(NodeCodecs.candidate)

  def candidateWithTxsAndPk(txs: Seq[NodeTransaction], minerPk: String): Try[MiningCandidateMsg] = {
    val body = new JsonObject
    body.add("txs", arrayOf(txs.map(encodeTransaction)))
    body.addProperty("pk", minerPk)
    postOne("/mining/candidateWithTxsAndPk", body.toString)(NodeCodecs.candidate)
  }

  def candidateWithRawTxs(txsJson: Seq[String]): Try[MiningCandidateMsg] =
    decode("/mining/candidateWithTxs")(rawArray(txsJson).toString)
      .flatMap(body => postOne("/mining/candidateWithTxs", body)(NodeCodecs.candidate))

  def candidateWithRawTxsAndPk(txsJson: Seq[String], minerPk: String): Try[MiningCandidateMsg] =
    decode("/mining/candidateWithTxsAndPk") {
      val body = new JsonObject
      body.add("txs", rawArray(txsJson))
      body.addProperty("pk", minerPk)
      body.toString
    }.flatMap(body => postOne("/mining/candidateWithTxsAndPk", body)(NodeCodecs.candidate))

  def submitSolution(solution: MiningSolution): Try[Unit] =
    http.postUnit("/mining/solution", encodeSolution(solution).toString)

  def rewardAddress(): Try[RewardAddress] = one("/mining/rewardAddress")(o => RewardAddress(o.str("rewardAddress")))

  def rewardPublicKey(): Try[RewardPublicKey] =
    one("/mining/rewardPublicKey")(o => RewardPublicKey(o.str("rewardPubkey")))

  def p2sAddress(source: String): Try[String] = {
    val body = new JsonObject
    body.addProperty("source", source)
    postOne("/script/p2sAddress", body.toString)(_.str("address"))
  }

  def p2shAddress(source: String): Try[String] = {
    val body = new JsonObject
    body.addProperty("source", source)
    postOne("/script/p2shAddress", body.toString)(_.str("address"))
  }

  def addressToTree(address: String): Try[ErgoTreeObject] = one(s"/script/addressToTree/$address")(NodeCodecs.ergoTreeObject)

  def addressToBytes(address: String): Try[ErgoTreeObject] = one(s"/script/addressToBytes/$address")(NodeCodecs.ergoTreeObject)

  def executeWithContext(requestJson: String): Try[ExecuteScriptResult] =
    postOne("/script/executeWithContext", requestJson)(NodeCodecs.executeScriptResult)

  def seed(): Try[String] = http.getText("/utils/seed")

  def seedOfLength(length: Int): Try[String] = http.getText(s"/utils/seed/$length")

  def hashBlake2b(input: String): Try[String] = http.postText("/utils/hash/blake2b", quoted(input))

  def addressValidity(address: String): Try[AddressValidity] = one(s"/utils/address/$address")(NodeCodecs.addressValidity)

  def checkAddressValidity(address: String, ergoTreeHex: String): Try[AddressValidity] = {
    val body = new JsonObject
    body.addProperty("address", address)
    body.addProperty("ergoTree", ergoTreeHex)
    postOne("/utils/address", body.toString)(NodeCodecs.addressValidity)
  }

  def addressToRaw(address: String): Try[String] =
    one(s"/utils/addressToRaw/$address")(_.str("raw"))

  def rawToAddress(pubkeyHex: String): Try[String] =
    one(s"/utils/rawToAddress/$pubkeyHex")(_.str("address"))

  def ergoTreeToAddress(ergoTreeHex: String): Try[String] =
    one(s"/utils/ergoTreeToAddress/$ergoTreeHex")(_.str("address"))

  def registerScan(request: ScanRequest): Try[ScanId] =
    postOne("/scan/register", encodeScanRequest(request).toString)(NodeCodecs.scanId)

  def deregisterScan(id: Int): Try[ScanId] = {
    val body = new JsonObject
    body.addProperty("scanId", java.lang.Integer.valueOf(id))
    postOne("/scan/deregister", body.toString)(NodeCodecs.scanId)
  }

  def listScans(): Try[Seq[Scan]] = many("/scan/listAll")(NodeCodecs.scan)

  def scanUnspentBoxes(id: Int, minConfirmations: Int, maxConfirmations: Int,
                       minInclusionHeight: Int, maxInclusionHeight: Int): Try[Seq[WalletBox]] =
    many(s"/scan/unspentBoxes/$id", rangeParams(
      ConfirmationRange(minConfirmations, maxConfirmations, minInclusionHeight, maxInclusionHeight)))(NodeCodecs.walletBox)

  def scanSpentBoxes(id: Int): Try[Seq[WalletBox]] = many(s"/scan/spentBoxes/$id")(NodeCodecs.walletBox)

  def stopTracking(id: Int, boxId: String): Try[ScanIdBoxId] = {
    val body = new JsonObject
    body.addProperty("scanId", java.lang.Integer.valueOf(id))
    body.addProperty("boxId", boxId)
    postOne("/scan/stopTracking", body.toString)(NodeCodecs.scanIdBoxId)
  }

  def addBoxToScans(scanIds: Seq[Int], boxJson: String): Try[String] = {
    val body = new JsonObject
    body.add("scanIds", intArray(scanIds))
    body.add("box", new com.google.gson.JsonParser().parse(boxJson))
    http.postText("/scan/addBox", body.toString)
  }

  def addP2SRule(source: String): Try[ScanId] = postOne("/scan/p2sRule", quoted(source))(NodeCodecs.scanId)

  def allPeers(): Try[Seq[Peer]] = many("/peers/all")(NodeCodecs.peer)

  def connectedPeers(): Try[Seq[ConnectedPeer]] = many("/peers/connected")(NodeCodecs.connectedPeer)

  def connectToPeer(address: String): Try[Unit] = http.postUnit("/peers/connect", quoted(address))

  def blacklistedPeers(): Try[BlacklistedPeers] =
    one("/peers/blacklisted")(o => BlacklistedPeers(o.strings("addresses")))

  def peersStatus(): Try[PeersStatus] = one("/peers/status")(NodeCodecs.peersStatus)

  def peersSyncInfo(): Try[SyncInfo] = one("/peers/syncInfo")(NodeCodecs.syncInfo)

  def peersTrackInfo(): Try[TrackInfo] = one("/peers/trackInfo")(NodeCodecs.trackInfo)

  def emissionAt(height: Int): Try[EmissionInfo] = one(s"/emission/at/$height")(NodeCodecs.emissionInfo)

  def emissionScripts(): Try[EmissionScripts] = one("/emission/scripts")(NodeCodecs.emissionScripts)

  def shutdown(): Try[Unit] = http.postUnit("/node/shutdown", "")

  def indexedHeight(): Try[BlockchainIndexHeight] = one("/blockchain/indexedHeight")(NodeCodecs.indexHeight)

  lazy val indexerEnabled: Boolean = indexedHeight().isSuccess

  def indexedBlockByHeaderId(headerId: String): Try[Option[IndexedBlock]] =
    requireIndexer("/blockchain/block/byHeaderId")(
      oneOpt(s"/blockchain/block/byHeaderId/$headerId")(NodeCodecs.indexedBlock))

  def indexedBlocksByHeaderIds(headerIds: Seq[String]): Try[Seq[IndexedBlock]] =
    requireIndexer("/blockchain/block/byHeaderIds")(
      postMany("/blockchain/block/byHeaderIds", stringArray(headerIds).toString)(NodeCodecs.indexedBlock))

  def indexedTransactionById(txId: String): Try[Option[IndexedTransaction]] =
    requireIndexer("/blockchain/transaction/byId")(
      oneOpt(s"/blockchain/transaction/byId/$txId")(NodeCodecs.indexedTransaction))

  def indexedTransactionByIndex(globalIndex: Long): Try[Option[IndexedTransaction]] =
    requireIndexer("/blockchain/transaction/byIndex")(
      oneOpt(s"/blockchain/transaction/byIndex/$globalIndex")(NodeCodecs.indexedTransaction))

  def indexedTransactionsByAddress(address: String, paging: Paging): Try[Paged[IndexedTransaction]] =
    requireIndexer("/blockchain/transaction/byAddress")(
      http.postJson("/blockchain/transaction/byAddress", quoted(address), page(paging))
        .flatMap(e => decode("/blockchain/transaction/byAddress")(paged(obj(e))(NodeCodecs.indexedTransaction))))

  def indexedTransactionIdRange(paging: Paging): Try[Seq[String]] =
    requireIndexer("/blockchain/transaction/range")(manyStrings("/blockchain/transaction/range", page(paging)))

  def indexedBoxById(boxId: String): Try[Option[IndexedBox]] =
    requireIndexer("/blockchain/box/byId")(oneOpt(s"/blockchain/box/byId/$boxId")(NodeCodecs.indexedBox))

  def indexedBoxByIndex(globalIndex: Long): Try[Option[IndexedBox]] =
    requireIndexer("/blockchain/box/byIndex")(oneOpt(s"/blockchain/box/byIndex/$globalIndex")(NodeCodecs.indexedBox))

  def indexedBoxIdRange(paging: Paging): Try[Seq[String]] =
    requireIndexer("/blockchain/box/range")(manyStrings("/blockchain/box/range", page(paging)))

  def boxesByTokenId(tokenId: String, paging: Paging): Try[Paged[IndexedBox]] =
    requireIndexer("/blockchain/box/byTokenId")(
      http.getJson(s"/blockchain/box/byTokenId/$tokenId", page(paging))
        .flatMap(e => decode("/blockchain/box/byTokenId")(paged(obj(e))(NodeCodecs.indexedBox))))

  def unspentBoxesByTokenId(tokenId: String, paging: Paging, sort: SortDirection,
                            mempool: MempoolOptions): Try[Seq[IndexedBox]] =
    requireIndexer("/blockchain/box/unspent/byTokenId")(
      many(s"/blockchain/box/unspent/byTokenId/$tokenId",
        page(paging) ++ Seq("sortDirection" -> sort.value) ++ mempoolParams(mempool))(NodeCodecs.indexedBox))

  def boxesByAddress(address: String, paging: Paging): Try[Paged[IndexedBox]] =
    requireIndexer("/blockchain/box/byAddress")(
      http.postJson("/blockchain/box/byAddress", quoted(address), page(paging))
        .flatMap(e => decode("/blockchain/box/byAddress")(paged(obj(e))(NodeCodecs.indexedBox))))

  def unspentBoxesByAddress(address: String, paging: Paging, sort: SortDirection,
                            mempool: MempoolOptions): Try[Seq[IndexedBox]] =
    requireIndexer("/blockchain/box/unspent/byAddress")(
      http.postJson("/blockchain/box/unspent/byAddress", quoted(address),
        page(paging) ++ Seq("sortDirection" -> sort.value) ++ mempoolParams(mempool))
        .flatMap(e => decode("/blockchain/box/unspent/byAddress")(asObjects(e).map(indexedBox))))

  def boxesByErgoTree(ergoTree: String, paging: Paging): Try[Paged[IndexedBox]] =
    requireIndexer("/blockchain/box/byErgoTree")(
      http.postJson("/blockchain/box/byErgoTree", quoted(ergoTree), page(paging))
        .flatMap(e => decode("/blockchain/box/byErgoTree")(paged(obj(e))(NodeCodecs.indexedBox))))

  def unspentBoxesByErgoTree(ergoTree: String, paging: Paging, sort: SortDirection,
                             mempool: MempoolOptions): Try[Seq[IndexedBox]] =
    requireIndexer("/blockchain/box/unspent/byErgoTree")(
      http.postJson("/blockchain/box/unspent/byErgoTree", quoted(ergoTree),
        page(paging) ++ Seq("sortDirection" -> sort.value) ++ mempoolParams(mempool))
        .flatMap(e => decode("/blockchain/box/unspent/byErgoTree")(asObjects(e).map(indexedBox))))

  def boxesByTemplateHash(templateHash: String, paging: Paging): Try[Seq[IndexedBox]] =
    requireIndexer("/blockchain/box/byTemplateHash")(
      many(s"/blockchain/box/byTemplateHash/$templateHash", page(paging))(NodeCodecs.indexedBox))

  def unspentBoxesByTemplateHash(templateHash: String, paging: Paging, sort: SortDirection,
                                 mempool: MempoolOptions): Try[Seq[IndexedBox]] =
    requireIndexer("/blockchain/box/unspent/byTemplateHash")(
      many(s"/blockchain/box/unspent/byTemplateHash/$templateHash",
        page(paging) ++ Seq("sortDirection" -> sort.value) ++ mempoolParams(mempool))(NodeCodecs.indexedBox))

  def tokenById(tokenId: String): Try[Option[TokenInfo]] =
    requireIndexer("/blockchain/token/byId")(oneOpt(s"/blockchain/token/byId/$tokenId")(NodeCodecs.tokenInfo))

  def tokensByIds(tokenIds: Seq[String]): Try[Seq[TokenInfo]] =
    requireIndexer("/blockchain/tokens")(postMany("/blockchain/tokens", stringArray(tokenIds).toString)(NodeCodecs.tokenInfo))

  def addressBalance(address: String): Try[AddressBalance] =
    requireIndexer("/blockchain/balance")(postOne("/blockchain/balance", quoted(address))(NodeCodecs.addressBalance))

  private def quoted(s: String): String = {
    val p = new com.google.gson.JsonPrimitive(s)
    p.toString
  }
}
