package state.messages


object StateFrameMessages {

  /** Broadcast to registered subscribers when StateFrame detects a canonical block. */
  case class NewBlock(blockInfo: BlockInfo)

  case object CheckBlock
  case object StartSynchronization
}
