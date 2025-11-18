package state

sealed trait LFSMPhase
object LFSMPhase extends {
  case object HOLDING extends LFSMPhase
  case object EVAL    extends LFSMPhase
  case object PAYOUT  extends LFSMPhase
}
