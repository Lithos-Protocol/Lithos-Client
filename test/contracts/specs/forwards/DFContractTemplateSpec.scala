package contracts.specs.forwards

import contracts.specs.harness.ContractSpecBase
import forwards.contracts.{DFContractKind, DifficultyForwardContracts}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.propspec.AnyPropSpec

/**
 * The templates every difficulty forward deployment's contracts are built from.
 *
 * Nothing compiles these contracts at runtime, so the pinned trees are the contracts.
 */
class DFContractTemplateSpec extends AnyPropSpec with ContractSpecBase {

  /**
   * The only place these scripts are compiled. A difference means every deployment already on chain sits
   * at a template the new one does not match, so re-pinning is a decision about those boxes, not a fix.
   */
  property("templates: the pinned trees are exactly what the .ergo sources compile to") {
    withCtx { _ =>
      val drifted = DFContractKind.all.flatMap { kind =>
        val hex = Hex.toHexString(DifficultyForwardContracts.compileSource(kind).bytes)
        if (hex == DifficultyForwardContracts.pinnedTreeHex(kind)) None
        else Some(s"${kind.scriptName} compiles to:\n" + hex.grouped(96).map(l => "\"" + l + "\"").mkString(" +\n"))
      }
      withClue(drifted.mkString("\n\n")) { drifted shouldBe empty }
    }
  }

  property("templates: every contract is ErgoTree v3 with each term as exactly one constant") {
    withCtx { _ =>
      DFContractKind.all.foreach { kind =>
        val t = DifficultyForwardContracts.template(kind)
        t.treeVersion shouldBe Some(3.toByte)
        kind.termNames.map(DifficultyForwardContracts.indexOf(kind, _)).distinct should have size kind.termNames.size
      }
    }
  }
}
