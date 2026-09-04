package utils

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.restapi.client.ErgoTransactionOutput
import sigma.SigmaProp
import sigma.ast.ErgoTree
import sigma.data.{CSigmaProp, ProveDlog, SigmaBoolean, SigmaLeaf}
import sigma.serialization.GroupElementSerializer
import transactions.ProtocolContracts
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import java.math.{BigDecimal, BigInteger, RoundingMode}
import scala.util.Try

object Helpers {

  // Every accessor below reads the one compiled set rather than compiling its own.

  def payoutContract(ctx: BlockchainContext): Contract = ProtocolContracts(ctx).payout

  def evalContract(ctx: BlockchainContext): Contract = ProtocolContracts(ctx).eval

  def holdingContract(ctx: BlockchainContext): Contract = ProtocolContracts(ctx).holding

  /** The rules a holding spend must hand to context variable 64. Never sits on a box. */
  def holdingLogicContract(ctx: BlockchainContext): Contract = ProtocolContracts(ctx).holdingLogic

  def dataBoxContract(ctx: BlockchainContext): Contract = ProtocolContracts(ctx).minerData

  /** The rules the data box's guard runs out of context var 64. Never sits on a box. */
  def dataBoxLogic(ctx: BlockchainContext): Contract = ProtocolContracts(ctx).minerDataLogic

  def mdErgoTrees(client: ErgoClient): Seq[String] = client.execute(ctx => mdErgoTrees(ctx))

  def mdErgoTrees(ctx: BlockchainContext): Seq[String] =
    Seq(ProtocolContracts(ctx).minerDictionary.ergoTreeHex)

  def rollupErgoTrees(client: ErgoClient): Seq[String] = {
    client.execute{
      ctx =>
        rollupErgoTrees(ctx)
    }
  }

  def rollupErgoTrees(ctx: BlockchainContext): Seq[String] = {
    val contracts = ProtocolContracts(ctx)
    Seq(contracts.holding.ergoTreeHex, contracts.eval.ergoTreeHex, contracts.payout.ergoTreeHex)
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
