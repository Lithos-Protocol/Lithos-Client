package contracts.specs.emission

import contracts.specs.harness.ContractSpecBase
import lfsm.LFSMHelpers
import lfsm.contracts.CollateralContract
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import sigma.{Coll, Colls}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

/** The emission-side contract set, compiled once — `RollupSpecBase` explains why this is cached. */
case class EmissionContracts(gate: Contract,
                             guard: Contract,
                             emission: Contract,
                             enforcer: Contract,
                             config: Contract,
                             collateral: Contract)

object EmissionSpecBase {

  /**
   * The collateral box's script is not under test here, so a `sigmaProp(true)` stand-in takes its
   * place: the emission contract only ever checks `blake2b256(collatBox.propositionBytes)` against
   * CONST_COLLATERAL_HASH, so any script works as long as the constant is built from it.
   */
  val collateralStandIn: Contract = Contract.SIGMA_TRUE

  private var cache: Option[EmissionContracts] = None

  def compiled(ctx: BlockchainContext, owner: Contract): EmissionContracts = synchronized {
    cache.getOrElse {
      val gate = CollateralContract.mkEmissionGateContract(ctx)
      val guard = CollateralContract.mkEmissionsGuardContract(
        ctx, LFSMHelpers.EMISSION_NFT, LFSMHelpers.EMCONFIG_NFT,
        collateralStandIn.hashedPropBytes, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val emission = CollateralContract.mkEmissionsContract(
        ctx, collateralStandIn.hashedPropBytes, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val enforcer = CollateralContract.mkCollateralEnforcerContract(ctx, LFSMHelpers.LIT_ID)
      val config = CollateralContract.mkEmConfigContract(ctx, owner)
      val collateral = CollateralContract.mkMainnetCollatContract(
        ctx, LFSMHelpers.EMCONFIG_NFT, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val all = EmissionContracts(gate, guard, emission, enforcer, config, collateral)
      cache = Some(all)
      all
    }
  }
}

/**
 * Shared harness for the emission subsystem: `Emission_Guard`, `LIT_Emissions`, `Emission_Gate`,
 * `Emission_Config` and `Collateral_Enforcer`.
 *
 * The emission box is scripted with `Emission_Guard` (analysis §27.1), which authenticates and then
 * runs the emission logic from context var 64 and the enforcer from context var 65. Every helper here
 * builds that production shape. Which script actually sits in var 65 is left to the caller: the
 * enforcer hash is read from emission config R5, so a spec that wants to isolate the emission logic
 * puts `sigmaProp(true)` in both places, while a spec testing the enforcer supplies the real one.
 *
 * Transaction shapes, all with the emission config as dataInputs(0):
 *
 *   Join     INPUTS  (0) emission (1) funding      OUTPUTS (0) emission (1) queue box
 *   Activate INPUTS  (0) emission (1) queue box [(2) proof-of-spend]
 *                                                 OUTPUTS (0) emission (1) collateral box
 *   Clear    INPUTS  (0) emission (1) queue box    OUTPUTS (0) emission
 */
trait EmissionSpecBase extends ContractSpecBase {

  // ─── identities ───────────────────────────────────────────────────────────

  protected val lenderSecret: BigInteger = BigInteger.valueOf(4004L)
  protected val otherLenderSecret: BigInteger = BigInteger.valueOf(5005L)
  protected val configOwnerSecret: BigInteger = BigInteger.valueOf(6006L)

  protected def lender(ctx: BlockchainContext): ErgoProver = proverWith(ctx, lenderSecret)

  protected def otherLender(ctx: BlockchainContext): ErgoProver = proverWith(ctx, otherLenderSecret)

  protected def configOwner(ctx: BlockchainContext): ErgoProver = proverWith(ctx, configOwnerSecret)

  /** The lender's entry in the active set — `blake2b256(lenderPk.propBytes)` as the contract reads it. */
  protected def setEntry(prover: ErgoProver): Array[Byte] = contractOf(prover).hashedPropBytes

  // ─── contracts ────────────────────────────────────────────────────────────

  protected def emissionContracts(ctx: BlockchainContext): EmissionContracts =
    EmissionSpecBase.compiled(ctx, contractOf(configOwner(ctx)))

  protected def gateContract(ctx: BlockchainContext): Contract = emissionContracts(ctx).gate

  protected def guardContract(ctx: BlockchainContext): Contract = emissionContracts(ctx).guard

  protected def emissionScript(ctx: BlockchainContext): Contract = emissionContracts(ctx).emission

  protected def enforcerScript(ctx: BlockchainContext): Contract = emissionContracts(ctx).enforcer

  protected def configContract(ctx: BlockchainContext): Contract = emissionContracts(ctx).config

  /**
   * The real `Collateral_Mainnet`. Note that `CONST_COLLATERAL_HASH` in the emission builder above is
   * built from [[collateralStandIn]], not from this — the emission specs isolate themselves from the
   * collateral contract's own conditions. [[CollateralSpec]] closes that gap with a handoff property.
   */
  protected def collateralContract(ctx: BlockchainContext): Contract = emissionContracts(ctx).collateral

  protected def collateralStandIn: Contract = EmissionSpecBase.collateralStandIn

  // ─── the emission schedule, mirrored from LIT_Emissions.ergo ──────────────

  protected val LIT: Long = 1000000000L
  protected val EPOCH_LENGTH: Int = 125000
  protected val INIT_PUB_RATE: Long = 500L * LIT
  protected val INIT_PRIV_RATE: Long = 140L * LIT
  protected val PRIV_LENGTH: Int = 8
  protected val PUB_DECAY: Long = 50L * LIT
  protected val P1_DECAY_LENGTH: Int = 2
  protected val P2_RATE_1: Long = 150L * LIT
  protected val P2_RATE_2: Long = 100L * LIT
  protected val P3_RATE: Long = 50L * LIT
  protected val P2_START: Int = 14
  protected val P3_START: Int = 16
  protected val MAX_EPOCHS: Int = 22
  protected val FINAL_EPOCH_LEN: Int = 75000
  protected val FINAL_BLOCK: Int = MAX_EPOCHS * EPOCH_LENGTH + FINAL_EPOCH_LEN // 2825000
  protected val F1_RATE: Long = 100L * LIT
  protected val F2_RATE: Long = 25L * LIT
  protected val F3_RATE: Long = 15L * LIT
  protected val MAX_ACTIVE: Int = 100

  /** The founders' fixed split, paid in full or not at all. */
  protected def founders: Seq[(Array[Byte], Long)] = Seq(
    LFSMHelpers.FOUNDER_1.hashedPropBytes -> F1_RATE,
    LFSMHelpers.FOUNDER_2.hashedPropBytes -> F2_RATE,
    LFSMHelpers.FOUNDER_3.hashedPropBytes -> F3_RATE
  )

  protected def privRateAt(currentBlock: Int): Long =
    if (currentBlock / EPOCH_LENGTH < PRIV_LENGTH) INIT_PRIV_RATE else 0L

  protected def pubRateAt(currentBlock: Int): Long = {
    val epoch = currentBlock / EPOCH_LENGTH
    if (currentBlock >= FINAL_BLOCK) 0L
    else if (epoch < P2_START) INIT_PUB_RATE - ((epoch / P1_DECAY_LENGTH) * PUB_DECAY)
    else if (epoch < P3_START) { if (epoch == P2_START) P2_RATE_1 else P2_RATE_2 }
    else P3_RATE
  }

  /**
   * What an Activate at `currentBlock` must actually place on the collateral box, given the LIT still
   * held by the emission box: `(emittedTotal, emittedPub, foundersMap)`. Mirrors the contract's
   * short-supply handling — the total is capped by the remaining supply, and the founders are paid in
   * full or not at all.
   */
  protected def emissionAt(currentBlock: Int, litHeld: Long): (Long, Long, Seq[(Array[Byte], Long)]) = {
    val priv = privRateAt(currentBlock)
    val total = math.min(litHeld, pubRateAt(currentBlock) + priv)
    val emittedPriv = if (total >= priv) priv else 0L
    (total, total - emittedPriv, if (emittedPriv > 0) founders else Seq.empty[(Array[Byte], Long)])
  }

  // ─── register values ──────────────────────────────────────────────────────

  /** R5 of the emission box: `Coll[Coll[Byte]]` of the active lender keys. */
  protected def lenderSetValue(entries: Seq[Array[Byte]]): ErgoValue[_] =
    ErgoValue.of(
      Colls.fromArray(entries.map(e => Colls.fromArray(e)).toArray),
      ErgoType.collType(scalaByteType))

  /** R6 of the collateral box: `(Long, Coll[(Coll[Byte], Long)])` — pool share, then founder split. */
  protected def litRewardsValue(pub: Long, founderSplit: Seq[(Array[Byte], Long)]): ErgoValue[_] = {
    val entries: Array[(Coll[Byte], Long)] =
      founderSplit.map { case (key, amount) => (Colls.fromArray(key), amount) }.toArray
    ErgoValue.pairOf(
      ErgoValue.of(pub),
      ErgoValue.of(Colls.fromArray(entries),
        ErgoType.pairType(ErgoType.collType(scalaByteType), scalaLongType)))
  }

  protected def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  // ─── the emission config data input ───────────────────────────────────────

  protected def permitParams: Array[Long] = LFSMHelpers.PERMIT_PARAMS

  /**
   * R4 permit params, R5 enforcer script hash, R6 extension script hash, R7 rollup holding hash.
   *
   * R5 and R6 hold hashed VALUE bytes while R7 holds hashed PROP bytes — nothing in any contract
   * distinguishes them, so getting it backwards halts emission or mints dead slots (analysis §27.2).
   */
  protected def configBox(ctx: BlockchainContext,
                          enforcer: Contract,
                          rollupHash: Array[Byte] = Contract.SIGMA_TRUE.hashedPropBytes,
                          params: Array[Long] = permitParams,
                          extensionHash: Array[Byte] = Contract.SIGMA_TRUE.hashedValueBytes,
                          nft: ErgoId = LFSMHelpers.EMCONFIG_NFT,
                          value: Long = Parameters.MinFee): UTXO =
    UTXO(configContract(ctx), value, Seq(Token(nft, 1L)), Seq(
      ErgoValue.of(Colls.fromArray(params), scalaLongType),
      bytesValue(enforcer.hashedValueBytes),
      bytesValue(extensionHash),
      bytesValue(rollupHash)
    ))

  protected def configInput(ctx: BlockchainContext, box: UTXO): InputUTXO =
    inputAt(box, ctx, 7)

  // ─── the emission box ─────────────────────────────────────────────────────

  /** Genesis funding: the emission NFT, Long.MaxValue of each proposition token, and the LIT supply. */
  protected val queueSupply: Long = LFSMHelpers.PROP_TOKEN_AMNT
  protected val collatSupply: Long = LFSMHelpers.PROP_TOKEN_AMNT
  protected val litSupply: Long = 825000000L * LIT

  /**
   * A token id belonging to nothing in the protocol, for "some other token turns up" cases.
   *
   * It cannot come from `LFSMHelpers`: the placeholder ids there collide with each other —
   * `COLLAT_TOKEN == MD_TOKEN_MAINNET`, `QUEUE_TOKEN == FP_TOKEN_MAINNET`, `EMCONFIG_NFT ==
   * MD_GENESIS_ID` — so reaching for one of those silently produces a second proposition token
   * (analysis §27.4, still open).
   */
  protected val unrelatedTokenId: ErgoId =
    ErgoId.create("1111111111111111111111111111111111111111111111111111111111111111")

  /** 0.01 ERG, ~7.5x the per-byte minimum of a full 3,700-byte box (analysis §27.3). */
  protected val emissionValue: Long = Parameters.OneErg / 100

  protected def emissionUTXO(ctx: BlockchainContext,
                             currentBlock: Int = 0,
                             lenderSet: Seq[Array[Byte]] = Seq.empty[Array[Byte]],
                             head: Long = 0L,
                             tail: Long = 0L,
                             queueTokens: Long = queueSupply,
                             collatTokens: Long = collatSupply,
                             lit: Long = litSupply,
                             nft: ErgoId = LFSMHelpers.EMISSION_NFT,
                             contract: Contract = null,
                             value: Long = 0L): UTXO = {
    val tokens =
      Seq(Token(nft, 1L), Token(LFSMHelpers.QUEUE_TOKEN, queueTokens), Token(LFSMHelpers.COLLAT_TOKEN, collatTokens)) ++
        (if (lit > 0) Seq(Token(LFSMHelpers.LIT_ID, lit)) else Seq.empty[Token])
    UTXO(
      if (contract == null) guardContract(ctx) else contract,
      if (value == 0L) emissionValue else value,
      tokens,
      Seq(ErgoValue.of(currentBlock), lenderSetValue(lenderSet), ErgoValue.of(head), ErgoValue.of(tail))
    )
  }

  /**
   * Turns the emission box into INPUTS(0) with the context vars every spend carries: the op byte, the
   * lender's group element, and the two scripts the guard executes.
   */
  protected def emissionInput(ctx: BlockchainContext,
                              box: UTXO,
                              op: Byte,
                              lenderGE: Option[ErgoValue[_]] = None,
                              emScript: Contract = null,
                              enfScript: Contract = Contract.SIGMA_TRUE,
                              slotIdx: Option[Int] = None): InputUTXO = {
    // Attached in ascending id order on purpose. Offline signing does not care, but on a live node the
    // extension is part of `messageToSign` and appkit serializes it in map iteration order — a
    // different attachment order signs one byte string and asks the node to verify another. See
    // [[ContextExtensionOrderSpec]]; this keeps the harness modelling the shape that actually works.
    val vars = Seq(
      ContextVar.of(0.toByte, ErgoValue.of(op))) ++
      slotIdx.map(i => ContextVar.of(1.toByte, ErgoValue.of(i))).toSeq ++
      lenderGE.map(ge => ContextVar.of(2.toByte, ge)).toSeq ++
      Seq(
        ContextVar.of(64.toByte, bytesValue(if (emScript == null) emissionScript(ctx).valueBytes else emScript.valueBytes)),
        ContextVar.of(65.toByte, bytesValue(enfScript.valueBytes)))
    inputAt(box, ctx, 0).setCtxVars(vars: _*)
  }

  /** The group element the enforcer pairs against the queue box's R5. */
  protected def lenderGEValue(ctx: BlockchainContext, prover: ErgoProver): ErgoValue[_] =
    ErgoValue.of(prover.getAddress.getPublicKeyGE)

  // ─── queue, proof-of-spend and collateral boxes ───────────────────────────

  /** Enforcer constants, inline in `Collateral_Enforcer.ergo` for audit legibility (analysis §26.0). */
  protected val BLOCK_REWARD: Long = 3000000000L
  protected val FEE_CAP: Long = 90000000L
  protected val DUST_BUDGET: Long = 5000000L
  protected val principalFloor: Long = BLOCK_REWARD - FEE_CAP + DUST_BUDGET

  /** The permit the thermostat demands at a given backlog (analysis §26.1). */
  protected def permitAt(backlog: Long, params: Array[Long] = permitParams): Long = {
    val scaled = BigInt(params(0)) + (BigInt(params(2)) * BigInt(backlog))
    if (scaled >= BigInt(params(1))) params(1) else scaled.toLong
  }

  /**
   * R4 feeValue, R5 lenderPk, R6 extensionFlag, R7 queue position. Token 0 is the queue token, token 1
   * the LIT permit — omitted entirely when the permit is zero, which is what the contracts' `getOrElse`
   * defaults are written for.
   */
  protected def queueUTXO(ctx: BlockchainContext,
                          lenderProver: ErgoProver,
                          position: Long,
                          permit: Long,
                          feeValue: Long = DUST_BUDGET,
                          extensionFlag: Byte = 0x00,
                          value: Long = 0L,
                          queueTokenId: ErgoId = LFSMHelpers.QUEUE_TOKEN,
                          permitTokenId: ErgoId = LFSMHelpers.LIT_ID,
                          contract: Contract = null): UTXO = {
    val tokens = Seq(Token(queueTokenId, 1L)) ++
      (if (permit > 0) Seq(Token(permitTokenId, permit)) else Seq.empty[Token])
    UTXO(
      if (contract == null) gateContract(ctx) else contract,
      if (value == 0L) principalFloor else value,
      tokens,
      Seq(
        ErgoValue.of(feeValue),
        ErgoValue.of(contractOf(lenderProver).sigmaBoolean.get),
        ErgoValue.of(extensionFlag),
        ErgoValue.of(position)
      ))
  }

  /**
   * Retires a lender from the active set. Carries only the collateral token and the identity being
   * retired, and its creation height must be strictly below the current one so that a retirement
   * cannot be used in the block that produced it.
   */
  protected def proofOfSpendUTXO(ctx: BlockchainContext,
                                 retiring: Array[Byte],
                                 createdAt: Int = 0,
                                 collatTokenId: ErgoId = LFSMHelpers.COLLAT_TOKEN,
                                 contract: Contract = null): UTXO =
    UTXO(
      if (contract == null) gateContract(ctx) else contract,
      Parameters.MinFee,
      Seq(Token(collatTokenId, 1L)),
      Seq(bytesValue(retiring))
    ).setCreationHeight(if (createdAt == 0) ctx.getHeight - 10 else createdAt)

  /**
   * The collateral box an Activate must produce. Principal and the lender's own terms carry through
   * from the queue box; R6 records this block's split and R8 the rollup contract it may pay into.
   */
  protected def collateralUTXO(ctx: BlockchainContext,
                               lenderProver: ErgoProver,
                               lit: Long,
                               pub: Long,
                               founderSplit: Seq[(Array[Byte], Long)],
                               rollupHash: Array[Byte] = Contract.SIGMA_TRUE.hashedPropBytes,
                               feeValue: Long = DUST_BUDGET,
                               extensionFlag: Byte = 0x00,
                               value: Long = 0L,
                               collatTokenId: ErgoId = LFSMHelpers.COLLAT_TOKEN,
                               litTokenId: ErgoId = LFSMHelpers.LIT_ID,
                               contract: Contract = null): UTXO = {
    val tokens = Seq(Token(collatTokenId, 1L)) ++
      (if (lit > 0) Seq(Token(litTokenId, lit)) else Seq.empty[Token])
    UTXO(
      if (contract == null) collateralStandIn else contract,
      if (value == 0L) principalFloor else value,
      tokens,
      Seq(
        ErgoValue.of(feeValue),
        ErgoValue.of(contractOf(lenderProver).sigmaBoolean.get),
        litRewardsValue(pub, founderSplit),
        ErgoValue.of(extensionFlag),
        bytesValue(rollupHash)
      ))
  }

  /** Funding that covers a queue box's principal and its permit without carrying protocol tokens. */
  protected def lenderFunding(ctx: BlockchainContext,
                              prover: ErgoProver,
                              permit: Long,
                              value: Long = 10L * Parameters.OneErg): InputUTXO = {
    val tokens = if (permit > 0) Seq(Token(LFSMHelpers.LIT_ID, permit)) else Seq.empty[Token]
    inputAt(UTXO(contractOf(prover), value, tokens), ctx, 1)
  }

  // ─── known-good scenarios ─────────────────────────────────────────────────
  //
  // Each builds one transaction that every contract in the stack accepts. Negatives take it and
  // perturb exactly one field, so a rejection can only come from the condition under test.

  protected case class Join(ctx: BlockchainContext,
                            emissionIn: InputUTXO,
                            funding: InputUTXO,
                            config: InputUTXO,
                            emissionOut: UTXO,
                            queueOut: UTXO,
                            prover: ErgoProver,
                            lenderProver: ErgoProver,
                            permit: Long)

  protected def joinScenario(ctx: BlockchainContext,
                             head: Long = 0L,
                             tail: Long = 0L,
                             currentBlock: Int = 0,
                             lenderSet: Seq[Array[Byte]] = Seq.empty[Array[Byte]],
                             enfScript: Contract = Contract.SIGMA_TRUE,
                             emScript: Contract = null,
                             params: Array[Long] = permitParams,
                             propSupply: Long = queueSupply): Join = {
    val lenderProver = lender(ctx)
    val permit = permitAt(tail - head, params)

    val box = emissionUTXO(ctx, currentBlock = currentBlock, lenderSet = lenderSet, head = head, tail = tail,
      queueTokens = propSupply, collatTokens = propSupply)
    val emissionIn = emissionInput(ctx, box, 0.toByte,
      lenderGE = Some(lenderGEValue(ctx, lenderProver)), emScript = emScript, enfScript = enfScript)

    val emissionOut = emissionUTXO(ctx, currentBlock = currentBlock, lenderSet = lenderSet,
      head = head, tail = tail + 1L, queueTokens = propSupply - 1L, collatTokens = propSupply)
    val queueOut = queueUTXO(ctx, lenderProver, position = tail, permit = permit)

    Join(ctx, emissionIn, lenderFunding(ctx, lenderProver, permit),
      configInput(ctx, configBox(ctx, enfScript, params = params)),
      emissionOut, queueOut, lenderProver, lenderProver, permit)
  }

  protected def joinTx(j: Join)(emissionOut: UTXO = j.emissionOut,
                                queueOut: UTXO = j.queueOut,
                                inputs: Seq[InputUTXO] = Seq(j.emissionIn, j.funding),
                                dataInputs: Seq[InputUTXO] = Seq(j.config),
                                burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(j.ctx, inputs, Seq(emissionOut, queueOut), j.prover.getAddress, dataInputs, burn)

  protected case class Activate(ctx: BlockchainContext,
                                emissionIn: InputUTXO,
                                queueIn: InputUTXO,
                                proofOfSpend: Option[InputUTXO],
                                funding: InputUTXO,
                                config: InputUTXO,
                                emissionOut: UTXO,
                                collatOut: UTXO,
                                prover: ErgoProver,
                                lenderProver: ErgoProver,
                                permit: Long,
                                emitted: Long)

  /**
   * `retiring` non-empty switches this off the bootstrap path: the set is filled to CONST_MAX_ACTIVE
   * and a proof-of-spend box frees the slot named by `slotIdx`.
   */
  protected def activateScenario(ctx: BlockchainContext,
                                 head: Long = 0L,
                                 tail: Long = 1L,
                                 currentBlock: Int = 0,
                                 lenderSet: Seq[Array[Byte]] = Seq.empty[Array[Byte]],
                                 lit: Long = -1L,
                                 permit: Long = -1L,
                                 retiring: Option[(Array[Byte], Int)] = None,
                                 enfScript: Contract = Contract.SIGMA_TRUE,
                                 emScript: Contract = null,
                                 rollupHash: Array[Byte] = Contract.SIGMA_TRUE.hashedPropBytes,
                                 propSupply: Long = queueSupply): Activate = {
    val lenderProver = lender(ctx)
    val held = if (lit < 0L) litSupply else lit
    val thePermit = if (permit < 0L) permitAt(tail - head) else permit
    val (emitted, pub, split) = emissionAt(currentBlock, held)

    val bootstrapping = retiring.isEmpty
    val collatHeld = if (bootstrapping) propSupply else propSupply - lenderSet.size

    val box = emissionUTXO(ctx, currentBlock = currentBlock, lenderSet = lenderSet, head = head, tail = tail,
      queueTokens = propSupply - 1L, collatTokens = collatHeld, lit = held)
    val emissionIn = emissionInput(ctx, box, 1.toByte, emScript = emScript, enfScript = enfScript,
      slotIdx = retiring.map(_._2))

    val nextSet =
      retiring match {
        case Some((_, idx)) => lenderSet.updated(idx, setEntry(lenderProver))
        case None => lenderSet :+ setEntry(lenderProver)
      }

    val emissionOut = emissionUTXO(ctx, currentBlock = currentBlock + 1, lenderSet = nextSet,
      head = head + 1L, tail = tail,
      queueTokens = propSupply,
      collatTokens = if (bootstrapping) collatHeld - 1L else collatHeld,
      lit = held - emitted)

    val queueIn = inputAt(queueUTXO(ctx, lenderProver, position = head, permit = thePermit), ctx, 1)
    val posIn = retiring.map { case (entry, _) => inputAt(proofOfSpendUTXO(ctx, entry), ctx, 2) }
    val collatOut = collateralUTXO(ctx, lenderProver, lit = emitted + thePermit, pub = pub,
      founderSplit = split, rollupHash = rollupHash)

    Activate(ctx, emissionIn, queueIn, posIn,
      inputAt(UTXO(contractOf(lenderProver), 10L * Parameters.OneErg), ctx, 3),
      configInput(ctx, configBox(ctx, enfScript, rollupHash = rollupHash)),
      emissionOut, collatOut, lenderProver, lenderProver, thePermit, emitted)
  }

  protected def activateInputs(a: Activate): Seq[InputUTXO] =
    Seq(a.emissionIn, a.queueIn) ++ a.proofOfSpend.toSeq ++ Seq(a.funding)

  protected def activateTx(a: Activate)(emissionOut: UTXO = a.emissionOut,
                                        collatOut: UTXO = a.collatOut,
                                        inputs: Seq[InputUTXO] = activateInputs(a),
                                        dataInputs: Seq[InputUTXO] = Seq(a.config),
                                        burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(a.ctx, inputs, Seq(emissionOut, collatOut), a.prover.getAddress, dataInputs, burn)

  protected case class Clear(ctx: BlockchainContext,
                             emissionIn: InputUTXO,
                             queueIn: InputUTXO,
                             funding: InputUTXO,
                             config: InputUTXO,
                             emissionOut: UTXO,
                             prover: ErgoProver,
                             lenderProver: ErgoProver,
                             permit: Long)

  /** The lender must already hold a slot, which is the only thing that makes a queue box clearable. */
  protected def clearScenario(ctx: BlockchainContext,
                              head: Long = 0L,
                              tail: Long = 1L,
                              currentBlock: Int = 0,
                              lenderSet: Seq[Array[Byte]] = null,
                              permit: Long = -1L,
                              enfScript: Contract = Contract.SIGMA_TRUE,
                              emScript: Contract = null): Clear = {
    val lenderProver = lender(ctx)
    val set = if (lenderSet == null) Seq(setEntry(lenderProver)) else lenderSet
    val thePermit = if (permit < 0L) permitAt(tail - head) else permit

    val box = emissionUTXO(ctx, currentBlock = currentBlock, lenderSet = set, head = head, tail = tail,
      queueTokens = queueSupply - 1L)
    val emissionIn = emissionInput(ctx, box, 2.toByte, emScript = emScript, enfScript = enfScript)

    val emissionOut = emissionUTXO(ctx, currentBlock = currentBlock, lenderSet = set,
      head = head + 1L, tail = tail, queueTokens = queueSupply)

    Clear(ctx, emissionIn, inputAt(queueUTXO(ctx, lenderProver, position = head, permit = thePermit), ctx, 1),
      inputAt(UTXO(contractOf(lenderProver), 10L * Parameters.OneErg), ctx, 3),
      configInput(ctx, configBox(ctx, enfScript)),
      emissionOut, lenderProver, lenderProver, thePermit)
  }

  protected def clearTx(c: Clear)(emissionOut: UTXO = c.emissionOut,
                                  inputs: Seq[InputUTXO] = Seq(c.emissionIn, c.queueIn, c.funding),
                                  dataInputs: Seq[InputUTXO] = Seq(c.config),
                                  burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(c.ctx, inputs, Seq(emissionOut), c.prover.getAddress, dataInputs, burn)
}
