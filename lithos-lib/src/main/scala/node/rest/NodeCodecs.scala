package node.rest

import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonPrimitive}
import node.model._
import node.rest.JsonOps._

object NodeCodecs {

  def asset(o: JsonObject): NodeAsset = NodeAsset(o.str("tokenId"), o.long("amount"))

  def registers(o: JsonObject, key: String): NodeRegisters = NodeRegisters(o.stringMap(key))

  def box(o: JsonObject): NodeBox = NodeBox(
    boxId = o.optStr("boxId").getOrElse(""),
    transactionId = o.optStr("transactionId").getOrElse(""),
    value = o.long("value"),
    index = o.optInt("index").getOrElse(0),
    creationHeight = o.int("creationHeight"),
    ergoTree = o.str("ergoTree"),
    assets = o.objects("assets").map(asset),
    additionalRegisters = registers(o, "additionalRegisters")
  )

  def spendingProof(o: JsonObject): NodeSpendingProof =
    NodeSpendingProof(o.optStr("proofBytes").getOrElse(""), o.stringMap("extension"))

  def indexedBox(o: JsonObject): IndexedBox = IndexedBox(
    box = box(o),
    address = o.optStr("address").getOrElse(""),
    inclusionHeight = o.optInt("inclusionHeight").getOrElse(0),
    globalIndex = o.optLong("globalIndex").getOrElse(0L),
    spentTransactionId = o.optStr("spentTransactionId"),
    spendingHeight = o.optInt("spendingHeight"),
    spendingProof = o.optObj("spendingProof").map(spendingProof)
  )

  def serializedBox(o: JsonObject): SerializedNodeBox =
    SerializedNodeBox(o.optStr("boxId").getOrElse(""), o.str("bytes"))

  def boxesBinaryProof(o: JsonObject): BoxesBinaryProof = BoxesBinaryProof(
    boxes = o.objects("boxes").map(serializedBox),
    proof = o.optStr("proof").getOrElse(""),
    digest = o.optStr("digest").getOrElse("")
  )

  def input(o: JsonObject): NodeInput =
    NodeInput(o.str("boxId"), o.optObj("spendingProof").map(spendingProof).getOrElse(NodeSpendingProof.empty))

  def dataInput(o: JsonObject): NodeDataInput = NodeDataInput(o.str("boxId"))

  def transaction(o: JsonObject): NodeTransaction = NodeTransaction(
    id = o.optStr("id").getOrElse(""),
    inputs = o.objects("inputs").map(input),
    dataInputs = o.objects("dataInputs").map(dataInput),
    outputs = o.objects("outputs").map(box),
    size = o.optInt("size")
  )

  def indexedTransaction(o: JsonObject): IndexedTransaction = IndexedTransaction(
    id = o.str("id"),
    inputs = o.objects("inputs").map(indexedBox),
    dataInputs = o.objects("dataInputs").map(dataInput),
    outputs = o.objects("outputs").map(indexedBox),
    inclusionHeight = o.int("inclusionHeight"),
    numConfirmations = o.optInt("numConfirmations").getOrElse(0),
    blockId = o.optStr("blockId").getOrElse(""),
    timestamp = o.optLong("timestamp").getOrElse(0L),
    index = o.optInt("index").getOrElse(0),
    globalIndex = o.optLong("globalIndex").getOrElse(0L),
    size = o.optInt("size").getOrElse(0)
  )

  def unconfirmedTransaction(o: JsonObject): UnconfirmedTransaction = UnconfirmedTransaction(
    transaction = transaction(o),
    size = o.optInt("size").getOrElse(0),
    spendingProofsSize = o.optInt("spendingProofsSize"),
    createdAt = o.optLong("createdAt")
  )

  def feeHistogramBin(o: JsonObject): FeeHistogramBin =
    FeeHistogramBin(o.optInt("nTxns").getOrElse(0), o.optLong("totalFee").getOrElse(0L))

  def poolHistogram(o: JsonObject): PoolHistogram = PoolHistogram(
    FeeHistogram(o.objects("waiting").map(feeHistogramBin)),
    FeeHistogram(o.objects("mined").map(feeHistogramBin))
  )

  def powSolution(o: JsonObject): NodePowSolution = NodePowSolution(
    pk = o.optStr("pk").getOrElse(""),
    w = o.optStr("w").getOrElse(""),
    n = o.optStr("n").getOrElse(""),
    d = o.optStr("d").getOrElse("0")
  )

  def header(o: JsonObject): NodeHeader = NodeHeader(
    id = o.str("id"),
    parentId = o.str("parentId"),
    height = o.int("height"),
    timestamp = o.long("timestamp"),
    version = o.int("version"),
    nBits = o.long("nBits"),
    difficulty = o.optBigInt("difficulty").getOrElse(BigInt(0)),
    adProofsRoot = o.optStr("adProofsRoot").getOrElse(""),
    stateRoot = o.optStr("stateRoot").getOrElse(""),
    transactionsRoot = o.optStr("transactionsRoot").getOrElse(""),
    extensionHash = o.optStr("extensionHash").getOrElse(""),
    powSolutions = o.optObj("powSolutions").map(powSolution).getOrElse(NodePowSolution("", "", "", "0")),
    votes = o.optStr("votes").getOrElse(""),
    size = o.optInt("size"),
    extensionId = o.optStr("extensionId"),
    transactionsId = o.optStr("transactionsId"),
    adProofsId = o.optStr("adProofsId")
  )

  def extensionField(e: JsonElement): NodeExtensionField = {
    val pair = jsonArrayToSeq(e)
    if (pair.size >= 2) NodeExtensionField(pair.head.getAsString, pair(1).getAsString)
    else NodeExtensionField("", "")
  }

  def extension(o: JsonObject): NodeExtension = NodeExtension(
    headerId = o.optStr("headerId").getOrElse(""),
    digest = o.optStr("digest").getOrElse(""),
    fields = Option(o.get("fields")).map(jsonArrayToSeq).getOrElse(Seq.empty[JsonElement]).map(extensionField)
  )

  def adProofs(o: JsonObject): NodeAdProofs = NodeAdProofs(
    headerId = o.optStr("headerId").getOrElse(""),
    proofBytes = o.optStr("proofBytes").getOrElse(""),
    digest = o.optStr("digest").getOrElse(""),
    size = o.optInt("size").getOrElse(0)
  )

  def blockTransactions(o: JsonObject): NodeBlockTransactions = NodeBlockTransactions(
    headerId = o.optStr("headerId").getOrElse(""),
    transactions = o.objects("transactions").map(transaction),
    size = o.optInt("size").getOrElse(0)
  )

  def block(o: JsonObject): NodeBlock = NodeBlock(
    header = header(o.obj("header")),
    blockTransactions = o.optObj("blockTransactions").map(blockTransactions)
      .getOrElse(NodeBlockTransactions("", Seq.empty[NodeTransaction], 0)),
    extension = o.optObj("extension").map(extension).getOrElse(NodeExtension("", "", Seq.empty[NodeExtensionField])),
    adProofs = o.optObj("adProofs").map(adProofs),
    size = o.optInt("size").getOrElse(0)
  )

  def indexedBlock(o: JsonObject): IndexedBlock = IndexedBlock(
    header = header(o.obj("header")),
    blockTransactions = o.optObj("blockTransactions").map(_.objects("transactions").map(indexedTransaction))
      .getOrElse(o.objects("blockTransactions").map(indexedTransaction)),
    extension = o.optObj("extension").map(extension).getOrElse(NodeExtension("", "", Seq.empty[NodeExtensionField])),
    adProofs = o.optObj("adProofs").map(adProofs),
    size = o.optInt("size").getOrElse(0)
  )

  def merkleLevel(e: JsonElement): MerkleLevel =
    if (e.isJsonArray) {
      val pair = jsonArrayToSeq(e)
      if (pair.size >= 2) MerkleLevel(pair.head.getAsString, pair(1).getAsInt) else MerkleLevel("", 0)
    } else {
      MerkleLevel.fromEncoded(e.getAsString)
    }

  private def merkleLevels(o: JsonObject): Seq[MerkleLevel] =
    Option(o.get("levels")).map(jsonArrayToSeq).getOrElse(Seq.empty[JsonElement]).map(merkleLevel)

  def blockTxProof(o: JsonObject): NodeMerkleProof = NodeMerkleProof(
    leaf = o.optStr("leafData").orElse(o.optStr("leaf")).getOrElse(""),
    levels = merkleLevels(o)
  )

  def membershipProof(o: JsonObject): NodeMerkleProof = NodeMerkleProof(
    leaf = o.optStr("leaf").orElse(o.optStr("leafData")).getOrElse(""),
    levels = merkleLevels(o)
  )

  def popowHeader(o: JsonObject): PopowHeader =
    PopowHeader(header(o.obj("header")), o.strings("interlinks"))

  def nipopowProof(o: JsonObject): NipopowProof = NipopowProof(
    m = o.optInt("m").getOrElse(0),
    k = o.optInt("k").getOrElse(0),
    prefix = o.objects("prefix").map(popowHeader),
    suffixHead = o.optObj("suffixHead").map(popowHeader)
      .getOrElse(PopowHeader(header(new JsonObject()), Seq.empty[String])),
    suffixTail = o.objects("suffixTail").map(header)
  )

  def proofOfUpcomingTransactions(o: JsonObject): ProofOfUpcomingTransactions = ProofOfUpcomingTransactions(
    msgPreimage = o.optStr("msgPreimage").getOrElse(""),
    txProofs = o.objects("txProofs").map(membershipProof)
  )

  def candidate(o: JsonObject): MiningCandidateMsg = MiningCandidateMsg(
    msg = o.str("msg"),
    b = o.optBigInt("b").getOrElse(BigInt(0)),
    pk = o.str("pk"),
    h = o.optInt("h"),
    proof = o.optObj("proof").map(proofOfUpcomingTransactions)
  )

  def walletBox(o: JsonObject): WalletBox = WalletBox(
    box = box(o.obj("box")),
    address = o.optStr("address").getOrElse(""),
    confirmationsNum = o.optInt("confirmationsNum"),
    creationTransaction = o.optStr("creationTransaction").getOrElse(""),
    creationOutIndex = o.optInt("creationOutIndex").getOrElse(0),
    inclusionHeight = o.optInt("inclusionHeight"),
    spendingTransaction = o.optStr("spendingTransaction"),
    spendingHeight = o.optInt("spendingHeight"),
    spent = o.bool("spent"),
    onchain = o.bool("onchain"),
    scans = o.ints("scans")
  )

  def walletTransaction(o: JsonObject): WalletTransaction = WalletTransaction(
    id = o.str("id"),
    inputs = o.objects("inputs").map(input),
    dataInputs = o.objects("dataInputs").map(dataInput),
    outputs = o.objects("outputs").map(box),
    inclusionHeight = o.optInt("inclusionHeight").getOrElse(0),
    numConfirmations = o.optInt("numConfirmations").getOrElse(0),
    scans = o.ints("scans"),
    size = o.optInt("size")
  )

  /**
   * `/wallet/balances` and `/wallet/balances/withUnconfirmed`.
   *
   * `assets` here is an OBJECT of `tokenId -> amount`, not the array of `{tokenId, amount}` a box
   * carries under the same name. Reading it as an array is silent — the array helpers return empty
   * for a non-array — so every balance came back with no tokens at all.
   */
  def walletBalances(o: JsonObject): WalletBalances = WalletBalances(
    height = o.optInt("height").getOrElse(0),
    balance = o.optLong("balance").getOrElse(0L),
    assets = o.longEntries("assets").map { case (tokenId, amount) => NodeAsset(tokenId, amount) }
  )

  def walletStatus(o: JsonObject): WalletStatus = WalletStatus(
    isInitialized = o.bool("isInitialized"),
    isUnlocked = o.bool("isUnlocked"),
    changeAddress = o.optStr("changeAddress").getOrElse(""),
    walletHeight = o.optInt("walletHeight").getOrElse(0),
    error = o.optStr("error").filter(_.nonEmpty)
  )

  def parameters(o: JsonObject): NodeParameters = NodeParameters(
    height = o.optInt("height").getOrElse(0),
    storageFeeFactor = o.optInt("storageFeeFactor").getOrElse(0),
    minValuePerByte = o.optInt("minValuePerByte").getOrElse(0),
    maxBlockSize = o.optInt("maxBlockSize").getOrElse(0),
    maxBlockCost = o.optInt("maxBlockCost").getOrElse(0),
    blockVersion = o.optInt("blockVersion").getOrElse(0),
    tokenAccessCost = o.optInt("tokenAccessCost").getOrElse(0),
    inputCost = o.optInt("inputCost").getOrElse(0),
    dataInputCost = o.optInt("dataInputCost").getOrElse(0),
    outputCost = o.optInt("outputCost").getOrElse(0)
  )

  def info(o: JsonObject): NodeInfo = NodeInfo(
    name = o.optStr("name").getOrElse(""),
    appVersion = o.optStr("appVersion").getOrElse(""),
    fullHeight = o.optInt("fullHeight"),
    headersHeight = o.optInt("headersHeight"),
    maxPeerHeight = o.optInt("maxPeerHeight"),
    bestFullHeaderId = o.optStr("bestFullHeaderId"),
    previousFullHeaderId = o.optStr("previousFullHeaderId"),
    bestHeaderId = o.optStr("bestHeaderId"),
    stateRoot = o.optStr("stateRoot"),
    stateType = o.optStr("stateType").getOrElse(""),
    stateVersion = o.optStr("stateVersion"),
    isMining = o.bool("isMining"),
    peersCount = o.optInt("peersCount").getOrElse(0),
    unconfirmedCount = o.optInt("unconfirmedCount").getOrElse(0),
    difficulty = o.optBigInt("difficulty"),
    currentTime = o.optLong("currentTime").getOrElse(0L),
    launchTime = o.optLong("launchTime").getOrElse(0L),
    headersScore = o.optBigInt("headersScore"),
    fullBlocksScore = o.optBigInt("fullBlocksScore"),
    genesisBlockId = o.optStr("genesisBlockId"),
    parameters = o.optObj("parameters").map(parameters).getOrElse(parameters(new JsonObject())),
    eip27Supported = o.optBool("eip27Supported"),
    restApiUrl = o.optStr("restApiUrl"),
    isExplorer = o.optBool("isExplorer")
  )

  def indexHeight(o: JsonObject): BlockchainIndexHeight =
    BlockchainIndexHeight(o.optInt("indexedHeight").getOrElse(0), o.optInt("fullHeight").getOrElse(0))

  def tokenInfo(o: JsonObject): TokenInfo = TokenInfo(
    id = o.str("id"),
    boxId = o.optStr("boxId").getOrElse(""),
    emissionAmount = o.optLong("emissionAmount").getOrElse(0L),
    name = o.optStr("name").getOrElse(""),
    description = o.optStr("description").getOrElse(""),
    decimals = o.optInt("decimals").getOrElse(0)
  )

  def tokenBalance(o: JsonObject): TokenBalance =
    TokenBalance(o.str("tokenId"), o.long("amount"), o.optInt("decimals"), o.optStr("name"))

  def balanceInfo(o: JsonObject): BalanceInfo =
    BalanceInfo(o.optLong("nanoErgs").getOrElse(0L), o.objects("tokens").map(tokenBalance))

  def addressBalance(o: JsonObject): AddressBalance =
    AddressBalance(o.optObj("confirmed").map(balanceInfo), o.optObj("unconfirmed").map(balanceInfo))

  def emissionInfo(o: JsonObject): EmissionInfo = EmissionInfo(
    minerReward = o.optLong("minerReward").getOrElse(0L),
    totalCoinsIssued = o.optLong("totalCoinsIssued").getOrElse(0L),
    totalRemainCoins = o.optLong("totalRemainCoins").getOrElse(0L),
    reemitted = o.optLong("reemitted")
  )

  def emissionScripts(o: JsonObject): EmissionScripts = EmissionScripts(
    emission = o.optStr("emission").getOrElse(""),
    reemission = o.optStr("reemission").getOrElse(""),
    pay2Reemission = o.optStr("pay2Reemission").getOrElse("")
  )

  def snapshotsInfo(o: JsonObject): SnapshotsInfo = SnapshotsInfo(
    o.objects("availableManifests").map(m =>
      SnapshotInfo(m.optInt("height").getOrElse(0), m.optStr("digest").getOrElse("")))
  )

  def addressValidity(o: JsonObject): AddressValidity =
    AddressValidity(o.optStr("address").getOrElse(""), o.bool("isValid"), o.optStr("error"))

  def ergoTreeObject(o: JsonObject): ErgoTreeObject = ErgoTreeObject(o.str("tree"))

  def executeScriptResult(o: JsonObject): ExecuteScriptResult =
    ExecuteScriptResult(o.optStr("value").getOrElse(""), o.optLong("cost").getOrElse(0L))

  def peer(o: JsonObject): Peer = Peer(
    address = o.optStr("address").getOrElse(""),
    name = o.optStr("name"),
    lastSeen = o.optLong("lastSeen"),
    connectionType = o.optStr("connectionType")
  )

  def peerMode(o: JsonObject): PeerMode = PeerMode(
    name = o.optStr("name").getOrElse(""),
    appVersion = o.optStr("appVersion").getOrElse(""),
    stateType = o.optStr("stateType").getOrElse(""),
    verifying = o.bool("verifying"),
    fullBlockHeight = o.optInt("fullBlockHeight")
  )

  def connectedPeer(o: JsonObject): ConnectedPeer = ConnectedPeer(
    address = o.optStr("address").getOrElse(""),
    name = o.optStr("name"),
    connectionType = o.optStr("connectionType"),
    mode = o.optObj("mode").map(peerMode)
  )

  def peersStatus(o: JsonObject): PeersStatus =
    PeersStatus(o.optLong("lastIncomingMessage").getOrElse(0L), o.optLong("currentNetworkTime").getOrElse(0L))

  def syncInfo(o: JsonObject): SyncInfo = SyncInfo(o.optInt("fullHeight"), o.optInt("maxPeerHeight"))

  def trackInfo(o: JsonObject): TrackInfo = TrackInfo(
    invalidModifierApproxSize = o.optInt("invalidModifierApproxSize").getOrElse(0),
    requested = o.stringMap("requested"),
    received = o.stringMap("received")
  )

  def scan(o: JsonObject): Scan = Scan(
    scanId = o.optInt("scanId").getOrElse(0),
    scanName = o.optStr("scanName").getOrElse(""),
    trackingRule = Option(o.get("trackingRule")).map(_.toString).getOrElse(""),
    walletInteraction = o.optStr("walletInteraction")
  )

  def scanId(o: JsonObject): ScanId = ScanId(o.optInt("scanId").getOrElse(0))

  def scanIdBoxId(o: JsonObject): ScanIdBoxId =
    ScanIdBoxId(o.optInt("scanId").getOrElse(0), o.optStr("boxId").getOrElse(""))

  def paged[A](o: JsonObject)(f: JsonObject => A): Paged[A] =
    Paged(o.objects("items").map(f), o.optInt("total").getOrElse(0))

  def encodeRegisters(regs: NodeRegisters): JsonObject = {
    val out = new JsonObject
    regs.values.foreach { case (k, v) => out.addProperty(k, v) }
    out
  }

  def encodeAsset(a: NodeAsset): JsonObject = {
    val out = new JsonObject
    out.addProperty("tokenId", a.tokenId)
    out.addProperty("amount", java.lang.Long.valueOf(a.amount))
    out
  }

  def encodeBox(b: NodeBox): JsonObject = {
    val out = new JsonObject
    if (b.boxId.nonEmpty) out.addProperty("boxId", b.boxId)
    out.addProperty("value", java.lang.Long.valueOf(b.value))
    out.addProperty("ergoTree", b.ergoTree)
    out.addProperty("creationHeight", java.lang.Integer.valueOf(b.creationHeight))
    out.add("assets", arrayOf(b.assets.map(encodeAsset)))
    out.add("additionalRegisters", encodeRegisters(b.additionalRegisters))
    if (b.transactionId.nonEmpty) out.addProperty("transactionId", b.transactionId)
    out.addProperty("index", java.lang.Integer.valueOf(b.index))
    out
  }

  def encodeSpendingProof(p: NodeSpendingProof): JsonObject = {
    val out = new JsonObject
    out.addProperty("proofBytes", p.proofBytes)
    val ext = new JsonObject
    p.extension.foreach { case (k, v) => ext.addProperty(k, v) }
    out.add("extension", ext)
    out
  }

  def encodeTransaction(tx: NodeTransaction): JsonObject = {
    val out = new JsonObject
    if (tx.id.nonEmpty) out.addProperty("id", tx.id)
    out.add("inputs", arrayOf(tx.inputs.map { i =>
      val io = new JsonObject
      io.addProperty("boxId", i.boxId)
      io.add("spendingProof", encodeSpendingProof(i.spendingProof))
      io
    }))
    out.add("dataInputs", arrayOf(tx.dataInputs.map { d =>
      val dobj = new JsonObject
      dobj.addProperty("boxId", d.boxId)
      dobj
    }))
    out.add("outputs", arrayOf(tx.outputs.map(encodeBox)))
    out
  }

  def encodePaymentRequest(p: PaymentRequest): JsonObject = {
    val out = new JsonObject
    out.addProperty("address", p.address)
    out.addProperty("value", java.lang.Long.valueOf(p.value))
    if (p.assets.nonEmpty) out.add("assets", arrayOf(p.assets.map(encodeAsset)))
    if (!p.registers.isEmpty) out.add("registers", encodeRegisters(p.registers))
    out
  }

  def encodeGenerationRequest(r: TransactionGenerationRequest): JsonObject = {
    val out = new JsonObject
    out.add("requests", arrayOf(r.requests.map(encodePaymentRequest)))
    r.fee.foreach(f => out.addProperty("fee", java.lang.Long.valueOf(f)))
    if (r.inputsRaw.nonEmpty) out.add("inputsRaw", stringArray(r.inputsRaw))
    if (r.dataInputsRaw.nonEmpty) out.add("dataInputsRaw", stringArray(r.dataInputsRaw))
    out
  }

  def encodeBoxesRequest(r: BoxesRequest): JsonObject = {
    val out = new JsonObject
    out.addProperty("targetBalance", java.lang.Long.valueOf(r.targetBalance))
    val assets = new JsonObject
    r.targetAssets.foreach { case (k, v) => assets.addProperty(k, java.lang.Long.valueOf(v)) }
    out.add("targetAssets", assets)
    out
  }

  def encodeSigningRequest(r: TransactionSigningRequest): JsonObject = {
    val out = new JsonObject
    out.add("tx", new com.google.gson.JsonParser().parse(r.unsignedTx))
    if (r.inputsRaw.nonEmpty) out.add("inputsRaw", stringArray(r.inputsRaw))
    if (r.dataInputsRaw.nonEmpty) out.add("dataInputsRaw", stringArray(r.dataInputsRaw))
    r.hints.foreach(h => out.add("hints", new com.google.gson.JsonParser().parse(h)))
    if (r.secretsDlog.nonEmpty || r.secretsDht.nonEmpty) {
      val secrets = new JsonObject
      if (r.secretsDlog.nonEmpty) secrets.add("dlog", stringArray(r.secretsDlog))
      if (r.secretsDht.nonEmpty) secrets.add("dht", stringArray(r.secretsDht))
      out.add("secrets", secrets)
    }
    out
  }

  def encodeSolution(s: MiningSolution): JsonObject = {
    val out = new JsonObject
    out.addProperty("pk", s.pk)
    out.addProperty("w", s.w)
    out.addProperty("n", s.n)
    out.add("d", new JsonPrimitive(new java.math.BigInteger(s.d)))
    out
  }

  def encodeScanRequest(r: ScanRequest): JsonObject = {
    val out = new JsonObject
    out.addProperty("scanName", r.scanName)
    out.add("trackingRule", new com.google.gson.JsonParser().parse(r.trackingRule))
    r.removeOffchain.foreach(b => out.addProperty("removeOffchain", java.lang.Boolean.valueOf(b)))
    out
  }

  def arrayOf(objects: Seq[JsonObject]): JsonArray = {
    val arr = new JsonArray
    objects.foreach(arr.add)
    arr
  }

  def stringArray(values: Seq[String]): JsonArray = {
    val arr = new JsonArray
    values.foreach(arr.add)
    arr
  }

  def intArray(values: Seq[Int]): JsonArray = {
    val arr = new JsonArray
    values.foreach(v => arr.add(java.lang.Integer.valueOf(v)))
    arr
  }

  def rawArray(jsonValues: Seq[String]): JsonArray = {
    val parser = new com.google.gson.JsonParser()
    val arr = new JsonArray
    jsonValues.foreach(v => arr.add(parser.parse(v)))
    arr
  }
}
