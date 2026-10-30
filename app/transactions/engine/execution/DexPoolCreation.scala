package transactions.engine.execution

import api.LDPoolIndex
import api.LithosApiErrors.LithosBadRequest
import api.models._
import configs.{NodeContext, VerifiedPool}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDGenesisBuilder, LDHelpers}
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.sdk.ErgoId
import transactions.batching.lithosdex.LDPoolCreation.{Chain, Plan}
import transactions.batching.lithosdex._
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import work.lithos.mutations.Token

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Creating a LithosDex pool from this wallet, for `/dex/pools/create`: the chain [[LDPoolCreation]] builds,
 * funded by one reservation and sent one transaction at a time.
 *
 * Every box one transaction leaves for a later one is held at the wallet before the first send, so no other
 * selection can spend a fresh NFT box or change between sends. The chain stops at the first send the node
 * does not accept, and what was held for the transactions never sent is given back.
 */
class DexPoolCreation(nodeContext: NodeContext, walletSelector: EngineFunding, index: LDPoolIndex,
                      alive: () => Boolean = () => true) {
  import DexPoolCreation._
  import DexPoolExecution.{exact, positive}

  private def nodeApi: NodeApi = nodeContext.getNodeApi
  private def wallet: NodeWallet = nodeContext.getNodeWallet

  def check(request: LDPoolCreateRequest): LDPoolCreateQuote = {
    val p = plan(request.tokenX, request.tokenY, request.amountX, request.amountY, request.feeParams)
    def tokens(ts: Seq[Token]) = ts.map(t => LDTokenAmount(t.id.toString, LDAmounts(t.amount)))
    LDPoolCreateQuote(kindOf(p), p.tokenX.fold("ERG")(_.toString), p.tokenY.toString,
      LDFeeParams(p.feeParams.toArray, LDHelpers.FEE_DENOM), LDAmounts(p.amountX), LDAmounts(p.amountY),
      LDAmounts(LDGenesisBuilder.GENESIS_PROVISION_SHARES), LDAmounts(LDHelpers.MIN_SUPPLY),
      LDHelpers.MIN_SUPPLY.toDouble / LDHelpers.GENESIS_SUPPLY.toDouble, LDAmounts(p.lockedErg),
      tokens(p.lockedTokens), LDAmounts(p.poolValue), LDAmounts(p.vaultValue), LDAmounts(LDHelpers.PROVISION_MIN),
      LDAmounts(p.ergRequired), tokens(p.tokens), LDAmounts(p.networkFee), samePair(p) ++ exitFloor(p))
  }

  def create(request: LDPoolCreateExecuteRequest): LDPoolCreated = {
    if (!request.acknowledgeLocked.contains(true)) throw LithosBadRequest(
      "a new pool's vault and its locked supply can never be taken back: set 'acknowledgeLocked' to true")
    val p = plan(request.tokenX, request.tokenY, request.amountX, request.amountY, request.feeParams)
    require(alive(), "engine attempt was superseded")
    val funding = walletSelector.reserve(p.fundingValue, p.tokens)
    var held = Vector.empty[FundingAllocation]
    val chain = try {
      val built = nodeContext.getClient.execute(ctx => LDPoolCreation.chain(ctx, wallet, p, funding.inputs))
      readsBack(built, p)
      require(alive(), "engine attempt was superseded before sending")
      built.chained.foreach(box => held :+= walletSelector.reserveKnown(Seq(box)))
      built
    } catch {
      case NonFatal(ex) => held.foreach(_.release()); funding.release(); throw ex
    }
    send(chain, funding, chain.chained.map(_.id.toString).zip(held).toMap, p)
  }

  /** Sends each link in turn, each with the reservations of exactly the wallet boxes it spends. */
  private def send(chain: Chain, funding: FundingAllocation, held: Map[String, FundingAllocation],
                   p: Plan): LDPoolCreated = {
    val broadcast = new EngineBroadcast(walletSelector.walletRef, nodeApi)(walletSelector.executionContext)
    var stopped = false
    val steps = chain.links.zipWithIndex.map { case (link, i) =>
      val allocations = if (link.chained.isEmpty) Seq(funding) else link.chained.map(b => held(b.id.toString))
      val txId = CandidateTx.txId(link.tx)
      if (stopped) {
        allocations.foreach(_.release())
        LDPoolCreateStep(link.step, txId, Unsent, Some("an earlier transaction was not accepted"))
      } else Try(broadcast.send(link.tx, allocations, "dex-create:" + txId, alive)) match {
        case Success(r) =>
          stopped = r.outcome != EngineBroadcast.Accepted
          LDPoolCreateStep(link.step, r.txId, r.outcome, r.reason)
        // Nothing reached the node on this path, and the send released this link's own reservations
        case Failure(ex) if i == 0 =>
          held.values.foreach(_.release())
          throw ex
        case Failure(ex) =>
          stopped = true
          LDPoolCreateStep(link.step, txId, Unsent, Option(ex.getMessage))
      }
    }
    LDPoolCreated(steps.find(_.outcome != EngineBroadcast.Accepted).fold(EngineBroadcast.Accepted)(_.outcome),
      kindOf(p), chain.poolNft.toString, chain.vaultNft.toString, chain.provToken.toString, chain.ownerNft.toString,
      chain.provision.id.toString, steps)
  }

  /** Refuses before any send a provision that does not read back as the creator's, owned from this wallet. */
  private def readsBack(chain: Chain, p: Plan): Unit = {
    val guard =
      if (p.n2t) LDContracts(chain.poolNft, chain.vaultNft, chain.provToken, nodeContext.getNetwork).provisionGuard
      else LDT2TContracts(chain.poolNft, chain.vaultNft, chain.provToken).provisionGuard
    val prov = Try(LDBoxes.readProvision(chain.provision)).toOption
    val reads = chain.provision.contract.ergoTreeHex == guard.ergoTreeHex && prov.exists(v =>
      v.ownerNFT == chain.ownerNft && v.shares == LDGenesisBuilder.GENESIS_PROVISION_SHARES)
    if (!reads) throw new IllegalStateException(
      s"the provision box ${chain.provision.id} does not read back as the creator's provision on the new pool")
  }

  /**
   * An ERG/token pool box must always keep over MIN_RENT. When the locked share of its reserve is no more than
   * that, the last provider to leave cannot take everything out.
   */
  private def exitFloor(p: Plan): Seq[String] =
    if (p.n2t && BigInt(p.amountX) * LDHelpers.MIN_SUPPLY / LDHelpers.GENESIS_SUPPLY <= LDHelpers.MIN_RENT)
      Seq(s"the pool box must keep over ${LDHelpers.MIN_RENT} nanoERG, so while the creator is its only provider a " +
        "full exit leaves about that much ERG, and its share of the token, behind")
    else Seq.empty

  /** Pools the index already lists for this pair, with either token first. */
  private def samePair(p: Plan): Seq[String] = {
    val s = index.snapshot()
    val pair = p.tokenX.map(_.toString).toSet + p.tokenY.toString
    val listed = s.pools.filter(pool =>
      pool.kind == (if (p.n2t) VerifiedPool.Kind.N2T else VerifiedPool.Kind.T2T) &&
        (pool.tokenX.map(_.toLowerCase).toSet + pool.tokenY.toLowerCase) == pair)
    (if (s.status != "ready") Seq("the pool index is still building, so pools already trading this pair were not looked for")
    else Seq.empty) ++ listed.map(pool =>
      s"pool ${pool.nft} already trades this pair" + (if (pool.verified) ", and is on the verified list" else ""))
  }

  private def plan(tokenX: String, tokenY: String, amountX: String, amountY: String,
                   feeParams: Option[LDFeeParams]): Plan = {
    val y = tokenIdOf("tokenY", tokenY)
    val x = if (tokenX.equalsIgnoreCase("ERG")) None else Some(tokenIdOf("tokenX", tokenX))
    if (x.contains(y)) throw LithosBadRequest("'tokenX' and 'tokenY' must be different tokens")
    val (ax, ay) = (positive("amountX", amountX), positive("amountY", amountY))
    if (x.isEmpty && ax < LDHelpers.MIN_RENT) throw LithosBadRequest(
      s"'amountX' must be at least ${LDHelpers.MIN_RENT} nanoERG, the least an ERG/token pool box may hold")
    val p = Plan(x, y, ax, ay, feeParams.fold(LDHelpers.GENESIS_FEE_PARAMS.toSeq)(numerators))
    exact { p.fundingValue; p.tokens }
    p
  }

  private def numerators(f: LDFeeParams): Seq[Long] = {
    val d = LDHelpers.FEE_DENOM
    val fees = Seq(f.dexFeeNum, f.ergFeeNum, f.tokenFeeNum)
    if (f.feeDenom != d || !LDGenesisBuilder.feesBounded(fees)) throw LithosBadRequest(
      s"'feeParams' must have feeDenom $d, dexFeeNum in (0, $d], and ergFeeNum and tokenFeeNum in [0, $d)")
    fees
  }
}

object DexPoolCreation {

  /** A step not sent because an earlier one was not accepted. */
  final val Unsent = "unsent"

  private val TokenId = "[0-9a-fA-F]{64}"

  private def kindOf(p: Plan): String = if (p.n2t) VerifiedPool.Kind.N2T.name else VerifiedPool.Kind.T2T.name

  private def tokenIdOf(field: String, raw: String): ErgoId =
    if (raw.matches(TokenId)) ErgoId.create(raw.toLowerCase)
    else throw LithosBadRequest(s"'$field' must be a 64-character hex token id" +
      (if (field == "tokenX") " or ERG" else "") + s", got $raw")
}
