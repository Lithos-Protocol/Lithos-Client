package stats

import arbitrage.contracts.ArbStakeContract
import configs.VerifiedRegistry
import forwards.DFHelpers
import forwards.contracts.DFContracts
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import state.messages.{BlockInfo, BlockTx, TxOutput}
import transactions.batching.ergodex.{ErgoDexContracts, ErgoDexExecution, ErgoDexOrder, ErgoDexPool, MachinaOrder}
import transactions.batching.forwards.DFBoxes
import transactions.batching.lithosdex.{DexContracts, LDDeployments, LDT2TOrder, LithosDexOrder}
import work.lithos.mutations.Contract

import scala.collection.concurrent.TrieMap
import scala.util.Try

/** Recognizes the executed pool/order/output layout. Carried executor boxes are never counted again. */
private[stats] object BatchingStatistics {
  def poolProtocol(input: TxOutput, network: NetworkType): Option[String] = {
    if (input.ergoTree == ErgoDexContracts.NativePoolErgoTree || input.ergoTree == ErgoDexContracts.TokenPoolErgoTree)
      Some("ergodex")
    else if (lithosDexPool(input, network) || lithosDexT2TPool(input)) Some("lithosdex")
    else None
  }

  /** The pool contract's template as hex, which every LithosDex deployment's pool tree ends with. */
  private val poolTemplates = TrieMap.empty[NetworkType, String]

  /** The token/token pool's template as hex. Every token/token deployment shares it, whatever its ids. */
  private lazy val t2tPoolTemplate: String = {
    val any = DexContracts(NetworkType.MAINNET).poolNFT
    Hex.toHexString(lithosdex.contracts.LDT2TContracts(any, any, any).liquidityPool.ergoTree.template)
  }

  /**
   * Any LithosDex deployment's pool, ERG:LIT included: a box whose contracts, built from its own ids,
   * reproduce its tree. The template suffix is checked first, so most boxes cost no build at all.
   */
  private def lithosDexPool(box: TxOutput, network: NetworkType): Boolean =
    box.ergoTree.endsWith(poolTemplates.getOrElseUpdate(network,
      Hex.toHexString(DexContracts(network).liquidityPool.ergoTree.template))) &&
      LDDeployments.verify(network, box.ergoTree, assetsOf(box)).isDefined

  /** Any token/token LithosDex deployment's pool, by the same test. */
  private def lithosDexT2TPool(box: TxOutput): Boolean =
    box.ergoTree.endsWith(t2tPoolTemplate) && LDDeployments.verifyT2T(box.ergoTree, assetsOf(box)).isDefined

  /** The network's forwards deployment, or None while it has no accumulator. Built once per network. */
  private val forwardsDeployments = TrieMap.empty[NetworkType, Option[DFContracts]]

  private def forwardsDeployment(network: NetworkType): Option[DFContracts] =
    forwardsDeployments.getOrElseUpdate(network,
      DFHelpers.getAccNFT(network).flatMap(nft => Try(DFContracts(nft, DFHelpers.getLitId(network))).toOption))

  def transactionFee(tx: BlockTx): BigInt = tx.outputs.filter(_.ergoTree == Contract.FEE.ergoTreeHex)
    .map(out => BigInt(out.value)).sum

  private def assetsOf(out: TxOutput): Seq[(String, Long)] = out.assets.map(t => t.id.toString -> t.amount)

  private def box(out: TxOutput): NodeBox = NodeBox(out.id, out.txId, out.value, out.index,
    out.creationHeight, out.ergoTree, out.assets.map(t => NodeAsset(t.id.toString, t.amount)),
    NodeRegisters(out.registers.zipWithIndex.map { case (value, index) => s"R${index + 4}" -> value }.toMap))

  /**
   * Tokens the executor kept: those in outputs outside `protocolOutputs` (fee outputs aside, which go to the
   * block's lender) less those its own inputs, outside `protocolInputs`, brought. Only gains count, so a carried
   * takings box nets out. Verified tokens are named; the rest are counted. Nothing when an input is unresolved.
   */
  private def takings(tx: BlockTx, inputs: Seq[Option[TxOutput]], protocolInputs: Set[Int],
                      protocolOutputs: Set[Int], registry: VerifiedRegistry): (Seq[TokenAmount], Int) = {
    val own = inputs.indices.filterNot(protocolInputs)
    if (own.exists(i => inputs(i).isEmpty)) return (Seq.empty, 0)
    val net = scala.collection.mutable.LinkedHashMap.empty[String, BigInt]
    def move(out: TxOutput, sign: Int): Unit = out.assets.foreach { t =>
      val id = t.id.toString
      net.update(id, net.getOrElse(id, BigInt(0)) + sign * BigInt(t.amount))
    }
    tx.outputs.zipWithIndex.foreach { case (out, i) =>
      if (!protocolOutputs(i) && out.ergoTree != Contract.FEE.ergoTreeHex) move(out, 1)
    }
    own.foreach(i => move(inputs(i).get, -1))
    val gains = net.filter(_._2 > 0).toSeq
    val (verified, other) = gains.partition { case (id, _) => registry.token(id).isDefined }
    (verified.map { case (id, amount) => TokenAmount(id, amount.toString) }, other.size)
  }

  /** `minerTree` is this client's own script; an execution whose takings went to it is this client's. */
  def read(block: BlockInfo, tx: BlockTx, network: NetworkType, minerTree: String): Vector[BatchingFee] =
    read(block, tx, network, minerTree, forwardsDeployment(network), VerifiedRegistry.forNetwork(network))

  /** The same, against a given forwards deployment and verified list. */
  def read(block: BlockInfo, tx: BlockTx, network: NetworkType, minerTree: String,
           forwardsContracts: Option[DFContracts], registry: VerifiedRegistry): Vector[BatchingFee] = {
    val inputs = tx.inputs.map(in => block.inputBox(in.id))
    val liquidated = sigmaFi(tx, inputs, minerTree)
    if (liquidated.nonEmpty) return liquidated
    if (inputs.size < 2 || inputs.head.isEmpty || tx.outputs.size < 2) return Vector.empty
    forwardsContracts.flatMap(forwards(tx, inputs, _, registry, minerTree))
      .getOrElse(executions(tx, inputs, network, registry, minerTree))
  }

  /**
   * A spend of the forwards accumulator, recording an epoch, settling a position, or both. None when input 0 is
   * not the accumulator. What the builder kept is everything outside the accumulator's successor and the
   * position's payouts, less what its own inputs brought: 5 LIT a recording and a settlement's rewards.
   * A spend that kept nothing, such as a top-up of the accumulator, is no work and gives an empty result.
   */
  private def forwards(tx: BlockTx, inputs: Seq[Option[TxOutput]], contracts: DFContracts,
                       registry: VerifiedRegistry, minerTree: String): Option[Vector[BatchingFee]] = {
    val acc = inputs.head.get
    val next = tx.outputs.head
    def holdsAcc(out: TxOutput) = out.ergoTree.equalsIgnoreCase(contracts.accumulator.ergoTreeHex) &&
      out.assets.headOption.exists(t => t.id == contracts.accNFT && t.amount == 1L)
    if (!holdsAcc(acc) || !holdsAcc(next)) None
    else Some {
      val position = inputs(1).flatMap(p => DFBoxes.position(box(p), contracts).map(p -> _))
      val payoutTrees = position.toSeq.flatMap { case (_, p) => Seq(p.long.ergoTreeHex, p.short.ergoTreeHex) }.toSet
      val payouts = tx.outputs.indices.drop(1).filter(i => payoutTrees(tx.outputs(i).ergoTree)).toSet
      val protocolIn = Set(0) ++ position.map(_ => 1)
      val gross = BigInt(acc.value) + position.map(p => BigInt(p._1.value)).getOrElse(BigInt(0)) -
        next.value - payouts.toSeq.map(i => BigInt(tx.outputs(i).value)).sum
      val (tokens, otherTokens) = takings(tx, inputs, protocolIn, payouts + 0, registry)
      val local = tx.outputs.indices.drop(1).exists(i => !payouts(i) && tx.outputs(i).ergoTree == minerTree)
      if (gross < 0 || (gross == 0 && tokens.isEmpty && otherTokens == 0)) Vector.empty
      else Vector(BatchingFee(tx.id, position.fold(acc.id)(_._1.id), "forwards", gross.toString,
        transactionFee(tx).toString, local, tokens, otherTokens))
    }
  }

  /** The protocol name SigmaFi liquidations are recorded under. */
  final val SigmaFiProtocol = "sigmafi"

  /**
   * Standard maturity liquidations only: a custom expression may convert collateral, so the difference in
   * ERG values does not measure its revenue. Match the expression, collateral, tags and singleton burn before
   * attributing the bounded ERG difference as a fee. The miner fee is split across the records,
   * so totals count it once and a broadcast batch's every record shows a fee, as a fee-less one in a Lithos
   * block is what marks this miner's own candidate work.
   */
  private def sigmaFi(tx: BlockTx, inputs: Seq[Option[TxOutput]], minerTree: String): Vector[BatchingFee] = {
    val contracts = sigmafi.contracts.SigmaFiContracts
    val vault = contracts.vault.ergoTreeHex
    def maturityExpression(hex: String): Boolean =
      Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue).toOption.exists {
        case bytes: sigma.Coll[Byte @unchecked] =>
          Try(bytes.toArray.sameElements(contracts.MaturityLiquidation.bytes)).getOrElse(false)
        case _ => false
      }
    val settled = inputs.indices.toVector.flatMap { i =>
      for {
        in <- inputs(i)
        if !tx.inputs(i).spendingProof.exists(_.ext.contains("127"))
        if tx.inputs(i).spendingProof.flatMap(_.ext.get("0")).exists(maturityExpression)
        bond <- transactions.batching.sigmafi.SigmaFiBoxes.bond(box(in))
        if bond.liquidation.contains(contracts.MaturityLiquidation)
        outputIndex <- tx.inputs(i).spendingProof.flatMap(_.ext.get("1")).flatMap { hex =>
          Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex)).toOption
            .filter(_.getType == org.ergoplatform.appkit.ErgoType.integerType())
            .map(_.getValue.asInstanceOf[Integer].intValue).filter(_ >= 0)
        }
        out <- tx.outputs.lift(outputIndex) if out.ergoTree.equalsIgnoreCase(vault)
        settlement <- transactions.batching.sigmafi.SigmaFiBoxes.vault(box(out))
        if settlement.bondId.contains(bond.boxId) && settlement.asset == bond.asset &&
          settlement.box.assets == bond.collateral && settlement.settlementPath.contains(1.toByte)
        if !tx.outputs.exists(_.assets.exists(_.id == bond.asset))
        gross = BigInt(in.value) - out.value if gross >= 0 && gross <= bond.batchingFee
      } yield (outputIndex, bond.boxId, gross)
    }
    if (settled.isEmpty) Vector.empty
    else {
      val vaults = settled.map(_._1).toSet
      val local = tx.outputs.zipWithIndex.exists { case (out, i) => !vaults(i) && out.ergoTree == minerTree }
      val fee = transactionFee(tx)
      val (share, remainder) = (fee / settled.size, fee % settled.size)
      settled.zipWithIndex.map { case ((_, bondId, gross), k) =>
        val paid = share + (if (k < remainder) 1 else 0)
        BatchingFee(tx.id, bondId, SigmaFiProtocol, gross.toString, paid.toString, local)
      }
    }
  }

  /** One arb cycle in a block: its record, and the ids of its legs in the order they spend the carry. */
  final case class ArbCycle(fee: BatchingFee, legs: Seq[String])

  /**
   * A fee-less pool swap with one other input, the carry, and the carry handed on at output 1: the pool at input 0
   * and its successor at output 0 under the same tree and NFT. A leg that ends a cycle splits its profit off to
   * output 2. Anything else in the transaction, a fee output included, makes it no leg.
   */
  private final case class ArbLegTx(tx: BlockTx, carryIn: TxOutput, nft: String) {
    def carryOut: TxOutput = tx.outputs(1)
    def profit: Option[TxOutput] = tx.outputs.lift(2)
  }

  private def arbLeg(block: BlockInfo, tx: BlockTx, network: NetworkType): Option[ArbLegTx] =
    if (tx.inputs.size != 2 || tx.outputs.size < 2 || tx.outputs.size > 3 ||
      tx.outputs.exists(_.ergoTree == Contract.FEE.ergoTreeHex)) None
    else for {
      pool <- block.inputBox(tx.inputs.head.id)
      carry <- block.inputBox(tx.inputs(1).id)
      nft <- pool.assets.headOption.filter(_.amount == 1L).map(_.id.toString)
      next = tx.outputs.head
      if next.ergoTree == pool.ergoTree && next.assets.headOption.exists(t => t.id.toString == nft && t.amount == 1L)
      if tx.outputs(1).ergoTree != pool.ergoTree && poolProtocol(pool, network).isDefined
    } yield ArbLegTx(tx, carry, nft)

  /**
   * Arb cycles this block carries, in any block: chains of legs where each spends the carry the one before handed
   * on, ending at a leg that splits off profit. A cycle starts from an ERG-only box at a plain key or at a key's
   * arb stake script, passes the carry at one tree throughout, and returns the start's ERG whole to that tree,
   * with no tokens, beside an ERG-only profit output at that tree, at the stake's owner's plain key, or at `true`,
   * where the holding top-up can take it. The next cycle may start from the returned box. Gross is the profit;
   * `local` means the carry was this client's: at `minerTree` or at its key's arb stake script.
   */
  def arbCycles(block: BlockInfo, network: NetworkType, minerTree: String): Vector[ArbCycle] = {
    val legs = block.txs.flatMap(arbLeg(block, _, network)).toVector
    val byCarryIn = legs.map(l => l.carryIn.id -> l).toMap
    val handedOn = legs.map(_.carryOut.id).toSet
    legs.filterNot(l => handedOn(l.carryIn.id)).flatMap { start =>
      // A chain of legs is a path through distinct transactions, so it ends within as many steps as there are legs
      val chain = Iterator.iterate(Option(start))(_.flatMap(l => byCarryIn.get(l.carryOut.id)))
        .take(legs.size).takeWhile(_.isDefined).map(_.get).toVector
      cycles(chain, minerTree)
    }
  }

  /** `chain` cut after each leg with a profit output, keeping each piece that has the whole shape of a cycle. */
  private def cycles(chain: Vector[ArbLegTx], minerTree: String): Vector[ArbCycle] = {
    val ends = chain.indices.filter(i => chain(i).profit.isDefined)
    ends.zip(-1 +: ends).flatMap { case (end, before) =>
      val piece = chain.slice(before + 1, end + 1)
      val first = piece.head.carryIn
      val returned = piece.last.carryOut
      val tree = returned.ergoTree
      val profit = piece.last.profit.get
      val owner = ArbStakeContract.ownerP2PK(first.ergoTree)
      val shaped = piece.size >= 2 && first.assets.isEmpty && (isP2PK(first.ergoTree) || owner.isDefined) &&
        piece.forall(_.carryOut.ergoTree == tree) && returned.assets.isEmpty && returned.value == first.value &&
        profit.assets.isEmpty && profit.value > 0L &&
        (profit.ergoTree == tree || owner.contains(profit.ergoTree) ||
          profit.ergoTree == Contract.SIGMA_TRUE.ergoTreeHex)
      if (!shaped) None
      else Some(ArbCycle(BatchingFee(piece.last.tx.id, piece.head.nft, ArbProtocol, profit.value.toString, "0",
        local = tree == minerTree || owner.contains(minerTree)), piece.map(_.tx.id)))
    }.toVector
  }

  /** The protocol name arb cycles are recorded under. */
  final val ArbProtocol = "arb"

  /** A P2PK tree: header 0x00, then a constant sigma proposition holding one 33-byte group element. */
  private def isP2PK(tree: String): Boolean = tree.length == 72 && tree.startsWith("0008cd")

  private def executions(tx: BlockTx, inputs: Seq[Option[TxOutput]], network: NetworkType, registry: VerifiedRegistry,
                         minerTree: String): Vector[BatchingFee] = {
    val pool = inputs.head.get
    val next = tx.outputs.head
    val nft = pool.assets.headOption.filter(_.amount == 1L).map(_.id.toString)
    if (nft.isEmpty || next.ergoTree != pool.ergoTree ||
      !next.assets.headOption.exists(t => nft.contains(t.id.toString) && t.amount == 1L)) return Vector.empty

    /*
     * The order sits at input `orderIndex` and its owner's output at `rewardIndex`. A redemption spends a
     * provision at input 1 and a deposit creates one at output 1; both belong to the protocol, not the executor.
     */
    def result(orderIndex: Int, owner: String, rewardIndex: Int, provisionIn: Boolean, provisionOut: Boolean,
               protocol: String, expected: Option[Long]): Vector[BatchingFee] = {
      val order = inputs(orderIndex).get
      tx.outputs.lift(rewardIndex).filter(_.ergoTree == owner).toVector.flatMap { reward =>
        val provisionInValue = if (provisionIn) inputs(1).map(p => BigInt(p.value)) else Some(BigInt(0))
        val provisionOutValue = if (provisionOut) tx.outputs.lift(1).map(p => BigInt(p.value)) else Some(BigInt(0))
        provisionInValue.zip(provisionOutValue).toVector.flatMap { case (provIn, provOut) =>
          // Conservation across the pool, order, provision and designated owner output measures the
          // amount left for execution, independently of any takings carried from earlier transactions.
          val gross = BigInt(pool.value) + order.value + provIn - next.value - provOut - reward.value
          val fee = transactionFee(tx)
          // Past the pool and the owner's reward, the takings box is what the executor keeps. The reward
          // is skipped so filling this client's own order is not mistaken for executing it.
          val local = tx.outputs.zipWithIndex.exists { case (out, i) => i != 0 && i != rewardIndex && out.ergoTree == minerTree }
          if (gross < 0 || expected.exists(BigInt(_) != gross) || fee > gross) Vector.empty
          else {
            val protocolIn = Set(0, orderIndex) ++ (if (provisionIn) Set(1) else Set.empty)
            val protocolOut = Set(0, rewardIndex) ++ (if (provisionOut) Set(1) else Set.empty)
            val (tokens, otherTokens) = takings(tx, inputs, protocolIn, protocolOut, registry)
            Vector(BatchingFee(tx.id, order.id, protocol, gross.toString, fee.toString, local, tokens, otherTokens))
          }
        }
      }
    }
    // A Machina fill through an ERG pool of `token`: the gap is what the pool and the order's child leave between
    // them. Its child is the order recreated or its owner paid out, at output 1. Counted as ErgoDEX work through
    // either DEX's pools, since the order is ErgoDEX's batcher's to route.
    def machina(token: String): Vector[BatchingFee] = inputs(1).toVector.flatMap { input =>
      MachinaOrder.parse(box(input)).filter(_.tokenId == token).toVector.flatMap { order =>
        tx.outputs.lift(1).map(_.ergoTree)
          .filter(tree => tree == input.ergoTree || tree == Hex.toHexString(order.ownerPropBytes))
          .toVector.flatMap(child => result(1, child, 1, provisionIn = false, provisionOut = false, "ergodex", None))
      }
    }
    ErgoDexPool.parse(box(pool)) match {
      case Some(before) =>
        inputs(1).toVector.flatMap { input =>
          val ergoDex = ErgoDexOrder.parse(box(input)).filter(o => nft.contains(o.poolNft)).toVector.flatMap { order =>
            ErgoDexExecution.movement(order, before).toVector.flatMap { case (quote, after) =>
              val actual = ErgoDexPool.parse(box(next))
              if (!actual.exists(p => p.copy(boxId = after.boxId) == after) || quote < order.minQuoteAmount) Vector.empty
              else result(1, Hex.toHexString(order.redeemerPropBytes), 1, provisionIn = false, provisionOut = false,
                "ergodex", Some(order.dexFee(quote)))
            }
          }
          if (ergoDex.nonEmpty) ergoDex else machina(before.yId)
        }
      case None if lithosDexPool(pool, network) =>
        val orders = Vector(1, 2).flatMap { at =>
          inputs.lift(at).flatten.toVector.flatMap { input =>
            LithosDexOrder.parse(box(input)).filter(o => nft.contains(o.poolNft)).toVector.flatMap {
              case order: LithosDexOrder.Deposit if at == 1 && tx.outputs.size > 2 =>
                result(1, order.terms.redeemerTree, 2, provisionIn = false, provisionOut = true, "lithosdex",
                  Some(order.terms.executorFee))
              case order: LithosDexOrder.Redeem if at == 2 && inputs(1).isDefined =>
                result(2, order.terms.redeemerTree, 1, provisionIn = true, provisionOut = false, "lithosdex",
                  Some(order.terms.executorFee))
              case order @ (_: LithosDexOrder.SwapSell | _: LithosDexOrder.SwapBuy) if at == 1 =>
                result(1, order.terms.redeemerTree, 1, provisionIn = false, provisionOut = false, "lithosdex",
                  Some(order.terms.executorFee))
              case _ => Vector.empty
            }
          }
        }
        // An ERG pool's token is its second token
        if (orders.nonEmpty) orders else pool.assets.lift(1).toVector.flatMap(t => machina(t.id.toString))
      // A token/token pool's orders take the same positions as an ERG/token pool's, and pay the same ERG fee.
      case None if lithosDexT2TPool(pool) =>
        Vector(1, 2).flatMap { at =>
          inputs.lift(at).flatten.toVector.flatMap { input =>
            LDT2TOrder.parse(box(input)).filter(o => nft.contains(o.poolNft)).toVector.flatMap {
              case order: LDT2TOrder.Deposit if at == 1 && tx.outputs.size > 2 =>
                result(1, order.terms.redeemerTree, 2, provisionIn = false, provisionOut = true, "lithosdex",
                  Some(order.terms.executorFee))
              case order: LDT2TOrder.Redeem if at == 2 && inputs(1).isDefined =>
                result(2, order.terms.redeemerTree, 1, provisionIn = true, provisionOut = false, "lithosdex",
                  Some(order.terms.executorFee))
              case order: LDT2TOrder.Swap if at == 1 =>
                result(1, order.terms.redeemerTree, 1, provisionIn = false, provisionOut = false, "lithosdex",
                  Some(order.terms.executorFee))
              case _ => Vector.empty
            }
          }
        }
      case _ => Vector.empty
    }
  }
}
