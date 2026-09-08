package stratum

/**
 * The signed genesis transaction and what identifies it.
 *
 * @param holdingOutput the holding box this transaction created, which a same-height top-up spends.
 *                      Present only where the genesis was built here rather than read back.
 */
case class CollateralData(txId: String, txJSON: String, pk: String,
                          txBytes: Array[Byte], collateralBoxBytes: Array[Byte],
                          collateralId: String, lenderAddress: String,
                          signedSizeBytes: Int = 0, cost: Long = 0L,
                          holdingOutput: Option[work.lithos.mutations.InputUTXO] = None) {

  /** Identity, not contents: two reads of the same genesis differ in nothing that matters here. */
  override def equals(obj: Any): Boolean = {
    obj match {
      case other: CollateralData =>
        other.txId == txId && other.pk == pk && other.collateralId == collateralId &&
          other.lenderAddress == lenderAddress
      case _ =>
        false
    }
  }

  override def toString: String = {
    s"CollateralData($txId, $pk)"
  }
}
