package contracts.specs

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, DictionaryContracts, FraudProofContracts, RollupContracts}
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import plasmadex.contracts.PlasmaDexContracts
import work.lithos.mutations.Contract

import java.math.BigInteger
import scala.util.Try

class HarnessProbeSpec extends AnyPropSpec with ContractSpecBase {

  property("mocked client yields a usable BlockchainContext at ErgoTree v3") {
    withCtx { ctx =>
      ctx.getHeight should be > 0
      println(s"[probe] height=${ctx.getHeight} network=${ctx.getNetworkType}")
    }
  }

  property("a dummy prover can sign a spend offline") {
    withCtx { ctx =>
      val prover = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(12345L)).build()
      val input = ctx.newTxBuilder().outBoxBuilder()
        .value(Parameters.OneErg)
        .contract(ctx.newContract(lfsm.ScriptGenerator.mkSigTrue(ctx).ergoTree))
        .build()
        .convertToInputWith("f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809", 0)
      val out = ctx.newTxBuilder().outBoxBuilder()
        .value(Parameters.OneErg - Parameters.MinFee)
        .contract(ctx.newContract(lfsm.ScriptGenerator.mkSigTrue(ctx).ergoTree))
        .build()
      val unsigned = ctx.newTxBuilder()
        .addInputs(input).addOutputs(out).fee(Parameters.MinFee)
        .sendChangeTo(prover.getAddress).build()
      prover.sign(unsigned).getOutputsToSpend.size() should be > 0
    }
  }

  private val dummyHash: Array[Byte] = Array.fill(32)(0.toByte)

  property("inventory: which production contract builders compile today") {
    withCtx { ctx =>
      val payout = Try(RollupContracts.mkPayoutContract(ctx))
      val eval = payout.map(p =>
        RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, p.hashedPropBytes, LFSMHelpers.getFPToken(ctx)))
      val holding = eval.map(e => RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, e.hashedPropBytes))

      val builders: Seq[(String, Try[Contract])] = Seq(
        "rollups/Payout"                  -> payout,
        "rollups/Evaluation"              -> eval,
        "rollups/Holding"                 -> holding,
        "rollups/FP_Control_Testnet"      -> Try(RollupContracts.mkFPControlTestnetContract(
                                               ctx, ContractProbe.dummyDlog(ctx))),
        "fraudproofs/FP_InvalidDiff"      -> Try(FraudProofContracts.mkInvalidDiffContract(ctx)),
        "fraudproofs/FP_InvalidFormat"    -> Try(FraudProofContracts.mkInvalidFormatContract(ctx)),
        "fraudproofs/FP_NonUniqueHeaders" -> Try(FraudProofContracts.mkNonUniqueHeadersContract(ctx)),
        "fraudproofs/FP_NotInWindow"      -> Try(FraudProofContracts.mkNotInWindowContract(ctx)),
        "fraudproofs/FP_IncorrectN"       -> Try(FraudProofContracts.mkIncorrectNContract(ctx)),
        "fraudproofs/FP_MalformedGE"      -> Try(FraudProofContracts.mkMalformedGEContract(ctx)),
        "fraudproofs/FP_TransactionNotIncluded" -> Try(FraudProofContracts.mkTransactionNotIncludedContract(ctx)),
        "dictionaries/MinerDictionary"    -> Try(DictionaryContracts.mkMinerDictionaryContract(
                                               ctx.getNetworkType, LFSMHelpers.MD_TOKEN_TESTNET)),
        "dictionaries/MinerData"          -> Try(DictionaryContracts.mkMinerDataContract(
                                               ctx.getNetworkType, LFSMHelpers.MD_TOKEN_TESTNET)),
        "plasmadex/LiquidityPool"         -> Try(PlasmaDexContracts.mkLPContract(ctx)),
        "plasmadex/FeeRewards"            -> Try(PlasmaDexContracts.mkFeeRewardContract(ctx)),
        "collateral/Collateral_Mainnet"   -> Try(CollateralContract.mkMainnetCollatContract(
                                               ctx, LFSMHelpers.EMISSION_NFT, dummyHash, LFSMHelpers.LIT_ID)),
        "collateral/Collateral_Testnet"   -> Try(CollateralContract.mkTestnetCollatContract(ctx, dummyHash)),
        "collateral/LIT_Emissions"        -> Try(CollateralContract.mkEmissionsContract(
                                               ctx, dummyHash, dummyHash, LFSMHelpers.LIT_ID)),
        "collateral/Emission_Gate"        -> Try(CollateralContract.mkEmissionGateContract(ctx)),
        "collateral/Emission_Guard"       -> Try(CollateralContract.mkEmissionsGuardContract(
                                               ctx, LFSMHelpers.EMISSION_NFT, LFSMHelpers.EMISSION_NFT,
                                               dummyHash, dummyHash, LFSMHelpers.LIT_ID)),
        "collateral/Emission_Config"      -> Try(CollateralContract.mkEmConfigContract(
                                               ctx, ContractProbe.dummyOwner(ctx))),
        "collateral/Collateral_Enforcer"  -> Try(CollateralContract.mkCollateralEnforcerContract(
                                               ctx, LFSMHelpers.LIT_ID))
      )

      println("\n[inventory] production contract builders")
      println("-" * 78)
      builders.foreach { case (name, attempt) =>
        val status = attempt match {
          case scala.util.Success(c) if c.ergoTreeHex == Contract.SIGMA_TRUE.ergoTreeHex =>
            "STUB      sigmaProp(true) - builder returns SIGMA_TRUE"
          case scala.util.Success(c) =>
            f"OK        v${c.ergoTree.version}%s, ${c.propBytes.length}%4d bytes"
          case scala.util.Failure(e) =>
            val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            "BROKEN    " + msg.replaceAll("\\s+", " ").take(140)
        }
        println(f"  $name%-40s $status")
      }
      println("-" * 78)
      succeed
    }
  }
}

object ContractProbe {
  def dummyOwner(ctx: BlockchainContext): Contract =
    Contract.fromAddress(ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(99L)).build().getAddress)

  def dummyDlog(ctx: BlockchainContext): sigma.data.ProveDlog =
    ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(99L)).build().getAddress.getPublicKey
}
