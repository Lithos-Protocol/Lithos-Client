package contracts.specs.lithosdex

import lithosdex.contracts.{LDContractKind, LDContracts, LDT2TContracts, LithosDexContracts}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import org.scalatest.propspec.AnyPropSpec
import sigma.ast.ByteArrayConstant

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

  property("templates: a token/token deployment runs the same provision logic and guard as an ERG/token one") {
    withCtx { _ =>
      val (pool, vault, prov) = (LithosDexSpecBase.poolNFT, LithosDexSpecBase.vaultNFT, LithosDexSpecBase.provToken)
      val n2t = LDContracts(pool, vault, prov, NetworkType.MAINNET)
      val t2t = LDT2TContracts(pool, vault, prov)
      t2t.provisionLogic.propBytes shouldEqual n2t.provisionLogic.propBytes
      t2t.provisionGuard.propBytes shouldEqual n2t.provisionGuard.propBytes
      t2t.liquidityPool.propBytes should not equal n2t.liquidityPool.propBytes
      t2t.feeVault.propBytes should not equal n2t.feeVault.propBytes
    }
  }

  property("templates: a token/token pool is wired to its own guard and vault") {
    withCtx { _ =>
      val d = LDT2TSpecBase.deployment
      val pool = d.liquidityPool.ergoTree.constants
      val vault = d.feeVault.ergoTree.constants
      def at(kind: LDContractKind, name: String) = LithosDexContracts.indexOf(kind, name)
      pool(at(LDContractKind.T2TLiquidityPool, LithosDexContracts.PROVISION_GUARD_HASH)) shouldEqual
        ByteArrayConstant(d.provisionGuard.hashedPropBytes)
      pool(at(LDContractKind.T2TLiquidityPool, LithosDexContracts.VAULT_NFT)) shouldEqual
        ByteArrayConstant(LDT2TSpecBase.vaultNFT.getBytes)
      vault(at(LDContractKind.T2TFeeVault, LithosDexContracts.POOL_NFT)) shouldEqual
        ByteArrayConstant(LDT2TSpecBase.poolNFT.getBytes)
      vault(at(LDContractKind.T2TFeeVault, LithosDexContracts.PROV_TOKEN)) shouldEqual
        ByteArrayConstant(LDT2TSpecBase.provToken.getBytes)
    }
  }
}
