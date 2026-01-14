package lfsm.states

/**
 * Representation of a UTXO or UTXO system which transitions states over time
 */
trait UTXOState {
  val startHeight: Int
  val utxoId: String

}
