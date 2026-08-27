package state.messages


case class BlockInfo(id: String,
                     height: Int,
                     txs: Seq[BlockTx],
                     parentId: String = "",
                     resolvedInputs: Map[String, TxOutput] = Map.empty) {
  def inputBox(id: String): Option[TxOutput] = resolvedInputs.get(id)

  /**
   * Whether every resolved input box came back without a script.
   */
  def inputsLookStripped: Boolean =
    resolvedInputs.nonEmpty && resolvedInputs.values.forall(_.ergoTree.isEmpty)
}
