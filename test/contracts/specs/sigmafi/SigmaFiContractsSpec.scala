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

  /** Printed by SigmaFi's `BondsV2Report` at the pinned commit, for mainnet. */
  final val VaultAddress =
    "3zECWKFRn9xN1djLR4kBGr8fzqX7jtzFU2q4xmSeGPQqVXBG6vGs92apVncKpxF8u8E9ksW1uCQcYwbVnhkMyUchVds2xS9M97XamNjK" +
      "tmWqLCRQ8ZdZsbsqeSHhhPngStjbivVHMFTiw8BYbGXuBKhpyt2DWuJCqXyzChNhUYtKtP8VPxsn269aWr7CHCDnGaJkPqRaQWqkxqw1" +
      "Wo2Hg44JB9KdDR8wBvYkEkWaNvJXKtqg"
  final val VaultHash = "ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b"
  final val ErgBondAddress =
    "5PxqVLKJBKWqownSfVSv9cRj7Zcr4KWhNWZQC4EkA8VDfkJeuEbgz1KX7ReDquJ155rS3NNuDFebR9NU68VfACdaBFMJxfVBVaHNhYqW" +
      "NcfS1mPipnW23uHhADe2Wm2ULqs854Pd2XZibiGDaEyQmgfE6n3xHdMUvfDpMyDB8VyUdHTpUH6iduT2kmrkeWo9JFHUx5cyqa5TJeGA" +
      "eYJnzuACWcrxT3pKUAbDMduLbJgAGhfiD2M3efKjXwUQhMJAaTwnwXLE63S7i8YkuHVL4nJDrjXWF3niKoMchS2HHwSqdbBUXviFgfSz" +
      "Tnmx8kWaTV4AC7MgR6p2snnrRrRBUi61uxpMj8VcwhLw9SzHTsUpbr1cJ9FA7dTpc5Nb8VFKu91gEKQgio18PGfEv1UBB2f4HFWjDb2w" +
      "sYwTNEtEKc3"
  final val ErgBondHash = "3839341bb246c79a8a4b98feffbf7805cbc41a8ca877920f2abade3507e92235"
  final val SigUsd = "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"
  final val SigUsdBondAddress =
    "w7b7Ht8ELRboQhcFmcV5ZwjpGJS4JL7dhjywVZwYd76CanXf33eBosWC71etNib7zoenYpUM9KMXuqAjmVjuxFDaECkobiZiGwBRKMXY" +
      "VNo24m6ij8kfKGYpiZgTjLFBpseQdYbF9h3AQHhpAQtx9Pits1YXCj27QoxwJVSAw3oXnYXkTxyFudynWy42We3BXqfjcKTie3N9MHVQ" +
      "2gWaRgbcDte3zeHEPcSLZe1yu6736HZHVz72LjGvRsNeiboKVBJGD7YMbUTmu77p1CorhcSbqdTCtVxGh8gBW8Xzicat6ecz8JhoSinw" +
      "JdYMFMpsKrSr4W9rx6C5jrZMAZjynnE6bDLJuya2SY7iSaAJukir9z9e8tKtTcTjJLkATFUwDWVugbC7yzZYRDV3K6qZgmywmcByUXdP" +
      "Mm4BX2TP4jWNF9Yk3QCstErr8MhCgYgnSxLx24aBDFU38wWWKxwkzB27iwpZh4z8pPdi19F2e6GF5zYPPvjrPkLaQGhYGdnoBLm111r3" +
      "jubUEztcVU8am8YfM"
  final val SigUsdBondHash = "9b2b3ca15eb9042a3ca8f5956d139a8feb6639dae56f74195c405f66962ad512"
  final val MaturityLiquidationHash = "23b131585209a670a79817292233ed8c30618c3c8046ba174b655afee0e828a3"
  final val TokenBondTemplateHash = "6e9bdb6db48c3986a0f01b11876264a1c7aad6e547090e6238c91b793121290a"

  import SigmaFiContracts._

  final val Templates: Map[SigmaFiTemplateKind, (String, Map[String, Int])] = Map(
    SigmaFiTemplateKind.Order -> ("5e82ac73dba7b8dbe463d8f05fd8058604542c00fecc0eade894785a24934bcf", Map(
      BORROWER_HASH -> 2, BOND_HASH -> 14, REPAYMENT -> 22, BATCHING_FEE -> 24, MATURITY -> 25, LIQ_SCRIPT_HASH -> 26, PRINCIPAL_ERG -> 30, PRINCIPAL_TOKENS -> 31)),
    SigmaFiTemplateKind.OrderOnClose -> ("f5e91924fcc12cce98913e60a43d9c03b8d6495fd88409143aed94373e35499f", Map(
      BORROWER_HASH -> 2, DURATION -> 7, BOND_HASH -> 15, REPAYMENT -> 23, BATCHING_FEE -> 25, LIQ_SCRIPT_HASH -> 26, PRINCIPAL_ERG -> 31, PRINCIPAL_TOKENS -> 32)))
}
