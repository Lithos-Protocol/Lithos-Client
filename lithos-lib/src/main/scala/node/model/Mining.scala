package node.model

case class ProofOfUpcomingTransactions(msgPreimage: String, txProofs: Seq[NodeMerkleProof])

case class MiningCandidateMsg(msg: String,
                              b: BigInt,
                              pk: String,
                              h: Option[Int] = None,
                              proof: Option[ProofOfUpcomingTransactions] = None)

case class MiningSolution(pk: String, w: String, n: String, d: String)

case class RewardAddress(rewardAddress: String)

case class RewardPublicKey(rewardPubkey: String)
