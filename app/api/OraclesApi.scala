package api

import api.models.{OracleFeeds, OracleState}

trait OraclesApi {
  def listFeeds(): OracleFeeds
  def getFeed(feedId: String, offset: Option[Int], limit: Option[Int], atBlockId: Option[String]): OracleState
}
