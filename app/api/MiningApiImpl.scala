package api

import configs.StratumConfig
import lfsm.LFSMHelpers
import model.ApiError
import model.NISPRepresentation
import model.StratumInfo
import nisp.NISPDatabase
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration

/**
  * Provides a default implementation for [[MiningApi]].
  */
class MiningApiImpl extends MiningApi {
  /**
    * @inheritdoc
    */
  override def getBestNISPAtHeight(height: Int, score: Long): Option[NISPRepresentation] = {

    val nispDb = new NISPDatabase
    val bestNISP = nispDb.getBestValidNISP(height, score)
    bestNISP.map(n => NISPRepresentation(n.score, height,
      n.shares.map(s => Hex.toHexString(s.headerBytes)).toList, Hex.toHexString(n.serialize)))

  }

  /**
    * @inheritdoc
    */
  override def getStratumInfo(config: Configuration): StratumInfo = {

    val stratumConfig = new StratumConfig(config)
    val stratumTau = LFSMHelpers.parseDiffValueForStratum(stratumConfig.diff).get
    val realTau = LFSMHelpers.convertTauOrScore(LFSMHelpers.convertTauOrScore(stratumTau))
    StratumInfo(stratumConfig.diff, realTau, stratumConfig.reduceShareMessages, -1.0)
  }

}
