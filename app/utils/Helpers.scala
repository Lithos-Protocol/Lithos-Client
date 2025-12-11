package utils

import lfsm.LFSMHelpers
import lfsm.collateral.CollateralContract
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoId, ErgoProver, ErgoValue}
import org.ergoplatform.restapi.client.ErgoTransactionOutput
import sigma.SigmaProp
import sigma.data.{CSigmaProp, ProveDlog, SigmaBoolean, SigmaLeaf}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import java.math.{BigDecimal, BigInteger, RoundingMode}
import scala.util.Try

object Helpers {
  def payoutContract(ctx: BlockchainContext): Contract = {
    RollupContracts.mkPayoutContract(ctx)
  }
  def evalContract(ctx: BlockchainContext): Contract = {
    RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD,
      payoutContract(ctx).hashedPropBytes, LFSMHelpers.getFPToken(ctx))
  }
  def holdingContract(ctx: BlockchainContext): Contract = {
    RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, evalContract(ctx).hashedPropBytes)
  }

  def collateralContract(ctx: BlockchainContext): Contract = {
    CollateralContract.mkTestnetCollatContract(ctx, holdingContract(ctx).hashedPropBytes)
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

  def parseOutput(ctx: BlockchainContext, output: ErgoTransactionOutput): InputUTXO = {
    val holdingHex = holdingContract(ctx).ergoTreeHex
    val evalHex = evalContract(ctx).ergoTreeHex
    val payoutHex = payoutContract(ctx).ergoTreeHex

    val contract = output.getErgoTree match {
      case hex: String if (hex == holdingHex) =>
        holdingContract(ctx)
      case hex: String if (hex == evalHex) =>
        evalContract(ctx)
      case hex: String if (hex == payoutHex) =>
        payoutContract(ctx)
    }
    val value = output.getValue

    val regHexes = for (i <- 4 to 9) yield output.getAdditionalRegisters.getOrDefault("R" + i, "none")
    val validRegHexes = regHexes.filter(_ != "none")
    val registers = validRegHexes.map(ErgoValue.fromHex)
    UTXO(contract, value, registers = registers)
      .toInput(ctx, ErgoId.create(output.getTransactionId), output.getIndex.toShort)
  }
}
