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
 * the addresses, hashes and term positions SigmaFi's own client printed for the same contracts. A bond, order or
 * offer made by either client is then found and spent by the other.
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
   * A difference means every bond, order and offer already on chain sits at a script the new one does not
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

  /** Printed by SigmaFi's `BondsV2Report` at the pinned commit, for mainnet. */
  final val VaultAddress =
    "3zECWKFRn9xN1djLR4kBGr8fzqX7jtzFU2q4xmSeGPQqVXBG6vGs92apVncKpxF8u8E9ksW1uCQcYwbVnhkMyUchVds2xS9M97XamNjKtmWqLCRQ8" +
      "ZdZsbsqeSHhhPngStjbivVHMFTiw8BYbGXuBKhpyt2DWuJCqXyzChNhUYtKtP8VPxsn269aWr7CHCDnGaJkPqRaQWqkxqw1Wo2Hg44JB9KdDR8w" +
      "BvYkEkWaNvJXKtqg"
  final val VaultHash = "ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b"
  final val ErgBondAddress =
    "Pftjcg9bAunFqwxjdVBNnfPjxQF28pX5WpE3guyaDFEVBPCihTZXUJkj6Hf4k1Nh3XQvckjyw9wP9tYGCE72e8ATjvoKJ7x3mv2escwdY2BbDLVj" +
      "AK9Ve1ek5AbuSJcAtBkBsuMh5XrStz73akzMXEeS4NWqKk2sPq4bXUt8pQCZEzM4htd8hxRQgZLvRDQBFVaEf4MK3MrrFxTyjyQZwTdBy7UgK3M" +
      "JHKZgtuBEC88p86bYZa1BXU5Q5BBDAAExiKsXyXeS8JuAdm2rpNNYsnchfUonBgZvRywwLsJXFHe83mBwitt4cv3fah4K7S68YYTDioBemAU"
  final val ErgBondHash = "3055b257bd81c1e6c4d703f41d2c1991c97585fa01a9e71e242bad24c1ab7e0a"
  final val SigUsd = "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"
  final val SigUsdBondAddress =
    "5p5JrCnsQcSxcGUkdQmG3UingR1D5fGHM5tXwtVMpjYgLuUwkgduoMXBVZods3G1NpRBo7yyMaiTsb5jS5geH1z5W8D6po9gdeAFNoQnpfxi6vLT" +
      "zd46ibmFLuig4kRyQVJoPwtEDuiM3ySdCArs6CDhKUUfbkootyzwTcWiviJ6xYq7pFyNyphvECTyUC9yjbwRixjjpnk7TZJnrUUZxZ3aaLhRdzjP" +
      "VQJhJ194fcPkcj7NTUwPrEhvT7Ryt26C7jYKhjSLiBDSUTGjRSmehQbcz3QkKpybVu4wnE8LEQbEPnCuagHJbLMStzAQuuisCqGUnKyJBVDd7VVM" +
      "mSbDjveCcNjMotddhGVg3UJuDry1Dj1vzZthGXRytp3vy4wQsvna4iXHxiuzYGkghZTDvMH1ULUwooffJhspErdzwrZRJD675FmoB7A5FE"
  final val SigUsdBondHash = "9325bcc25e91ad2b9edc8b390dcf9e00235f5500fd1113b72c8ecb3d8c9c88cd"
  final val MaturityLiquidationHash = "83f2d5427f032846504b3e9d71319cf7262a0a80f01404ca94105ea9eb569185"
  final val TokenBondTemplateHash = "1be0ff4b324f56dd17ee6cb13a6283aaf9349d0c707720266a49fd86dd7fa099"

  import SigmaFiContracts._

  final val Templates: Map[SigmaFiTemplateKind, (String, Map[String, Int])] = Map(
    SigmaFiTemplateKind.Offer -> ("11724775e64ab60852d8f6833f40285d81dc3ed58f22ab4c6f33593d08fb7a58", Map(
      LENDER_HASH -> 2, ASSET_NAME -> 12, ASSET_DESCRIPTION -> 13, BOND_HASH -> 15, COLLATERAL_TOKENS -> 16,
      COLLATERAL_ERG -> 17, REPAYMENT -> 20, BATCHING_FEE -> 22, MATURITY -> 23, LIQ_SCRIPT_HASH -> 24)),
    SigmaFiTemplateKind.Order -> ("a5a619be5bff9c9eb6d1f6d15ac4b95475512afed917f3a4e95b5afe334fe30c", Map(
      BORROWER_HASH -> 2, BOND_HASH -> 12, REPAYMENT -> 15, BATCHING_FEE -> 17, MATURITY -> 18,
      LIQ_SCRIPT_HASH -> 19, PRINCIPAL_ERG -> 23, PRINCIPAL_TOKENS -> 24)),
    SigmaFiTemplateKind.OrderOnClose -> ("51959c0b41c3796aa09e6387e058cbec22bb1f61e883caed82003a07a6d2ca5a", Map(
      BORROWER_HASH -> 2, DURATION -> 7, BOND_HASH -> 13, REPAYMENT -> 16, BATCHING_FEE -> 18,
      LIQ_SCRIPT_HASH -> 19, PRINCIPAL_ERG -> 24, PRINCIPAL_TOKENS -> 25)))
}
