package node.model

case class NodeVersionInfo(name: String, appVersion: String)

case class NodeInfo(name: String,
                    appVersion: String,
                    fullHeight: Option[Int],
                    headersHeight: Option[Int],
                    maxPeerHeight: Option[Int],
                    bestFullHeaderId: Option[String],
                    previousFullHeaderId: Option[String],
                    bestHeaderId: Option[String],
                    stateRoot: Option[String],
                    stateType: String,
                    stateVersion: Option[String],
                    isMining: Boolean,
                    peersCount: Int,
                    unconfirmedCount: Int,
                    difficulty: Option[BigInt],
                    currentTime: Long,
                    launchTime: Long,
                    headersScore: Option[BigInt],
                    fullBlocksScore: Option[BigInt],
                    genesisBlockId: Option[String],
                    parameters: NodeParameters,
                    eip27Supported: Option[Boolean],
                    restApiUrl: Option[String],
                    isExplorer: Option[Boolean])

case class NodeParameters(height: Int,
                          storageFeeFactor: Int,
                          minValuePerByte: Int,
                          maxBlockSize: Int,
                          maxBlockCost: Int,
                          blockVersion: Int,
                          tokenAccessCost: Int,
                          inputCost: Int,
                          dataInputCost: Int,
                          outputCost: Int)

case class BlockchainIndexHeight(indexedHeight: Int, fullHeight: Int)

case class TokenInfo(id: String,
                     boxId: String,
                     emissionAmount: Long,
                     name: String,
                     description: String,
                     decimals: Int)

case class TokenBalance(tokenId: String, amount: Long, decimals: Option[Int], name: Option[String])

case class BalanceInfo(nanoErgs: Long, tokens: Seq[TokenBalance])

case class AddressBalance(confirmed: Option[BalanceInfo], unconfirmed: Option[BalanceInfo])

case class EmissionInfo(minerReward: Long, totalCoinsIssued: Long, totalRemainCoins: Long, reemitted: Option[Long])

case class EmissionScripts(emission: String, reemission: String, pay2Reemission: String)

case class SnapshotInfo(height: Int, digest: String)

case class SnapshotsInfo(availableManifests: Seq[SnapshotInfo])

case class AddressValidity(address: String, isValid: Boolean, error: Option[String])

case class ErgoTreeObject(tree: String)

case class ExecuteScriptResult(value: String, cost: Long)

case class Peer(address: String,
                name: Option[String],
                lastSeen: Option[Long],
                connectionType: Option[String])

case class PeerMode(name: String, appVersion: String, stateType: String, verifying: Boolean, fullBlockHeight: Option[Int])

case class ConnectedPeer(address: String,
                         name: Option[String],
                         connectionType: Option[String],
                         mode: Option[PeerMode])

case class PeersStatus(lastIncomingMessage: Long, currentNetworkTime: Long)

case class BlacklistedPeers(addresses: Seq[String])

case class SyncInfo(fullHeight: Option[Int], maxPeerHeight: Option[Int])

case class TrackInfo(invalidModifierApproxSize: Int, requested: Map[String, String], received: Map[String, String])

case class ScanRequest(scanName: String, trackingRule: String, removeOffchain: Option[Boolean] = None)

case class Scan(scanId: Int, scanName: String, trackingRule: String, walletInteraction: Option[String])

case class ScanId(scanId: Int)

case class ScanIdBoxId(scanId: Int, boxId: String)
