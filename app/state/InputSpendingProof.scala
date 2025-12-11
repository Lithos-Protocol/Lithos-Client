package state

import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.restapi.client.SpendingProof

case class InputSpendingProof(proof: String, ext: Map[String, String])

object InputSpendingProof {
  def fromSync(sp: SpendingProof): InputSpendingProof = {
    val extMappings = for (k: String <- sp.getExtension.keySet().toArray) yield k -> sp.getExtension.get(k)
    val ext = Map(extMappings: _*)
    InputSpendingProof(sp.getProofBytes, ext)
  }
}
