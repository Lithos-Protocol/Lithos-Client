package contracts.specs.lithosdex

import lithosdex.contracts.{LDContractKind, LithosDexContracts}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.propspec.AnyPropSpec

/**
 * The templates every LithosDex deployment's contracts are built from, the canonical one included.
 *
 * Nothing compiles these contracts at runtime, so the pinned trees are the contracts. Whether the canonical
 * and a second deployment's built trees equal the live chain's is checked in `LDDeploymentsSpec`.
 */
class LDContractTemplateSpec extends AnyPropSpec with LithosDexSpecBase {

  /**
   * The only place these scripts are compiled. A difference means every deployment already on chain sits
   * at a template the new one does not match, so re-pinning is a decision about those pools, not a fix.
   */
  property("templates: the pinned trees are exactly what the .ergo sources compile to") {
    withCtx { _ =>
      val drifted = LDContractKind.all.flatMap { kind =>
        val hex = Hex.toHexString(LithosDexContracts.compileSource(kind).bytes)
        if (hex == LithosDexContracts.pinnedTreeHex(kind)) None
        else Some(s"${kind.scriptName} compiles to:\n" + hex.grouped(96).map(l => "\"" + l + "\"").mkString(" +\n"))
      }
      withClue(drifted.mkString("\n\n")) { drifted shouldBe empty }
    }
  }

  property("templates: every contract is ErgoTree v3 with each term as exactly one constant") {
    withCtx { _ =>
      LDContractKind.all.foreach { kind =>
        val t = LithosDexContracts.template(kind)
        t.treeVersion shouldBe Some(3.toByte)
        kind.termNames.map(LithosDexContracts.indexOf(kind, _)).distinct should have size kind.termNames.size
      }
    }
  }
}
