package state.messages


/**
 * The context extension of one spent input.
 *
 * The serialized proof bytes are deliberately not retained.
 */
case class InputSpendingProof(ext: Map[String, String])
