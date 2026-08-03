package node

import node.model._

import scala.util.Try

/**
 * Blocking, `Try`-returning view of the Ergo node REST API.
 *
 * Every call maps one-to-one onto an endpoint of the node's openapi spec (6.0.3). Failures are
 * returned as [[NodeError]] inside the `Try`; `Option` is used only where the node treats absence
 * as a normal answer rather than an error.
 */
trait NodeApi
  extends NodeInfoApi
    with NodeBlocksApi
    with NodeNipopowApi
    with NodeTransactionsApi
    with NodeUtxoApi
    with NodeWalletApi
    with NodeMiningApi
    with NodeScriptApi
    with NodeUtilsApi
    with NodeScanApi
    with NodePeersApi
    with NodeEmissionApi
    with NodeControlApi
    with NodeIndexerApi

trait NodeInfoApi {
  def info(): Try[NodeInfo]

  def isOnline: Boolean
}

trait NodeBlocksApi {
  def headerIds(fromHeight: Option[Int] = None, toHeight: Option[Int] = None): Try[Seq[String]]

  def headerIdsAtHeight(height: Int): Try[Seq[String]]

  def headerIdsByHeights(heights: Seq[Int]): Try[Seq[String]]

  def sendBlock(blockJson: String): Try[String]

  def chainSlice(fromHeight: Option[Int] = None, toHeight: Option[Int] = None): Try[Seq[NodeHeader]]

  def lastHeaders(count: Int): Try[Seq[NodeHeader]]

  def block(headerId: String): Try[Option[NodeBlock]]

  def blockAt(height: Int): Try[Seq[NodeBlock]]

  def header(headerId: String): Try[Option[NodeHeader]]

  def blockTransactions(headerId: String): Try[Option[NodeBlockTransactions]]

  def txProof(headerId: String, txId: String): Try[Option[NodeMerkleProof]]

  def modifier(modifierId: String): Try[Option[String]]
}

trait NodeNipopowApi {
  def popowHeaderById(headerId: String): Try[Option[PopowHeader]]

  def popowHeaderByHeight(height: Int): Try[Option[PopowHeader]]

  def nipopowProof(minChainLength: Int, suffixLength: Int): Try[NipopowProof]

  def nipopowProofByHeaderId(minChainLength: Int, suffixLength: Int, headerId: String): Try[NipopowProof]
}

trait NodeTransactionsApi {
  def sendTransaction(txJson: String): Try[String]

  def checkTransaction(txJson: String): Try[String]

  def unconfirmedTransactions(paging: Paging = Paging.Default): Try[Seq[NodeTransaction]]

  def unconfirmedTransactionIds(): Try[Seq[String]]

  def unconfirmedTransactionById(txId: String): Try[Option[NodeTransaction]]

  def unconfirmedTransactionsByIds(txIds: Seq[String]): Try[Seq[NodeTransaction]]

  def unconfirmedTransactionsByErgoTree(ergoTree: String, paging: Paging = Paging.Default): Try[Seq[NodeTransaction]]

  def unconfirmedInputByBoxId(boxId: String): Try[Option[NodeTransaction]]

  def unconfirmedOutputByBoxId(boxId: String): Try[Option[NodeBox]]

  def unconfirmedOutputsByErgoTree(ergoTree: String, paging: Paging = Paging.Default): Try[Seq[NodeBox]]

  def unconfirmedOutputsByTokenId(tokenId: String): Try[Seq[NodeBox]]

  def unconfirmedOutputsByRegisters(registers: NodeRegisters, paging: Paging = Paging.Default): Try[Seq[NodeBox]]

  def poolHistogram(): Try[PoolHistogram]

  def recommendedFee(waitTimeMinutes: Int, txSizeBytes: Int): Try[Long]

  def expectedWaitTime(fee: Long, txSizeBytes: Int): Try[Int]
}

trait NodeUtxoApi {
  def boxById(boxId: String): Try[Option[NodeBox]]

  def boxByIdBinary(boxId: String): Try[Option[SerializedNodeBox]]

  def boxWithPoolById(boxId: String): Try[Option[NodeBox]]

  def boxWithPoolByIdBinary(boxId: String): Try[Option[SerializedNodeBox]]

  def boxesWithPoolByIds(boxIds: Seq[String]): Try[Seq[NodeBox]]

  def boxesBinaryProof(boxIds: Seq[String]): Try[Option[BoxesBinaryProof]]

  def snapshotsInfo(): Try[SnapshotsInfo]

  def genesisBoxes(): Try[Seq[NodeBox]]
}

/**
 * Wallet endpoints. All of these require a valid api_key, and everything past `unlock` also
 * requires the wallet to be unlocked.
 */
trait NodeWalletApi {
  def walletStatus(): Try[WalletStatus]

  def initWallet(pass: String, mnemonicPass: Option[String] = None): Try[InitWalletResult]

  def restoreWallet(pass: String, mnemonic: String, mnemonicPass: Option[String] = None, usePre1627KeyDerivation: Boolean = false): Try[Unit]

  def checkWallet(mnemonic: String, mnemonicPass: Option[String] = None): Try[Boolean]

  def unlockWallet(pass: String): Try[Unit]

  def lockWallet(): Try[Unit]

  def rescanWallet(fromHeight: Int = 0): Try[Unit]

  def walletAddresses(): Try[Seq[String]]

  def updateChangeAddress(address: String): Try[Unit]

  def deriveKey(derivationPath: String): Try[DerivedKey]

  def deriveNextKey(): Try[DerivedNextKey]

  def walletBalances(): Try[WalletBalances]

  def walletBalancesWithUnconfirmed(): Try[WalletBalances]

  def walletBoxes(range: ConfirmationRange = ConfirmationRange.Default,
                  paging: Paging = Paging.Default): Try[Seq[WalletBox]]

  def walletUnspentBoxes(range: ConfirmationRange = ConfirmationRange.Default,
                         paging: Paging = Paging.Default): Try[Seq[WalletBox]]

  def collectBoxes(request: BoxesRequest): Try[Seq[NodeBox]]

  def walletTransactions(minInclusionHeight: Option[Int] = None,
                         maxInclusionHeight: Option[Int] = None,
                         minConfirmations: Option[Int] = None,
                         maxConfirmations: Option[Int] = None): Try[Seq[WalletTransaction]]

  def walletTransactionById(txId: String): Try[Option[WalletTransaction]]

  def walletTransactionsByScanId(scanId: Int,
                                 minInclusionHeight: Option[Int] = None,
                                 maxInclusionHeight: Option[Int] = None,
                                 minConfirmations: Option[Int] = None,
                                 maxConfirmations: Option[Int] = None,
                                 includeUnconfirmed: Boolean = false): Try[Seq[WalletTransaction]]

  def generateTransaction(request: TransactionGenerationRequest): Try[String]

  def generateUnsignedTransaction(request: TransactionGenerationRequest): Try[String]

  def signTransaction(request: TransactionSigningRequest): Try[String]

  def sendTransactionRequest(request: TransactionGenerationRequest): Try[String]

  def sendPayment(payments: Seq[PaymentRequest]): Try[String]

  def privateKey(request: PrivateKeyRequest): Try[DlogSecret]

  def generateCommitments(requestJson: String): Try[String]

  def extractHints(requestJson: String): Try[String]
}

/**
 * Mining endpoints, all api_key protected.
 *
 * `candidateWithTxsAndPk` is the only variant that lets the caller choose the coinbase recipient;
 * it was added to the node in 6.0.2 and is not present in older nodes.
 */
trait NodeMiningApi {
  def candidate(): Try[MiningCandidateMsg]

  def candidateWithTxs(txs: Seq[NodeTransaction]): Try[MiningCandidateMsg]

  def candidateWithTxsAndPk(txs: Seq[NodeTransaction], minerPk: String): Try[MiningCandidateMsg]

  def candidateWithRawTxs(txsJson: Seq[String]): Try[MiningCandidateMsg]

  def candidateWithRawTxsAndPk(txsJson: Seq[String], minerPk: String): Try[MiningCandidateMsg]

  def submitSolution(solution: MiningSolution): Try[Unit]

  def rewardAddress(): Try[RewardAddress]

  def rewardPublicKey(): Try[RewardPublicKey]
}

trait NodeScriptApi {
  def p2sAddress(source: String): Try[String]

  def p2shAddress(source: String): Try[String]

  def addressToTree(address: String): Try[ErgoTreeObject]

  def addressToBytes(address: String): Try[ErgoTreeObject]

  def executeWithContext(requestJson: String): Try[ExecuteScriptResult]
}

trait NodeUtilsApi {
  def seed(): Try[String]

  def seedOfLength(length: Int): Try[String]

  def hashBlake2b(input: String): Try[String]

  def addressValidity(address: String): Try[AddressValidity]

  def checkAddressValidity(address: String, ergoTreeHex: String): Try[AddressValidity]

  def addressToRaw(address: String): Try[String]

  def rawToAddress(pubkeyHex: String): Try[String]

  def ergoTreeToAddress(ergoTreeHex: String): Try[String]
}

trait NodeScanApi {
  def registerScan(request: ScanRequest): Try[ScanId]

  def deregisterScan(scanId: Int): Try[ScanId]

  def listScans(): Try[Seq[Scan]]

  def scanUnspentBoxes(scanId: Int,
                       minConfirmations: Int = 0,
                       maxConfirmations: Int = -1,
                       minInclusionHeight: Int = 0,
                       maxInclusionHeight: Int = -1): Try[Seq[WalletBox]]

  def scanSpentBoxes(scanId: Int): Try[Seq[WalletBox]]

  def stopTracking(scanId: Int, boxId: String): Try[ScanIdBoxId]

  def addBoxToScans(scanIds: Seq[Int], boxJson: String): Try[String]

  def addP2SRule(source: String): Try[ScanId]
}

trait NodePeersApi {
  def allPeers(): Try[Seq[Peer]]

  def connectedPeers(): Try[Seq[ConnectedPeer]]

  def connectToPeer(address: String): Try[Unit]

  def blacklistedPeers(): Try[BlacklistedPeers]

  def peersStatus(): Try[PeersStatus]

  def peersSyncInfo(): Try[SyncInfo]

  def peersTrackInfo(): Try[TrackInfo]
}

trait NodeEmissionApi {
  def emissionAt(height: Int): Try[EmissionInfo]

  def emissionScripts(): Try[EmissionScripts]
}

trait NodeControlApi {
  def shutdown(): Try[Unit]
}

/**
 * Indexer endpoints, served only by a node started with `extraIndex = true`;
 * on a plain node every call here fails with [[NodeError.IndexerDisabled]].
 *
 * Use [[indexerEnabled]] to branch before relying on them.
 */
trait NodeIndexerApi {
  def indexedHeight(): Try[BlockchainIndexHeight]

  def indexerEnabled: Boolean

  def indexedBlockByHeaderId(headerId: String): Try[Option[IndexedBlock]]

  def indexedBlocksByHeaderIds(headerIds: Seq[String]): Try[Seq[IndexedBlock]]

  def indexedTransactionById(txId: String): Try[Option[IndexedTransaction]]

  def indexedTransactionByIndex(globalIndex: Long): Try[Option[IndexedTransaction]]

  def indexedTransactionsByAddress(address: String, paging: Paging = Paging.Default): Try[Paged[IndexedTransaction]]

  def indexedTransactionIdRange(paging: Paging = Paging.Default): Try[Seq[String]]

  def indexedBoxById(boxId: String): Try[Option[IndexedBox]]

  def indexedBoxByIndex(globalIndex: Long): Try[Option[IndexedBox]]

  def indexedBoxIdRange(paging: Paging = Paging.Default): Try[Seq[String]]

  def boxesByTokenId(tokenId: String, paging: Paging = Paging.Default): Try[Paged[IndexedBox]]

  def unspentBoxesByTokenId(tokenId: String,
                            paging: Paging = Paging.Default,
                            sort: SortDirection = SortDirection.Desc,
                            mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Try[Seq[IndexedBox]]

  def boxesByAddress(address: String, paging: Paging = Paging.Default): Try[Paged[IndexedBox]]

  def unspentBoxesByAddress(address: String,
                            paging: Paging = Paging.Default,
                            sort: SortDirection = SortDirection.Desc,
                            mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Try[Seq[IndexedBox]]

  def boxesByErgoTree(ergoTree: String, paging: Paging = Paging.Default): Try[Paged[IndexedBox]]

  def unspentBoxesByErgoTree(ergoTree: String,
                             paging: Paging = Paging.Default,
                             sort: SortDirection = SortDirection.Desc,
                             mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Try[Seq[IndexedBox]]

  def boxesByTemplateHash(templateHash: String, paging: Paging = Paging.Default): Try[Seq[IndexedBox]]

  def unspentBoxesByTemplateHash(templateHash: String,
                                 paging: Paging = Paging.Default,
                                 sort: SortDirection = SortDirection.Desc,
                                 mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Try[Seq[IndexedBox]]

  def tokenById(tokenId: String): Try[Option[TokenInfo]]

  def tokensByIds(tokenIds: Seq[String]): Try[Seq[TokenInfo]]

  def addressBalance(address: String): Try[AddressBalance]
}
