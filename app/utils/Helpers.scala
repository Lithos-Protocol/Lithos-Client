package utils

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, DictionaryContracts, RollupContracts}
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

  def collateralContract(ctx: BlockchainContext): Contract = {
    CollateralContract.mkTestnetCollatContract(ctx, holdingContract(ctx).hashedPropBytes)
  }

  private def compilePayout(networkType: NetworkType): Contract = {
    RollupContracts.mkPayoutContract(networkType)
  }
  private def compileEval(networkType: NetworkType): Contract = {
    RollupContracts.mkEvalContract(networkType, LFSMHelpers.EVAL_PERIOD,
      compilePayout(networkType).hashedPropBytes, LFSMHelpers.getFPToken(networkType))
  }
  private def compileHolding(networkType: NetworkType): Contract = {
    RollupContracts.mkHoldingContract(networkType, LFSMHelpers.HOLDING_PERIOD, compileEval(networkType).hashedPropBytes)
  }

  // Pre-compile contracts to avoid v6 issues
  lazy val payoutMainnet: Contract  = compilePayout(NetworkType.MAINNET)
  lazy val payoutTestnet: Contract  = compilePayout(NetworkType.TESTNET)
  lazy val evalMainnet: Contract    = compileEval(NetworkType.MAINNET)
  lazy val evalTestnet: Contract    = compileEval(NetworkType.TESTNET)
  lazy val holdingMainnet: Contract = compileHolding(NetworkType.MAINNET)
  lazy val holdingTestnet: Contract = compileHolding(NetworkType.TESTNET)

  // MD contracts
  lazy val mdMainnet: Contract = DictionaryContracts.mkMinerDictionaryContract(NetworkType.MAINNET, LFSMHelpers.MD_TOKEN_MAINNET)
  lazy val mdTestnet: Contract = DictionaryContracts.mkMinerDictionaryContract(NetworkType.TESTNET, LFSMHelpers.MD_TOKEN_TESTNET)
  lazy val minerDataMainnet: Contract = DictionaryContracts.mkMinerDataContract(NetworkType.MAINNET, LFSMHelpers.MD_TOKEN_MAINNET)
  lazy val minerDataTestnet: Contract = DictionaryContracts.mkMinerDataContract(NetworkType.TESTNET, LFSMHelpers.MD_TOKEN_TESTNET)

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
