package api

import play.api.libs.json._
import model.ApiError
import model.NISPRepresentation
import model.StratumInfo
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
}
