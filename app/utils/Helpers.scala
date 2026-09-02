package utils

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, DictionaryContracts, HoldingScripts, RollupContracts}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.restapi.client.ErgoTransactionOutput
import sigma.SigmaProp
import sigma.ast.ErgoTree
import sigma.data.{CSigmaProp, ProveDlog, SigmaBoolean, SigmaLeaf}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import java.math.{BigDecimal, BigInteger, RoundingMode}
import scala.util.Try

object Helpers {
  def payoutContract(ctx: BlockchainContext): Contract = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET =>
        payoutMainnet
      case NetworkType.TESTNET =>
        payoutTestnet
    }
  }
  def evalContract(ctx: BlockchainContext): Contract = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET =>
        evalMainnet
      case NetworkType.TESTNET =>
        evalTestnet
    }
  }
  def holdingContract(ctx: BlockchainContext): Contract = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET =>
        holdingMainnet
      case NetworkType.TESTNET =>
        holdingTestnet
    }
  }
  /** The rules a holding spend must hand to context variable 64. Never sits on a box. */
  def holdingLogicContract(ctx: BlockchainContext): Contract = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET =>
        holdingLogicMainnet
      case NetworkType.TESTNET =>
        holdingLogicTestnet
    }
  }

  private def compilePayout(networkType: NetworkType): Contract = {
    RollupContracts.mkPayoutContract(networkType)
  }
  private def compileEval(networkType: NetworkType): Contract = {
    RollupContracts.mkEvalContract(networkType, LFSMHelpers.EVAL_PERIOD,
      compilePayout(networkType).hashedPropBytes, LFSMHelpers.getFPToken(networkType))
  }
  private def compileHolding(networkType: NetworkType): HoldingScripts = {
    RollupContracts.mkHoldingScripts(networkType, LFSMHelpers.HOLDING_PERIOD, compileEval(networkType).hashedPropBytes)
  }
  private def compileCollateral(networkType: NetworkType): Contract = {
    val gate = CollateralContract.mkEmissionGateContract(networkType)
    // TODO: Refactor, there is no more testnet collateral contract
    // The current naming is confusing
    CollateralContract.mkMainnetCollatContract(
      networkType, LFSMHelpers.EMCONFIG_NFT, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
  }

  // Pre-compile contracts to avoid v6 issues
  lazy val payoutMainnet: Contract  = compilePayout(NetworkType.MAINNET)
  lazy val payoutTestnet: Contract  = compilePayout(NetworkType.TESTNET)
  lazy val evalMainnet: Contract    = compileEval(NetworkType.MAINNET)
  lazy val evalTestnet: Contract    = compileEval(NetworkType.TESTNET)
  // The guard and its logic together, so nothing can pin one instance into the guard's constant and
  // hand a different one to context variable 64.
  lazy val holdingScriptsMainnet: HoldingScripts = compileHolding(NetworkType.MAINNET)
  lazy val holdingScriptsTestnet: HoldingScripts = compileHolding(NetworkType.TESTNET)
  lazy val holdingMainnet: Contract = holdingScriptsMainnet.guard
  lazy val holdingTestnet: Contract = holdingScriptsTestnet.guard
  lazy val holdingLogicMainnet: Contract = holdingScriptsMainnet.logic
  lazy val holdingLogicTestnet: Contract = holdingScriptsTestnet.logic
  lazy val collateralMainnet: Contract = compileCollateral(NetworkType.MAINNET)
  lazy val collateralTestnet: Contract = compileCollateral(NetworkType.TESTNET)

  // MD contracts
  lazy val mdMainnet: Contract = DictionaryContracts.mkMinerDictionaryContract(NetworkType.MAINNET, LFSMHelpers.MD_TOKEN_MAINNET)
  lazy val mdTestnet: Contract = DictionaryContracts.mkMinerDictionaryContract(NetworkType.TESTNET, LFSMHelpers.MD_TOKEN_TESTNET)
  lazy val minerDataMainnet: Contract = DictionaryContracts.mkMinerDataContract(NetworkType.MAINNET, LFSMHelpers.MD_TOKEN_MAINNET)
  lazy val minerDataTestnet: Contract = DictionaryContracts.mkMinerDataContract(NetworkType.TESTNET, LFSMHelpers.MD_TOKEN_TESTNET)
  lazy val minerDataLogicMainnet: Contract = DictionaryContracts.mkMinerDataLogicContract(NetworkType.MAINNET, LFSMHelpers.MD_TOKEN_MAINNET)
  lazy val minerDataLogicTestnet: Contract = DictionaryContracts.mkMinerDataLogicContract(NetworkType.TESTNET, LFSMHelpers.MD_TOKEN_TESTNET)

  def mdErgoTrees(client: ErgoClient): Seq[String] = {
    client.execute{
      ctx => mdErgoTrees(ctx)
    }
  }

  def dataBoxContract(ctx: BlockchainContext): Contract = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET => minerDataMainnet
      case NetworkType.TESTNET => minerDataTestnet
    }
  }

  /** The rules the data box's guard runs out of context var 64. Never sits on a box. */
  def dataBoxLogic(ctx: BlockchainContext): Contract = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET => minerDataLogicMainnet
      case NetworkType.TESTNET => minerDataLogicTestnet
    }
  }

  def mdErgoTrees(ctx: BlockchainContext): Seq[String] = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET =>
        Seq(
          mdMainnet.ergoTreeHex
          //minerDataMainnet.ergoTreeHex
        )
      case NetworkType.TESTNET =>
        Seq(
          mdTestnet.ergoTreeHex
          //minerDataTestnet.ergoTreeHex
        )
    }

  }

  def rollupErgoTrees(client: ErgoClient): Seq[String] = {
    client.execute{
      ctx =>
        rollupErgoTrees(ctx)
    }
  }

  def rollupErgoTrees(ctx: BlockchainContext): Seq[String] = {
    ctx.getNetworkType match {
      case NetworkType.MAINNET =>
        Seq(
          holdingMainnet.ergoTreeHex,
          evalMainnet.ergoTreeHex,
          payoutMainnet.ergoTreeHex
        )
      case NetworkType.TESTNET =>
        Seq(
          holdingTestnet.ergoTreeHex,
          evalTestnet.ergoTreeHex,
          payoutTestnet.ergoTreeHex
        )
    }

  }

  def pkHexFromBoolean(bool: SigmaBoolean): Option[String] = {
    bool match {
      case leaf: SigmaLeaf =>
        leaf match {
          case ProveDlog(value) =>
            Some(Hex.toHexString(GroupElementSerializer.toBytes(value)))
          case _ => None
        }
      case _ => None
    }
  }

  def pkHexFromSigmaProp(sigmaProp: SigmaProp): Option[String] = {
    sigmaProp match {
      case CSigmaProp(sigmaTree) => Some(pkHexFromBoolean(sigmaTree)).flatten
      case _ => None
    }
  }
}
