package stratum

case class CollateralData(txId: String, txJSON: String, pk: String,
                          txBytes: Array[Byte], collateralBoxBytes: Array[Byte],
                          collateralId: String, lenderAddress: String) {

  override def equals(obj: Any): Boolean = {
    obj match {
      case CollateralData(dataTxId, _, dataPk, _, _, dataCollId, dataLenderAddress) =>
        dataTxId == txId && pk == dataPk && dataCollId == collateralId && dataLenderAddress == lenderAddress
      case _ =>
        false
    }
  }

  override def toString: String = {
    s"CollateralData($txId, $pk)"
  }
}
