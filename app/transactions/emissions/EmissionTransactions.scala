package transactions.emissions

import configs.EmissionConfig
import lfsm.{CollateralParams, EmissionSchedule, LFSMHelpers}
import mutations.{NodeWallet, NotEnoughInputsException}
import node.MutationConversions._
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeTransaction, Paging, SortDirection}
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoType, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import sigma.{Coll, Colls, SigmaProp}
import stratum.CollateralNotFoundException
import transactions.ProtocolContracts.{hex, lenderEntry}
import transactions.{CompiledContracts, ProtocolContracts}
import transactions.wallet.{WalletReservation, WalletSource}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** The emission box's state, read off its registers and tokens. */
case class EmissionState(currentBlock: Int,
                         lenderSet: Seq[Array[Byte]],
                         head: Long,
                         tail: Long,
                         nft: Token,
                         queueToken: Token,
                         collatToken: Token,
                         lit: Option[Token],
                         value: Long) {

  def backlog: Long = tail - head

  def litHeld: Long = lit.map(_.amount).getOrElse(0L)

  /** True while the active set is still filling, which decides whether an Activate needs a
   * proof-of-spend box to free a slot. */
  def bootstrapping: Boolean = lenderSet.size < EmissionSchedule.MAX_ACTIVE

  def holds(entry: Array[Byte]): Boolean = lenderSet.exists(_.sameElements(entry))
}

/** The emission config's registers: R4 permit params, R5 enforcer hash, R6 extension hash, R7
 * rollup holding hash. R5 and R6 hold hashed VALUE bytes while R7 holds hashed PROP bytes. */
case class ConfigState(permitParams: Seq[Long],
                       enforcerHash: Array[Byte],
                       extensionHash: Array[Byte],
                       rollupHash: Array[Byte]) {

  def permitAt(backlog: Long): Long = CollateralParams.permitAt(backlog, permitParams)
}

/**
 * The emission box as it will be when the next block is assembled, plus the unconfirmed transactions
 * that got it there. The ancestors matter because a transaction chaining off an unconfirmed parent
 * is only valid in a block that also carries the parent.
 */
case class EmissionTip(box: InputUTXO, ancestors: Seq[NodeTransaction])

/** One built emission transaction and which path produced it. */
case class EmissionSpend(tx: SignedTransaction,
                         kind: String,
                         reservations: Seq[WalletReservation] = Seq.empty)

object EmissionSpend {
  final val Join = "join"
  final val Activate = "activate"
  final val Clear = "clear"
}

/**
 * The three emission spending paths - Join, Activate and Clear - and the lookups they need.
 *
 * Structured as [[transactions.rollups.RollupTransactions]] is: pure builders taking the boxes they
 * need and returning a signed transaction, with [[EmissionHandler]] owning the timers and the
 * broadcasting.
 *
 * Two build modes share the code. FUNDED adds a fee output and wallet inputs, for the mempool.
 * FEE-LESS has neither, for this miner's own block candidate; Activate and Clear are self-funding
 * because the queue box they consume carries 2.915 ERG. A Join is always funded - it is the
 * transaction that puts the principal up.
 */
class EmissionTransactions(prover: NodeWallet,
                           nodeApi: NodeApi,
                           config: EmissionConfig,
                           walletSource: WalletSource) {

  private val logger: Logger = LoggerFactory.getLogger("EmissionTransactions")

  /** Boxes already consumed in the mempool are no use to us, confirmed or not. */
  private val liveOnly = MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)

  /** Everything live, mempool included - what a duplicate-key check has to see. */
  private val withMempool = MempoolOptions.WithMempool

  def contracts(ctx: BlockchainContext): CompiledContracts = ProtocolContracts(ctx)

  /**
   * This client's own script, hex-encoded once.
   *
   * `Contract.ergoTreeHex` is a def that re-serialises the tree and hex-encodes it on every call, so
   * anything comparing against a fixed contract inside a filter has to hoist it or pay for a fresh
   * kilobyte-scale string per element.
   */
  private lazy val walletTree: String = prover.contract.ergoTreeHex

  // ══════════════════════════════════════════════════════════════════════════
  //  Reading live state
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * The emission box as it will be when the next block is assembled.
   *
   * Walked forward one spend at a time rather than asked for with `includeUnconfirmed`, because the
   * tip alone is not enough: a transaction chaining off an unconfirmed parent is only valid in a
   * block that carries the parent too, so a candidate has to be built parents-first. Those spends
   * belong to other lenders and are chained onto, never displaced.
   */
  def emissionTip(ctx: BlockchainContext): EmissionTip = {
    val nft = LFSMHelpers.EMISSION_NFT.toString
    val guardTree = contracts(ctx).guard.ergoTreeHex
    val confirmed = nodeApi
      .unspentBoxesByTokenId(nft, Paging.all(8), SortDirection.Desc, withMempool)
      .getOrElse(Seq.empty[IndexedBox])
      .find(b => carriesOne(b, LFSMHelpers.EMISSION_NFT) && b.ergoTree == guardTree)
      .map(_.toInputUTXO(ctx))
      .getOrElse(throw new CollateralNotFoundException(
        s"no unspent emission box carrying $nft - is the node indexed, and has emission launched?"))

    if (!config.mempoolChaining) EmissionTip(confirmed, Seq.empty[NodeTransaction])
    else {
      var tip = confirmed
      var chain = Vector.empty[NodeTransaction]
      var walking = true
      while (walking && chain.size < config.maxChainDepth) {
        nodeApi.unconfirmedInputByBoxId(tip.id.toString) match {
          case Success(Some(tx)) =>
            tx.outputs.find(o => o.assets.headOption.exists(_.tokenId == nft)) match {
              case Some(next) =>
                chain :+= tx
                tip = next.toInputUTXO(ctx)
              case None =>
                // The NFT left the box the chain was following, which the emission contract makes
                // impossible. Stop rather than guess.
                logger.warn(s"Mempool transaction ${tx.id} spends the emission box without recreating it")
                walking = false
            }
          case _ => walking = false
        }
      }
      if (chain.nonEmpty)
        logger.info(s"Emission box is ${chain.size} unconfirmed spend(s) ahead, tip ${tip.id}")
      EmissionTip(tip, chain)
    }
  }

  /**
   * The emission config, a data input on every emission spend. Confirmed only - data inputs are
   * resolved against the UTXO set, so an unconfirmed successor is not addressable.
   */
  def configBox(ctx: BlockchainContext): InputUTXO =
    nodeApi
      .unspentBoxesByTokenId(LFSMHelpers.EMCONFIG_NFT.toString, Paging.all(8), SortDirection.Desc,
        MempoolOptions.ConfirmedOnly)
      .getOrElse(Seq.empty[IndexedBox])
      .find(b => carriesOne(b, LFSMHelpers.EMCONFIG_NFT) && b.box.additionalRegisters.ordered.size >= 4)
      .map(_.toInputUTXO(ctx))
      .getOrElse(throw new CollateralNotFoundException(
        s"no unspent emission config box carrying ${LFSMHelpers.EMCONFIG_NFT}"))

  def readEmission(box: InputUTXO): EmissionState = EmissionState(
    currentBlock = box.parseReg[Int](0),
    lenderSet = box.parseReg[Coll[Coll[Byte]]](1).toArray.map(_.toArray).toSeq,
    head = box.parseReg[Long](2),
    tail = box.parseReg[Long](3),
    nft = box.tokens.head,
    queueToken = box.tokens(1),
    collatToken = box.tokens(2),
    lit = box.tokens.lift(3),
    value = box.value
  )

  def readConfig(box: InputUTXO): ConfigState = ConfigState(
    permitParams = box.parseReg[Coll[Long]](0).toArray.toSeq,
    enforcerHash = box.parseReg[Coll[Byte]](1).toArray,
    extensionHash = box.parseReg[Coll[Byte]](2).toArray,
    rollupHash = box.parseReg[Coll[Byte]](3).toArray
  )

  /**
   * The queue box at `position`, looked for in the unconfirmed chain first - during a join burst the
   * head is often a box that has not been confirmed yet - and then in confirmed state.
   */
  def queueBoxAt(ctx: BlockchainContext,
                 position: Long,
                 ancestors: Seq[NodeTransaction]): Option[InputUTXO] = {
    val queueTok = LFSMHelpers.QUEUE_TOKEN.toString
    val gateTree = contracts(ctx).gate.ergoTreeHex
    val spentInChain: Set[String] = ancestors.flatMap(_.inputs.map(_.boxId)).toSet

    val fromMempool = ancestors.reverse.flatMap(_.outputs)
      .filter(o => !spentInChain.contains(o.boxId) &&
        o.assets.headOption.exists(a => a.tokenId == queueTok && a.amount == 1L) &&
        o.ergoTree == gateTree)
      .find(o => queuePosition(o.registerValues).contains(position))
      .map(_.toInputUTXO(ctx))

    fromMempool.orElse {
      queueBoxes(ctx, liveOnly)
        .find(b => queuePosition(b.box.registerValues).contains(position))
        .map(_.toInputUTXO(ctx))
    }
  }

  /**
   * A proof-of-spend box that frees a slot in the active set, with the index it frees.
   *
   * The emission contract demands `creationInfo._1 < HEIGHT`, so a retirement produced by this
   * block is unusable. That is deliberate: it stops a miner spending a collateral box, activating
   * the same lender's next queue box on the retirement, and spending that too in one block.
   */
  def findProofOfSpend(ctx: BlockchainContext,
                       lenderSet: Seq[Array[Byte]],
                       blockHeight: Int,
                       exclude: Set[String] = Set.empty[String]): Option[(InputUTXO, Int)] =
    proofOfSpendBoxes(ctx, liveOnly)
      .filter(b => b.box.creationHeight < blockHeight && !exclude.contains(b.boxId))
      .flatMap { b =>
        retiringKey(b).flatMap { r =>
          lenderSet.indexWhere(_.sameElements(r)) match {
            case -1 => None
            case idx => Some((b.toInputUTXO(ctx), idx))
          }
        }
      }
      .headOption

  /**
   * Every lender key already spoken for: in the active set, sitting in the queue, on a live
   * collateral box, or named by a proof of spend that has not been consumed yet.
   *
   * The mempool is included deliberately. The active set holds distinct KEYS, so a queue box under
   * a key already in the set can only be Cleared, which forfeits its principal and permit to
   * whoever clears it. Missing an unconfirmed join of our own would cost 2.915 ERG, not a fee.
   */
  def takenLenderKeys(ctx: BlockchainContext, tip: EmissionTip): Set[String] = {
    val gate = contracts(ctx).gate.ergoTreeHex
    val keys = mutable.Set.empty[String]

    keys ++= readEmission(tip.box).lenderSet.map(hex)

    // Unconfirmed joins chained onto the emission box are in no index yet, so they come from the
    // chain we walked.
    keys ++= tip.ancestors.flatMap(_.outputs)
      .filter(o => o.ergoTree == gate &&
        o.assets.headOption.exists(_.tokenId == LFSMHelpers.QUEUE_TOKEN.toString))
      .flatMap(o => Try(lenderEntry(o.registerValues(1).getValue.asInstanceOf[SigmaProp])).toOption)
      .map(hex)

    // Paged rather than capped at queueScanLimit: our most recent joins sit at the TAIL, which is
    // exactly what a single ascending page would drop. Only the keys are kept — the queue runs to
    // thousands of boxes and holding them all is the difference between kilobytes and tens of MB.
    scanByToken(ctx, LFSMHelpers.QUEUE_TOKEN, withMempool) { page =>
      keys ++= page
        .filter(b => carriesOne(b, LFSMHelpers.QUEUE_TOKEN) && b.ergoTree == gate)
        .flatMap(lenderKeyOf).map(hex)
    }

    // One scan covers both box types the collateral token identifies: live collateral boxes are not
    // at the gate, proof-of-spend boxes are.
    scanByToken(ctx, LFSMHelpers.COLLAT_TOKEN, withMempool) { page =>
      val carrying = page.filter(carriesOne(_, LFSMHelpers.COLLAT_TOKEN))
      keys ++= carrying.filter(_.ergoTree != gate).flatMap(lenderKeyOf).map(hex)
      // Include proof of spend boxes with less than 50 confirmations, since a block re-org
      // could accidentally cause you to post to a lender-key which may no-longer be spent
      // after the re-org
      keys ++= carrying.filter(c => c.ergoTree == gate && ctx.getHeight - c.inclusionHeight <= 50).flatMap(retiringKey).map(hex)
    }

    keys.toSet
  }

  /** Every P2PK address the node's wallet controls, which is this miner's supply of lender keys. */
  def walletAddresses(ctx: BlockchainContext): Seq[Address] =
    nodeApi.walletAddresses()
      .getOrElse(throw new CollateralNotFoundException(
        "could not read the node wallet's addresses - is the wallet unlocked and the api key right?"))
      .flatMap(a => Try(Address.create(a)).toOption)

  /**
   * Up to `count` wallet addresses that no live box already uses, deriving new EIP-3 keys through
   * the node when the existing ones are all spoken for.
   *
   * Derivation goes through the node's wallet, not appkit, so the node knows the key — every one of
   * these addresses eventually receives a 3 ERG coinbase. The appkit prover holds EIP-3 index 0
   * only and cannot sign for them; `FundingSource` filters them out.
   */
  def freeLenderKeys(ctx: BlockchainContext, taken: Set[String], count: Int): Seq[Address] = {
    var mine = walletAddresses(ctx)
    var free = mine.filterNot(a => taken.contains(hex(lenderEntry(a))))
    var deriving = true

    while (deriving && free.size < count && mine.size < config.maxLenderKeys) {
      nodeApi.deriveNextKey().flatMap(d => Try(d.derivationPath -> Address.create(d.address))) match {
        case Success((path, address)) =>
          logger.info(s"Derived lender key $path -> $address")
          mine = mine :+ address
          if (!taken.contains(hex(lenderEntry(address)))) free = free :+ address
        case Failure(ex) =>
          logger.warn(s"Could not derive another lender key: ${ex.getMessage}")
          deriving = false
      }
    }

    if (free.size < count && mine.size >= config.maxLenderKeys)
      logger.info(s"Wallet is at the ${config.maxLenderKeys}-key lending budget; " +
        s"${free.size} of $count requested keys are free")

    free.take(count)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  The three spending paths
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Join - a lender locks their principal and permit into the tail of the queue.
   *
   * The lender key is fixed here in R5 and Activate only carries it through, so callers must source
   * the address from [[freeLenderKeys]]: a key already in the active set produces a box that can
   * only be Cleared, forfeiting 2.915 ERG. Always funded, since it puts the principal up.
   */
  def genJoin(ctx: BlockchainContext,
              em: InputUTXO,
              cfg: InputUTXO,
              lender: Address,
              funding: Seq[InputUTXO]): SignedTransaction = {
    val c = contracts(ctx)
    val st = readEmission(em)
    val permit = readConfig(cfg).permitAt(st.backlog)
    val litId = st.lit.map(_.id).getOrElse(LFSMHelpers.LIT_ID)

    val emIn = withEmissionVars(ctx, em, 0.toByte,
      lenderGE = Some(ErgoValue.of(lender.getPublicKeyGE)))

    val emOut = em.toUTXO
      .removeToken(st.queueToken.id, 1L)
      .withReg(3, ErgoValue.of(st.tail + 1L))

    val queueOut = UTXO(c.gate, CollateralParams.PRINCIPAL_FLOOR,
      Seq(Token(st.queueToken.id, 1L)) ++
        (if (permit > 0) Seq(Token(litId, permit)) else Seq.empty[Token]),
      Seq(
        ErgoValue.of(CollateralParams.DUST_BUDGET),                     // R4 feeValue
        ErgoValue.of(Contract.fromAddress(lender).sigmaBoolean.get),    // R5 lenderPk
        ErgoValue.of(CollateralParams.EXTENSION_FLAG),                  // R6 extensionFlag
        ErgoValue.of(st.tail)))                                         // R7 queue position

    logger.info(s"Join at position ${st.tail} (backlog ${st.backlog}): " +
      s"${CollateralParams.PRINCIPAL_FLOOR} nanoERG principal, $permit LIT permit, lender $lender")

    prover.sign(TxBuilder(ctx)
      .setInputs((emIn +: funding): _*)
      .setDataInputs(cfg)
      .setOutputs(emOut, queueOut, UTXO.feeBox(config.txFee))
      .buildTx(0, prover.p2pk))
  }

  /**
   * Activate - the head of the queue becomes a live collateral box carrying this block's emission.
   *
   * `retiring` is the proof-of-spend box freeing a slot and the index it frees: empty while the
   * active set is filling, present once it is full. Its value folds into the collateral box, which
   * `collatBox.value >= queueIn.value` allows, so the transaction balances at zero change. Empty
   * `feeOutput` and `funding` therefore gives the fee-less form for this miner's own block.
   */
  def genActivate(ctx: BlockchainContext,
                  em: InputUTXO,
                  cfg: InputUTXO,
                  queueIn: InputUTXO,
                  retiring: Option[(InputUTXO, Int)],
                  funding: Seq[InputUTXO],
                  feeOutput: Option[UTXO]): SignedTransaction = {
    val c = contracts(ctx)
    val st = readEmission(em)
    val rollupHash = readConfig(cfg).rollupHash

    val bootstrapping = retiring.isEmpty
    val lenderProp = queueIn.registers(1).getValue.asInstanceOf[SigmaProp]
    val newEntry = lenderEntry(lenderProp)
    val queuePermit = queueIn.tokens.lift(1).map(_.amount).getOrElse(0L)
    val (emitted, pub, split) = EmissionSchedule.emissionAt(st.currentBlock, st.litHeld)
    val litId = st.lit.map(_.id)
      .orElse(queueIn.tokens.lift(1).map(_.id))
      .getOrElse(LFSMHelpers.LIT_ID)

    val nextSet = retiring match {
      case Some((_, idx)) => st.lenderSet.updated(idx, newEntry)
      case None => st.lenderSet :+ newEntry
    }

    // The queue token comes back from the spent queue box. The collateral token only leaves while
    // the set is filling - once full, the proof-of-spend box returns one at the same time.
    val nextTokens =
      Seq(st.nft,
        st.queueToken.copy(amount = st.queueToken.amount + 1L),
        st.collatToken.copy(amount = if (bootstrapping) st.collatToken.amount - 1L else st.collatToken.amount)) ++
        (if (st.litHeld - emitted > 0) Seq(Token(litId, st.litHeld - emitted)) else Seq.empty[Token])

    val emOut = em.toUTXO
      .setTokens(nextTokens: _*)
      .setRegs(
        ErgoValue.of(st.currentBlock + 1),
        lenderSetValue(nextSet),
        ErgoValue.of(st.head + 1L),
        ErgoValue.of(st.tail))

    val collatOut = UTXO(c.collateral, queueIn.value + retiring.map(_._1.value).getOrElse(0L),
      Seq(Token(st.collatToken.id, 1L)) ++
        (if (emitted + queuePermit > 0) Seq(Token(litId, emitted + queuePermit)) else Seq.empty[Token]),
      Seq(
        queueIn.registers.head,                 // R4 feeValue, carried through
        queueIn.registers(1),                   // R5 lenderPk, carried through
        litRewardsValue(pub, split),            // R6 this block's split
        queueIn.registers(2),                   // R7 extensionFlag, carried through
        bytesValue(rollupHash)))                // R8 stamped from the config's R7

    val emIn = withEmissionVars(ctx, em, 1.toByte, slotIdx = retiring.map(_._2))
    val inputs = Seq(emIn, queueIn) ++ retiring.map(_._1).toSeq ++ funding

    logger.info(s"Activate at queue position ${st.head}: emits $emitted LIT " +
      s"(pool $pub, founders ${split.map(_._2).sum}), " +
      s"${if (bootstrapping) "bootstrapping" else s"rotating slot ${retiring.get._2}"}" +
      s"${if (feeOutput.isEmpty) ", fee-less" else ""}")

    prover.sign(TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setDataInputs(cfg)
      .setOutputs((Seq(emOut, collatOut) ++ feeOutput.toSeq): _*)
      .buildTx(0, prover.p2pk))
  }

  /**
   * Clear - drop a head queue box whose lender already holds a slot in the active set.
   *
   * Strict FCFS has no skip, so one duplicate key would otherwise hold the entire queue still.
   * Permissionless and nobody is obliged to do it, which is why the client drives it. The box is
   * not refunded: its principal and permit fall to whoever builds this transaction, which is both
   * the incentive to build it and why a lender must never re-use a live key.
   */
  def genClear(ctx: BlockchainContext,
               em: InputUTXO,
               cfg: InputUTXO,
               queueIn: InputUTXO,
               funding: Seq[InputUTXO],
               feeOutput: Option[UTXO]): SignedTransaction = {
    val st = readEmission(em)

    // Nothing is emitted and the set does not move; only the queue token comes back and the head
    // advances. Token count has to stay identical, which is why the LIT entry is carried as-is.
    val nextTokens =
      Seq(st.nft, st.queueToken.copy(amount = st.queueToken.amount + 1L), st.collatToken) ++ st.lit.toSeq

    val emOut = em.toUTXO
      .setTokens(nextTokens: _*)
      .setRegs(
        ErgoValue.of(st.currentBlock),
        lenderSetValue(st.lenderSet),
        ErgoValue.of(st.head + 1L),
        ErgoValue.of(st.tail))

    val emIn = withEmissionVars(ctx, em, 2.toByte)

    logger.info(s"Clear at queue position ${st.head}: its lender already holds a slot, recovering " +
      s"${queueIn.value} nanoERG and ${queueIn.tokens.lift(1).map(_.amount).getOrElse(0L)} LIT" +
      s"${if (feeOutput.isEmpty) ", fee-less" else ""}")

    prover.sign(TxBuilder(ctx)
      .setInputs((Seq(emIn, queueIn) ++ funding): _*)
      .setDataInputs(cfg)
      .setOutputs((Seq(emOut) ++ feeOutput.toSeq): _*)
      .buildTx(0, prover.p2pk))
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  Drivers
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Walk the queue from its head, chaining one emission spend onto the next. A head whose lender
   * already holds a slot is Cleared, anything else is Activated, and each transaction respends the
   * previous one's emission successor - so one pass can unstick a run of duplicates and still mint
   * a collateral box behind them.
   *
   * @param funded true pays a fee and can be broadcast; false is the fee-less form for this
   *               miner's own block, which needs no wallet input at all.
   */
  def buildQueueSpends(ctx: BlockchainContext,
                       blockHeight: Int,
                       funded: Boolean,
                       limit: Int): (EmissionTip, Seq[EmissionSpend]) = {
    val tip = emissionTip(ctx)
    val cfg = configBox(ctx)
    val funding = new FundingSource

    var em = tip.box
    var built = Vector.empty[EmissionSpend]
    val usedProofs = mutable.Set.empty[String]
    var stop = false
    var completed = false

    def fee: Option[UTXO] = if (funded) Some(UTXO.feeBox(config.txFee)) else None

    /**
     * A Clear takes the head queue box's whole principal and gives nothing back, so it funds its own
     * fee out of that and leaves the rest as change — which is the forfeit. Drawing a wallet box as
     * well added an input for nothing and held a reservation against `SubmissionHandler`, which
     * draws from the same wallet.
     *
     * The guard is the change floor rather than the fee, because a remainder below it is folded into
     * the fee output and the queue box's LIT permit would then have nowhere to go.
     */
    def clearInputs(queueIn: InputUTXO): Seq[InputUTXO] =
      if (funded && queueIn.value - config.txFee < UTXO.MIN_CHANGE) funding.takeOrNone(config.txFee * 2)
      else Seq.empty

    try {
      while (!stop && built.size < limit) {
        val st = readEmission(em)
        if (st.head >= st.tail) {
          if (built.isEmpty) logger.info(s"Collateral queue is empty at head ${st.head} - nothing to spend")
          stop = true
        } else queueBoxAt(ctx, st.head, tip.ancestors) match {
          case None =>
            if (built.isEmpty) logger.warn(s"No queue box found at head ${st.head}")
            stop = true

          case Some(queueIn) =>
            Try(lenderEntry(queueIn.registers(1).getValue.asInstanceOf[SigmaProp])).toOption match {
              case None =>
                logger.warn(s"Queue box ${queueIn.id} has no readable lender key - stopping")
                stop = true

              case Some(newEntry) if st.holds(newEntry) =>
                val sTx = genClear(ctx, em, cfg, queueIn, clearInputs(queueIn), fee)
                em = chainOn(sTx, funding, holdWalletChange = funded).emission
                built :+= EmissionSpend(sTx, EmissionSpend.Clear, funding.transferPending())

              case Some(_) =>
                val retiring =
                  if (st.bootstrapping) None
                  else findProofOfSpend(ctx, st.lenderSet, blockHeight, usedProofs.toSet)
                if (!st.bootstrapping && retiring.isEmpty) {
                  logger.info(s"Active set is full at ${st.lenderSet.size} and no proof-of-spend box " +
                    s"older than height $blockHeight frees a slot - cannot activate")
                  stop = true
                } else {
                  retiring.foreach(r => usedProofs += r._1.id.toString)
                  // The collateral box carries the queue box's whole principal straight back out, so
                  // a funded Activate has nothing of its own to pay the fee with.
                  val extra = if (funded) funding.take(config.txFee * 2) else Seq.empty[InputUTXO]
                  val sTx = genActivate(ctx, em, cfg, queueIn, retiring, extra, fee)
                  em = chainOn(sTx, funding, holdWalletChange = funded).emission
                  built :+= EmissionSpend(sTx, EmissionSpend.Activate, funding.transferPending())
                }
            }
        }
      }
      // After the loop, not inside it. Set per iteration, one bad spend part way through a run left
      // the earlier iterations' inputs reserved even though the throw means the caller never gets
      // the sequence and nothing is broadcast at all.
      completed = true
    } finally
      // Funded spends are broadcast by the caller the moment this returns, so their inputs stay
      // reserved — but only if we got there: an exception discards everything built. Nothing is
      // confirmed sent either way, so no change goes back.
      {
        if (!completed) built.flatMap(_.reservations).foreach(_.release())
        funding.finish(returnChange = false)
      }

    (tip, built)
  }

  /**
   * Put this miner's own funds into the collateral queue, and broadcast the result. Always broadcast,
   * because a join that only went into this miner's own candidate would mostly never land.
   *
   * Bounded on three configured axes: how many boxes may be live at once, how many to add per pass,
   * and the most to pay in permit for one of them.
   *
   * @return the ids of the transactions sent
   */
  def selfCollateralize(ctx: BlockchainContext): Seq[String] = {
    if (!config.autoCollateralize) return Seq.empty[String]

    val tip = emissionTip(ctx)
    val cfg = configBox(ctx)
    val cs = readConfig(cfg)
    val taken = takenLenderKeys(ctx, tip)

    val mine = walletAddresses(ctx)
    val ourLive = mine.count(a => taken.contains(hex(lenderEntry(a))))
    val room = config.maxOwnCollateral - ourLive

    logger.info(s"Self-collateralization: ${mine.size} wallet key(s), $ourLive already committed, " +
      s"budget ${config.maxOwnCollateral}, ${taken.size} key(s) taken protocol-wide")

    if (room <= 0) {
      logger.info(s"Already holding $ourLive collateral position(s) - nothing to add")
      Seq.empty[String]
    } else {
      val wanted = math.min(room, config.maxJoinsPerRun)
      val keys = freeLenderKeys(ctx, taken, wanted)
      if (keys.isEmpty) {
        logger.info("No free lender keys available - raise maxLenderKeys to dedicate more addresses")
        Seq.empty[String]
      } else {
        val funding = new FundingSource
        val litId = readEmission(tip.box).lit.map(_.id).getOrElse(LFSMHelpers.LIT_ID)
        var em = tip.box
        var sent = Vector.empty[String]
        var remaining = keys
        var stop = false

        // Stop-on-first-problem rather than best-effort: each join chains off the previous one's
        // emission successor and its change, so after a failure the rest build on state that does
        // not exist.
        try while (!stop && remaining.nonEmpty) {
          val lender = remaining.head
          remaining = remaining.tail
          val permit = cs.permitAt(readEmission(em).backlog)

          if (permit > config.maxPermitPerJoin) {
            logger.info(s"Permit has reached $permit LIT, past the ${config.maxPermitPerJoin} budget " +
              s"- stopping after ${sent.size} join(s)")
            stop = true
          } else {
            val cost = CollateralParams.PRINCIPAL_FLOOR + config.txFee * 2
            var attemptReservations = Seq.empty[WalletReservation]
            var submissionStarted = false
            Try {
              val inputs = funding.take(cost, if (permit > 0) Some(Token(litId, permit)) else None)
              val sTx = genJoin(ctx, em, cfg, lender, inputs)
              // Hold signable change before crossing the node boundary. A mempool-aware wallet
              // refresh may expose it immediately after acceptance, before this thread can chain
              // the next join, so post-send reservation is too late.
              val chained = chainOn(sTx, funding)
              attemptReservations = funding.pendingReservations
              WalletReservation.beginAll(attemptReservations)
              submissionStarted = true
              val txId = ctx.sendTransaction(sTx).replace("\"", "")
              attemptReservations.foreach(_.commit())
              funding.clearPending()
              chained.walletChange.foreach(funding.markAccepted)
              em = chained.emission
              txId
            } match {
              case Success(txId) =>
                logger.info(s"Sent join $txId for lender $lender")
                sent :+= txId
              case Failure(ex: NotEnoughInputsException) =>
                logger.warn(s"Stopping self-collateralization after ${sent.size} join(s): ${ex.getMessage}")
                stop = true
              case Failure(ex) =>
                if (submissionStarted) attemptReservations.foreach(_.uncertain())
                logger.error(s"Join for lender $lender failed, stopping: ${ex.getMessage}", ex)
                stop = true
            }
          }
        } finally
          // Every chained box here came from a join the node accepted, so its change is real.
          funding.finish(returnChange = sent.nonEmpty)
        sent
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  Funding
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * One run's view of the wallet.
   *
   * Everything drawn from the shared [[WalletSource]] is reserved there, so nothing else can select
   * the same box. Change produced inside the run is spent first, since the wallet cannot know about
   * it until it confirms.
   */
  class FundingSource {
    private case class ChainedInput(box: InputUTXO, reservation: WalletReservation)

    private var chained: Vector[ChainedInput] = Vector.empty
    private var reserved: Vector[WalletReservation] = Vector.empty
    private var pending: Vector[WalletReservation] = Vector.empty
    private var used = Set.empty[String]
    private var accepted = Set.empty[String]

    /**
     * Chain change back in and reserve it at the actor immediately. The handle follows the box into
     * whichever later spend consumes it; until then, even a refresh which discovers it cannot hand
     * it to DEX or rollup selection.
     */
    def push(box: InputUTXO): Unit = {
      val reservation = walletSource.reserveKnown(Seq(box))
      reserved :+= reservation
      chained = ChainedInput(box, reservation) +: chained
    }

    /** Record that a provisional chained output's parent was accepted by the node. */
    def markAccepted(box: InputUTXO): Unit =
      if (chained.exists(_.box.id == box.id)) accepted += box.id.toString

    def take(value: Long, token: Option[Token] = None): Seq[InputUTXO] = {
      val availableChain = chained.filterNot(entry => used.contains(entry.box.id.toString))
      val fromChain = drawFrom(availableChain.map(_.box), value, token)
      val fromChainIds = fromChain.map(_.id.toString).toSet
      pending ++= availableChain
        .filter(entry => fromChainIds.contains(entry.box.id.toString))
        .map(_.reservation)
      val ergShort = math.max(0L, value - fromChain.map(_.value).sum)
      // The REMAINDER, not the original amount: chained change can carry LIT, and asking the wallet
      // for the full permit again would over-collect or fail when it holds only the difference.
      val tokenShort = token.flatMap { t =>
        val have = fromChain.flatMap(_.tokens).filter(_.id == t.id).map(_.amount).sum
        if (have >= t.amount) None else Some(Token(t.id, t.amount - have))
      }

      val fromWallet =
        if (ergShort == 0 && tokenShort.isEmpty) Seq.empty[InputUTXO]
        else {
          val reservation = walletSource.reserve(ergShort, tokenShort.toSeq)
          reserved :+= reservation
          pending :+= reservation
          reservation.inputs
        }

      val picked = fromChain ++ fromWallet
      used ++= picked.map(_.id.toString)
      if (picked.isEmpty && value > 0)
        throw new NotEnoughInputsException(s"no wallet inputs available for $value nanoERG")
      picked
    }

    /** For transactions already funded by their own inputs, which only want the change. */
    def takeOrNone(value: Long): Seq[InputUTXO] =
      Try(take(value)).getOrElse(Seq.empty[InputUTXO])

    /**
     * End of run. The two halves are decided separately and neither implies the other.
     *
     * `keepReservations` must hold whenever the built transactions will be broadcast, even if the
     * broadcast has not happened yet — releasing first would let something else pick the same box.
     * `returnChange` is stricter: it may only be set once the parent transactions are known to have
     * reached the node, since handing back change from a transaction that never lands would seed
     * the wallet with a box the chain has never seen.
     */
    def pendingReservations: Seq[WalletReservation] = pending

    def clearPending(): Unit = pending = Vector.empty

    /** Hand ownership of the latest build's reservations to its EmissionSpend. */
    def transferPending(): Seq[WalletReservation] = {
      val transferred = pending
      val ids = transferred.map(_.id).toSet
      reserved = reserved.filterNot(r => ids.contains(r.id))
      pending = Vector.empty
      transferred
    }

    def finish(returnChange: Boolean): Unit = {
      // Open local reservations are abandoned. Committed/uncertain handles ignore release.
      reserved.foreach(_.release())
      if (returnChange) {
        val leftover = chained
          .filter(entry => accepted.contains(entry.box.id.toString) && !used.contains(entry.box.id.toString))
          .map(_.box)
        if (leftover.nonEmpty) walletSource.giveBack(leftover)
      }
    }

    /**
     * Token-carrying boxes first, then largest first, so a permit needs as few inputs as possible.
     * A box is only taken if it moves one of the two requirements — otherwise an unmet token demand
     * would drag in every ERG-only box on the way past.
     */
    private def drawFrom(from: Seq[InputUTXO], value: Long, token: Option[Token]): Seq[InputUTXO] = {
      val needed = token.map(_.amount).getOrElse(0L)
      val tokenId = token.map(_.id.toString)
      val ordered = from.sortBy { b =>
        val carries = tokenId.exists(id => b.tokens.exists(_.id.toString == id))
        (if (carries) 0 else 1, -b.value)
      }
      var erg = 0L
      var tokens = 0L
      var picked = Vector.empty[InputUTXO]
      val it = ordered.iterator
      while (it.hasNext && (erg < value || tokens < needed)) {
        val box = it.next()
        val boxTokens = tokenId.map(id => box.tokens.find(_.id.toString == id).map(_.amount).getOrElse(0L))
        if (erg < value || boxTokens.exists(_ > 0)) {
          picked :+= box
          erg += box.value
          tokens += boxTokens.getOrElse(0L)
        }
      }
      picked
    }
  }

  /** Respend a just-built transaction's emission successor, and feed its change back to funding. */
  private case class ChainResult(emission: InputUTXO, walletChange: Option[InputUTXO])

  private def chainOn(sTx: SignedTransaction,
                      funding: FundingSource,
                      holdWalletChange: Boolean = true): ChainResult = {
    val outputs = sTx.getOutputsToSpend.asScala.toSeq.map(InputUTXO(_))
    val change = outputs.drop(1).find(_.contract.ergoTreeHex == walletTree)
    if (holdWalletChange) change.foreach(funding.push)
    ChainResult(outputs.head, change)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  Box lookups and register shapes
  // ══════════════════════════════════════════════════════════════════════════

  /** The head of the queue is the OLDEST unspent queue box, so one ascending page always holds it. */
  private def queueBoxes(ctx: BlockchainContext, mempool: MempoolOptions): Seq[IndexedBox] = {
    val gateTree = contracts(ctx).gate.ergoTreeHex
    nodeApi
      .unspentBoxesByTokenId(LFSMHelpers.QUEUE_TOKEN.toString, Paging.all(config.queueScanLimit),
        SortDirection.Asc, mempool)
      .getOrElse(Seq.empty[IndexedBox])
      .filter(b => carriesOne(b, LFSMHelpers.QUEUE_TOKEN) && b.ergoTree == gateTree)
  }

  private def proofOfSpendBoxes(ctx: BlockchainContext, mempool: MempoolOptions): Seq[IndexedBox] = {
    val gateTree = contracts(ctx).gate.ergoTreeHex
    nodeApi
      .unspentBoxesByTokenId(LFSMHelpers.COLLAT_TOKEN.toString, Paging.all(config.queueScanLimit),
        SortDirection.Asc, mempool)
      .getOrElse(Seq.empty[IndexedBox])
      .filter(b => carriesOne(b, LFSMHelpers.COLLAT_TOKEN) &&
        b.ergoTree == gateTree &&
        b.box.additionalRegisters.ordered.size == 1)
  }

  /**
   * Every unspent box carrying `tokenId`, one page at a time to exhaustion.
   *
   * Only for the duplicate-key check, where a partial answer is worse than none. Pages are handed to
   * `consume` and dropped rather than accumulated, because the queue can run to thousands of boxes
   * and the callers only want a few bytes out of each. The hot paths stay capped.
   */
  private def scanByToken(ctx: BlockchainContext, tokenId: ErgoId, mempool: MempoolOptions)
                         (consume: Seq[IndexedBox] => Unit): Unit = {
    var seen = 0
    var paging = Paging(0, EmissionTransactions.ScanPageSize)
    var exhausted = false
    while (!exhausted && seen < EmissionTransactions.MaxScannedBoxes) {
      nodeApi.unspentBoxesByTokenId(tokenId.toString, paging, SortDirection.Asc, mempool) match {
        case Success(page) =>
          consume(page)
          seen += page.size
          exhausted = page.size < paging.limit
          paging = paging.next
        case Failure(ex) =>
          logger.warn(s"Scan of $tokenId stopped after $seen box(es): ${ex.getMessage}")
          exhausted = true
      }
    }
    if (seen >= EmissionTransactions.MaxScannedBoxes)
      logger.warn(s"Scan of $tokenId hit the ${EmissionTransactions.MaxScannedBoxes}-box ceiling - " +
        "the duplicate-key check may be incomplete")
  }

  /** R5 of a queue or collateral box, as the hashed prop bytes the active set is keyed by. */
  private def lenderKeyOf(b: IndexedBox): Option[Array[Byte]] =
    Try(lenderEntry(b.box.registerValues(1).getValue.asInstanceOf[SigmaProp])).toOption

  /** R4 of a proof-of-spend box: the identity it retires. */
  private def retiringKey(b: IndexedBox): Option[Array[Byte]] =
    Try(b.box.registerValues.head.getValue.asInstanceOf[Coll[Byte]].toArray).toOption

  private def queuePosition(regs: Seq[ErgoValue[_]]): Option[Long] =
    if (regs.size >= 4) Try(regs(3).getValue.asInstanceOf[Long]).toOption else None

  private def carriesOne(b: IndexedBox, tokenId: ErgoId): Boolean =
    b.assets.headOption.exists(a => a.tokenId == tokenId.toString && a.amount == 1L)

  /**
   * The context extension every emission spend carries, attached in the ONLY order that survives
   * the trip to the node.
   *
   * The extension is part of `messageToSign` and its serializer walks the map in iteration order.
   * Signing uses a Scala `Map` (insertion order at four entries or fewer); sending copies into a
   * `java.util.HashMap` keyed by the decimal id, and the JSON is written in that hash order. So
   * attaching in written order signs one byte string and asks the node to verify another. Attaching
   * in the HashMap's own order makes both agree. Pinned by `ContextExtensionOrderSpec`.
   */
  private def withEmissionVars(ctx: BlockchainContext,
                               em: InputUTXO,
                               op: Byte,
                               slotIdx: Option[Int] = None,
                               lenderGE: Option[ErgoValue[_]] = None): InputUTXO = {
    val c = contracts(ctx)
    val vars: Seq[(Byte, ErgoValue[_])] =
      Seq[(Byte, ErgoValue[_])](
        0.toByte -> ErgoValue.of(op),
        64.toByte -> bytesValue(c.emission.valueBytes),
        65.toByte -> bytesValue(c.enforcer.valueBytes)) ++
        slotIdx.map(i => 1.toByte -> ErgoValue.of(i)).toSeq ++
        lenderGE.map(ge => 2.toByte -> ge).toSeq

    val probe = new java.util.HashMap[String, String](vars.size)
    vars.foreach { case (id, _) => probe.put(id.toString, "") }
    val wireOrder = probe.keySet().asScala.toSeq.map(_.toByte)

    wireOrder.foldLeft(em)((acc, id) => acc.withCtxVar(id, vars.find(_._1 == id).get._2))
  }

  private def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  private def lenderSetValue(entries: Seq[Array[Byte]]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(entries.map(e => Colls.fromArray(e)).toArray),
      ErgoType.collType(scalaByteType))

  private def litRewardsValue(pub: Long, founderSplit: Seq[(Array[Byte], Long)]): ErgoValue[_] = {
    val entries: Array[(Coll[Byte], Long)] =
      founderSplit.map { case (key, amount) => (Colls.fromArray(key), amount) }.toArray
    ErgoValue.pairOf(
      ErgoValue.of(pub),
      ErgoValue.of(Colls.fromArray(entries),
        ErgoType.pairType(ErgoType.collType(scalaByteType), scalaLongType)))
  }
}

object EmissionTransactions {

  /** Page size for the exhaustive scans behind the duplicate-key check. */
  private[transactions] final val ScanPageSize = 500

  /** Hard ceiling on those scans, so a pathological queue cannot stall a pass indefinitely. */
  private[transactions] final val MaxScannedBoxes = 10000
}
