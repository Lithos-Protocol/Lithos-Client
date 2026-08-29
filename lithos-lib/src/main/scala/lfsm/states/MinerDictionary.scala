package lfsm.states

import lfsm.LFSMHelpers
import org.bouncycastle.util.encoders.Hex

/** Small, always-resident metadata for the protocol-wide Miner Dictionary. */
final case class MinerDictionaryMetadata(numMiners: Int,
                                         startHeight: Int,
                                         hasMiner: Boolean,
                                         utxoId: String,
                                         synced: Boolean,
                                         syncHeight: Int,
                                         savedHeight: Int,
                                         dictionaryDigest: String) extends UTXOState {
  def materialize(dictionary: AuthenticatedDictionaryView): MinerDictionary = {
    require(Hex.toHexString(dictionary.digest) == dictionaryDigest,
      "Miner Dictionary digest does not match its metadata")
    MinerDictionary(dictionary, numMiners, startHeight, hasMiner, utxoId, synced, syncHeight,
      savedHeight)
  }
}

/** A materialized Miner Dictionary. Address and data-token mirrors are deliberately not retained. */
final case class MinerDictionary(dictionary: AuthenticatedDictionaryView,
                                 numMiners: Int,
                                 startHeight: Int,
                                 hasMiner: Boolean,
                                 utxoId: String,
                                 synced: Boolean,
                                 syncHeight: Int,
                                 savedHeight: Int) extends UTXOState {

  def metadata: MinerDictionaryMetadata =
    MinerDictionaryMetadata(numMiners, startHeight, hasMiner, utxoId, synced, syncHeight,
      savedHeight, Hex.toHexString(dictionary.digest))
}

object MinerDictionary {
  final val ADD_MINER_OP = 0.toByte
  final val REMOVE_MINER_OP = 1.toByte

  def initialState: MinerDictionary = {
    val dictionary = PlasmaDictionary.empty()
    MinerDictionary(dictionary, 0, LFSMHelpers.MD_GENESIS_HEIGHT, hasMiner = false,
      LFSMHelpers.MD_GENESIS_ID, synced = false, 0, 0)
  }
}
