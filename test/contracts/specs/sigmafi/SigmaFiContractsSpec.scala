package contracts.specs.sigmafi

import contracts.specs.harness.ContractSpecBase
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigmafi.contracts.{SigmaFiContracts, SigmaFiTemplateKind}
import work.lithos.mutations.Contract

/**
 * The SigmaFi V2 contracts this client builds and reads, pinned rather than compiled.
 *
 * Two checks hold them in place. The `.ergo` copies compile to the pinned trees here, and the pinned trees give
 * the addresses, hashes and term positions SigmaFi's own client printed for the same contracts. A bond, order made by either client is then found and spent by the other.
 */
class SigmaFiContractsSpec extends AnyPropSpec with ContractSpecBase {

  import SigmaFiContractsSpec._

  /** Every source compiled once, since compiling a script twice in one JVM can throw. */
  private lazy val compiled: Map[String, String] = withCtx { _ =>
    val vault = SigmaFiContracts.compileSource("BondAssetVault", Map.empty)
    val vaultHash = Colls.fromArray(Blake2b256.hash(vault.bytes))
    val ergBond = SigmaFiContracts.compileSource("ZC_ERG_Bond", Map(SigmaFiContracts.VAULT_HASH -> vaultHash))
    val liquidation = SigmaFiContracts.compileSource("MaturityLiquidation", Map.empty)
    Map("BondAssetVault" -> Hex.toHexString(vault.bytes), "ZC_ERG_Bond" -> Hex.toHexString(ergBond.bytes),
      "MaturityLiquidation" -> Hex.toHexString(Contract.compileValue(liquidation))) ++
      SigmaFiTemplateKind.all.map(kind => kind.scriptName -> Hex.toHexString(
        SigmaFiContracts.compileSource(kind.scriptName, SigmaFiContracts.sentinelTerms(kind)).bytes))
  }

  private def drift(name: String, pinned: String): Option[String] = {
    val hex = compiled(name)
    if (hex == pinned) None
    else Some(s"$name compiles to:\n" + hex.grouped(104).map(l => "\"" + l + "\"").mkString(" +\n"))
  }

  /**
   * A difference means every bond, order already on chain sits at a script the new one does not
   * match, so re-pinning is a decision about those boxes, not a fix.
   */
  property("pins: every tree is exactly what its .ergo source compiles to") {
    val drifted = Seq(
      drift("BondAssetVault", SigmaFiContracts.VaultTreeHex),
      drift("ZC_ERG_Bond", SigmaFiContracts.ErgBondTreeHex),
      drift("MaturityLiquidation", SigmaFiContracts.MaturityLiquidation.bytesHex)) ++
      SigmaFiTemplateKind.all.map(k => drift(k.scriptName, SigmaFiContracts.pinnedTreeHex(k)))
    withClue(drifted.flatten.mkString("\n\n")) { drifted.flatten shouldBe empty }
  }

  property("pins: addresses and hashes are the ones SigmaFi's own client prints") {
    withCtx { _ =>
      SigmaFiContracts.vault.address(NetworkType.MAINNET).toString shouldBe VaultAddress
      SigmaFiContracts.vault.hashedPropBytesHex shouldBe VaultHash
      SigmaFiContracts.ergBond.address(NetworkType.MAINNET).toString shouldBe ErgBondAddress
      SigmaFiContracts.ergBond.hashedPropBytesHex shouldBe ErgBondHash
      val sigUsdBond = SigmaFiContracts.tokenBond(ErgoId.create(SigUsd))
      sigUsdBond.address(NetworkType.MAINNET).toString shouldBe SigUsdBondAddress
      sigUsdBond.hashedPropBytesHex shouldBe SigUsdBondHash
      SigmaFiContracts.MaturityLiquidation.hashHex shouldBe MaturityLiquidationHash
      SigmaFiContracts.template(SigmaFiTemplateKind.TokenBond).templateHashHex shouldBe TokenBondTemplateHash
    }
  }

  property("pins: template hashes and term positions are the ones SigmaFi's own client prints") {
    withCtx { _ =>
      Templates.foreach { case (kind, (hash, positions)) =>
        val t = SigmaFiContracts.template(kind)
        t.templateHashHex shouldBe hash
        t.positions shouldBe positions
      }
    }
  }

  property("pins: every tree is ErgoTree v3, and every term is a distinct constant") {
    withCtx { _ =>
      (SigmaFiContracts.vault.ergoTree +: SigmaFiContracts.ergBond.ergoTree +:
        SigmaFiTemplateKind.all.map(SigmaFiContracts.template(_).tree)).foreach(_.version shouldBe 3.toByte)
      SigmaFiTemplateKind.all.foreach { kind =>
        SigmaFiContracts.template(kind).positions.values.toSet should have size kind.termNames.size
      }
    }
  }
}

object SigmaFiContractsSpec {

  /** Exported by SigmaFi's `LithosReference` at 6384141, for mainnet. */
  final val VaultAddress =
    "3zECWKFRn9xN1djLR4kBGr8fzqX7jtzFU2q4xmSeGPQqVXBG6vGs92apVncKpxF8u8E9ksW1uCQcYwbVnhkMyUchVds2xS9M97XamNjKtmWqLCRQ8ZdZsbsqeSHhhPngStjbivVHMFTiw8BYbGXuBKhpyt2DWuJCqXyzChNhUYtKtP8VPxsn269aWr7CHCDnGaJkPqRaQWqkxqw1Wo2Hg44JB9KdDR8wBvYkEkWaNvJXKtqg"
  final val VaultHash = "ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b"
  final val ErgBondAddress =
    "AbMVCU6L2xciF5BfFo19UscGtuzpyGTNtPiEWAh84N8h2aP6EGHgfdioSUz9Y4DvPTE59LjSxC5KgKcRskLPoGnmkEGJwroYq5FEzLJoMyRNZdi2D5ZxgupVGuVWzxjwc1cj4hfD4Rh3xSW5Mc2AbdF9XH61URkYYGDHnumNKQhTkzwNNX1b4AWjcn84TJeqD3AUtLiGQHfLVMWcnuNYSynXZPRaaSpWLMn8wE7jkDpKpx6fC4T1wp7cw6SpCbDqxLNcMia8aGCqRMCQKrLRt32toQbV7ZZAVpc21A7ct3Y1Y1CBMmdXqpk6exQATpdt2wMqiedKoshvAke5sMN23mr1BuFAasbRtKSgiiFuYwtXFr5qwS831KytNRH5U4Ts1mWpfzaVoq869NUiveR8cPtdbsVNcp5DvmUUjNofKKyqBjZpp4poZooQCV25fvhrWuGVqG8SftDpg1kAgqTP3Lxn8MnSYJ8rNN9qY64kuk3qCHLgTPrTXmzucPcn5v5T9qpzWPjLARNQByNvxXvP5bCdSUNYMJgz6qCveADLppv2jzBLqSoSxHdF7T6ZMYX2kT43QbQ9oJZCNiUrgztyuGHWpFZr4W72Mp2dPGHfra"
  final val ErgBondHash = "1bceba97922f67d50e2718f39696c3f43f21279d971c83381a14ea0334df93b9"
  final val SigUsd = "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"
  final val SigUsdBondAddress =
    "335hjjFkLYzyY99TeUyNeUzWBdkxb4ZSLXRwpG6uXJDYefQQrASucVdcJ2B7Aiv5kagCBUoyTzozVmvACff9gEg79YyoPBtKBUt7nYFpCmnKbc2RzFvvsbANvq25kyuwZ3qCYM2ZVnXaVnXduNH3C7GE9wdPn2mwn5SS3fUj8ECAYe3xWEcy5pt94o7aWUPBbdNKxxuWk7pUt2EBNYFWhFaJfxvwqK4Vae3VJHVqoqZNnuGWRm4o615yRnRP67v5AgPbEEyE5CnWAHGd7LQik9CATe8FRq4hDuB9mx1ogMyx9fT8ATURbNY6uYp1QKqo9euSP85RR47SFpjEWbCCGQa9zmY34BYaMjNAaWHc88fjLwZWa3a3qj8aeaq59Zr6CkrgffKF7qXM9NUKrAsa1xa1e86AnV3i6Zzqv2WgHTg1nAqHreRokAJ3xtMRnW5Y4KNFj36PLsXp9yz5bj977aMHcTnSvZesZ5KTNHvPcEkeZyBYUSJhyrxyRhzACJLZZnFXjsxwhcb5LNvNv452P2nap8HEtqSsQmnS23YNXT2RAnUxiFx2cowjdU15dRGTk5kAwx5GCtnWc9tFUscbdHXzufKvzogGGmDm7CxcGyM2KNDYEAeNmwHSuCXC3QxuZezeZuD8zTbpGcYpAL3cq2TyTGvJTaoXzKtVc1fLqVsDbpU88VX2iV18aTjsHtmgS2kVAYLmBk77HWTiSb6eJFWBh"
  final val SigUsdBondHash = "9425199824c3985ccbda2614ebcf4e4f17336e5e8abdb58be0830982f9a507cd"
  final val MaturityLiquidationHash = "ea301f93d1a806e8fedd21ef0ffae39bdbfc15671c41247c4476ccb942a1485b"
  final val TokenBondTemplateHash = "01dc8c5e62641965839a9b984bb7f73a7a9e24928b8483a6642459ba13dc1181"

  import SigmaFiContracts._

  final val Templates: Map[SigmaFiTemplateKind, (String, Map[String, Int])] = Map(
    SigmaFiTemplateKind.Order -> ("49dd9de79ddf81c435b42703a884abb7f212b20ad052f2a50d9d779f4171bf4d", Map(
      BORROWER_HASH -> 2, BOND_HASH -> 14, REPAYMENT -> 23, PRINCIPAL_ERG -> 26, PRINCIPAL_TOKENS -> 27, MATURITY -> 29, LIQ_SCRIPT_HASH -> 30)),
    SigmaFiTemplateKind.OrderOnClose -> ("362744a608dc1c7f872a616f1a6ad88c92240500ba5ec9a9498d74ef031bcc52", Map(
      BORROWER_HASH -> 2, DURATION -> 7, BOND_HASH -> 16, REPAYMENT -> 25, PRINCIPAL_ERG -> 28, PRINCIPAL_TOKENS -> 29, LIQ_SCRIPT_HASH -> 30)))
}
