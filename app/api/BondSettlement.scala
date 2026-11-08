package api

import api.models._
import node.NodeApi
import node.model.{IndexedBox, IndexedTransaction, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import scorex.crypto.hash.Blake2b256
import transactions.batching.sigmafi.{SigmaFiBond, SigmaFiBoxes}

import scala.util.Try

/** Classifies the actual confirmed spend path; maturity and an empty live scan do not prove settlement. */
object BondSettlement {
  final case class State(status: String, settlement: Option[SFSettlement] = None, reason: Option[String] = None,
                         liveBond: Option[SigmaFiBond] = None)
  private def unknown(reason: String): State = State("Unknown", reason = Some(reason))

  def read(api: NodeApi, bondId: String, assetId: String, height: Int): State =
    read(api, bondId, assetId, height, id => api.indexedTransactionById(id))

  def read(api: NodeApi, bondId: String, assetId: String, height: Int,
           readTransaction: String => Try[Option[IndexedTransaction]]): State = {
    val result = for {
      indexed <- api.indexedBoxById(bondId)
      state <- Try(indexed match {
        case Some(box) if box.boxId == bondId =>
          SigmaFiBoxes.bond(box.box).filter(_.asset.toString == assetId) match {
            case Some(bond) => box.spentTransactionId match {
              case None => State(if (height.toLong + 1 >= bond.maturity) "Matured" else "Open", liveBond = Some(bond))
              case Some(txId) => readTransaction(txId).get match {
                case Some(tx) if tx.id == txId && tx.inclusionHeight <= height => classify(bond, box, tx)
                case _ => unknown("the node has not returned the bond's settlement transaction")
              }
            }
            case None => unknown("the indexed bond no longer matches its verified mint")
          }
        case _ => unknown("the node has not returned the verified bond box")
      })
    } yield state
    result.getOrElse(unknown("the node could not read the bond's confirmed settlement state"))
  }

  private def classify(bond: SigmaFiBond, indexed: IndexedBox, tx: IndexedTransaction): State = {
    val position = tx.inputs.indexWhere(_.boxId == bond.boxId)
    val proof = tx.inputs.lift(position).flatMap(_.spendingProof).orElse(indexed.spendingProof)
    val extension = proof.map(_.extension).getOrElse(Map.empty)
    def outputIndex(variable: String): Option[Int] = extension.get(variable).flatMap { hex =>
      Try(ErgoValue.fromHex(hex)).toOption.filter(_.getType == ErgoType.integerType())
        .map(_.getValue.asInstanceOf[Integer].intValue).filter(_ >= 0)
    }
    val settlementIndex = outputIndex("1")
    val settlement = settlementIndex.flatMap(tx.outputs.lift).flatMap(b => SigmaFiBoxes.vault(b.box))
      .filter(v => v.bondId.contains(bond.boxId) && v.asset == bond.asset)
    if (position < 0 || tx.inclusionHeight <= 0 || proof.isEmpty || settlement.isEmpty ||
      tx.outputs.exists(_.assets.exists(_.tokenId == bond.asset.toString)))
      return unknown("the indexed spend does not establish a tagged vault settlement and singleton burn")
    // Rent can bypass a guard; do not label that path as a contractual repayment or liquidation.
    if (extension.contains("127")) return unknown("the bond spend carries a storage-rent extension")
    val vault = settlement.get
    val payout = SFVaultView(vault.boxId, vault.bondId, vault.asset.toString, vault.box.value.toString, tokens(vault.box))
    val expression = extension.get("0")
    val authenticated = expression.flatMap { hex =>
      Try(ErgoValue.fromHex(hex)).toOption.filter(_.getType == ErgoType.collType(ErgoType.byteType()))
        .map(_.getValue.asInstanceOf[sigma.Coll[Byte]].toArray)
    }.exists(bytes => Blake2b256.hash(bytes).sameElements(bond.liqScriptHash))
    if (expression.isDefined) {
      if (!authenticated || !vault.settlementPath.contains(1.toByte))
        unknown("the liquidation expression or settlement marker does not match the bond's agreed path")
      else State("Liquidated", Some(SFSettlement(tx.id, tx.inclusionHeight, Some(tx.timestamp).filter(_ > 0), bond.boxId, payout,
        None, Some(Hex.toHexString(bond.liqScriptHash)))))
    } else {
      val returned = outputIndex("2").filterNot(i => settlementIndex.contains(i)).flatMap(tx.outputs.lift).map(_.box).filter { box =>
        SigmaFiBoxes.parse(box.ergoTree).exists(t => SigmaFiBoxes.hashOf(t).sameElements(bond.borrowerHash)) &&
          box.value >= bond.collateralErg && box.assets == bond.collateral &&
          box.additionalRegisters.get(4).exists(hex => Try(ErgoValue.fromHex(hex)).toOption.exists(v =>
            v.getType == ErgoType.collType(ErgoType.byteType()) &&
              v.getValue.asInstanceOf[sigma.Coll[Byte]].toArray.sameElements(Hex.decode(bond.boxId))))
      }
      val paid = vault.box.value >= bond.batchingFee && bond.repayToken.fold(vault.box.value - bond.batchingFee >= bond.repayment) { token =>
        vault.box.value - bond.batchingFee >= 1000000L && vault.box.assets.size == 1 &&
          vault.box.assets.head.tokenId == token.toString && vault.box.assets.head.amount >= bond.repayment
      }
      if (!vault.settlementPath.contains(0.toByte) || tx.inclusionHeight >= bond.maturity || !paid || returned.isEmpty)
        unknown("the indexed spend does not establish a full repayment and collateral return")
      else State("Repaid", Some(SFSettlement(tx.id, tx.inclusionHeight, Some(tx.timestamp).filter(_ > 0), bond.boxId, payout,
        returned.map(b => SFBoxValue(b.boxId, b.value.toString, tokens(b))), None)))
    }
  }

  private def tokens(box: NodeBox): Seq[SFTokenAmount] =
    box.assets.map(a => SFTokenAmount(a.tokenId, a.amount.toString))
}
