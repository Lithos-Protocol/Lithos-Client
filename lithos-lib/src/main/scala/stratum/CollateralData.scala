package stratum

case class CollateralData(txId: String, txJSON: String, pk: String,
                          txBytes: Array[Byte], collateralBoxBytes: Array[Byte]) {
  override def toString: String = {
    s"CollateralData($txId, $pk)"
  }
}
