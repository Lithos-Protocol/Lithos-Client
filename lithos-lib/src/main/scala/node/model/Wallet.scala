package node.model

case class WalletBox(box: NodeBox,
                     address: String,
                     confirmationsNum: Option[Int],
                     creationTransaction: String,
                     creationOutIndex: Int,
                     inclusionHeight: Option[Int],
                     spendingTransaction: Option[String],
                     spendingHeight: Option[Int],
                     spent: Boolean,
                     onchain: Boolean,
                     scans: Seq[Int]) {

  def boxId: String = box.boxId

  def value: Long = box.value
}

case class WalletTransaction(id: String,
                             inputs: Seq[NodeInput],
                             dataInputs: Seq[NodeDataInput],
                             outputs: Seq[NodeBox],
                             inclusionHeight: Int,
                             numConfirmations: Int,
                             scans: Seq[Int],
                             size: Option[Int] = None)

case class WalletBalances(height: Int, balance: Long, assets: Seq[NodeAsset])

case class WalletStatus(isInitialized: Boolean,
                        isUnlocked: Boolean,
                        changeAddress: String,
                        walletHeight: Int,
                        error: Option[String])

case class DerivedKey(address: String)

case class DerivedNextKey(derivationPath: String, address: String)

case class InitWalletResult(mnemonic: String)

case class BoxesRequest(targetBalance: Long,
                        targetAssets: Map[String, Long] = Map.empty)

case class PaymentRequest(address: String,
                          value: Long,
                          assets: Seq[NodeAsset] = Seq.empty[NodeAsset],
                          registers: NodeRegisters = NodeRegisters.empty)

case class TransactionGenerationRequest(requests: Seq[PaymentRequest],
                                        fee: Option[Long] = None,
                                        inputsRaw: Seq[String] = Seq.empty[String],
                                        dataInputsRaw: Seq[String] = Seq.empty[String])

case class TransactionSigningRequest(unsignedTx: String,
                                     inputsRaw: Seq[String] = Seq.empty[String],
                                     dataInputsRaw: Seq[String] = Seq.empty[String],
                                     hints: Option[String] = None,
                                     secretsDlog: Seq[String] = Seq.empty[String],
                                     secretsDht: Seq[String] = Seq.empty[String])

case class PrivateKeyRequest(address: String)

case class DlogSecret(secret: String)
