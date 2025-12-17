package nisp


trait NISPStorage {

  def getAll: Seq[(Array[Byte], Array[Byte])]
  def size: Int
  /**
   * Adds SuperShare to create new NISP at given height, or adds the share to an existing NISP
   * @param height Height of the SuperShare
   * @param score Score associated with the share, only used in new insertions
   * @param shareBytes Bytes of SuperShare to add to database
   * @return Whether all operations returned successfully
   */
  def addNISP(height: Int, score: Long, shareBytes: Array[Byte]): Boolean
  /**
   * Adds SuperShare to create new NISP at given height, or adds the share to an existing NISP
   * @param height Height of the SuperShare
   * @param score Score associated with the share, only used in new insertions
   * @param share Share to add to database
   * @return Whether all operations returned successfully
   */
  def addNISP(height: Int, score: Long, share: SuperShare): Boolean

  /**
   * Remove NISPs from the db until the given height (exclusive)
   * @param height Threshold height such that all NISPs under this height are removed
   */
  def removeUntil(height: Int): Boolean
  def removeLastNISP: Boolean
  /**
   * Gets next height with nisp starting from given height
   */
  def getNextHeight(start: Array[Byte]): Option[Array[Byte]]

  def lastHeight: Option[Array[Byte]]

  def currentHeight: Option[Array[Byte]]

  def getNISPBytes(height: Int): Option[Array[Byte]]

  def getNISP(height: Int): Option[NISP]

  /**
   * Gets the best valid NISP before a given height and above a given score. If a NISP with 10 super-shares cannot be
   * made, `None` is returned.
   * @param height Height that all super-shares must be under. Super-shares must be above (height - NISP_PERIOD)
   * @param score Score that all super-shares must be above.
   * @return `Some(NISP)` with 10 super-shares below the given height and above the given score, or `None`
   */
  def getBestValidNISP(height: Int, score: Long): Option[NISP]
  def makeUnique(shares: Seq[SuperShare]): Seq[SuperShare]
  def updateLastHeight(newLastHeight: Array[Byte], storedLastHeight: Option[Array[Byte]]): Boolean

  def updateCurrentHeight(newCurrHeight: Array[Byte], storedCurrHeight: Option[Array[Byte]]): Boolean

  def close(): Unit
}
