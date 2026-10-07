package api

import play.api.libs.json._
import models.ApiError
import models.CandidateSettings
import models.{CommitmentRequest, CommitmentResult, DifficultyCommitment}
import models.NISPRepresentation
import models.StratumInfo
import play.api.Configuration

trait MiningApi {
  /**
    * Get best NISP at height
    * Returns the best NISP produced for a given height and score, with exactly 10 super-shares
    * @param height Height that all super shares in the NISP must be below
    * @param score Score that all super shares in this NISP must be above
    */
  def getBestNISPAtHeight(height: Int, score: Long): Option[NISPRepresentation]

  /**
    * Get Stratum information
    * Information about the Lithos stratum
    */
  def getStratumInfo(config: Configuration): StratumInfo

  /**
    * Get candidate settings
    * The `stratum.candidate` settings that decide what this client's blocks carry besides the
    * genesis transaction
    */
  def getCandidateSettings(config: Configuration): CandidateSettings

  /** This miner's difficulty commitment as read from the chain now. */
  def getCommitment: DifficultyCommitment

  /** Registers this miner with `request.diff`, or changes its commitment to it. */
  def commit(request: CommitmentRequest): CommitmentResult
}
