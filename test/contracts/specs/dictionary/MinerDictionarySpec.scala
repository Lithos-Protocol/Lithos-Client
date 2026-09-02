package contracts.specs.dictionary

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec

import java.math.BigInteger
import scorex.crypto.hash.Blake2b256
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * `MinerDictionary.ergo` — one property per named condition.
 *
 * Three operations: register (0), evict (1), remove (2). Every scenario builds one known-good
 * transaction and each negative perturbs exactly one field of it. There is no renewal: a registration
 * expires after CONST_DATA_LIFETIME and the miner removes and registers again.
 *
 * HEIGHT is pinned with a preHeader on every path, because the entry's `validUntil` is
 * `HEIGHT + CONST_DATA_LIFETIME` and the mocked context leaves HEIGHT ambiguous between `getHeight`
 * and `getHeight + 1`.
 */
class MinerDictionarySpec extends AnyPropSpec with DictionarySpecBase {

  /**
   * A credential minted by some earlier registration, for the evict and remove scenarios which do
   * not mint one themselves. Distinct from `mdToken`: reaching for the dictionary's own NFT here
   * would put it on the data box and quietly test something else.
   */
  private val priorCredential: ErgoId =
    ErgoId.create("c2ede4ca11a0000000000000000000000000000000000000000000000000000c")

  /** Belongs to nothing, and is not the dictionary NFT. */
  private val strangerNFT: ErgoId =
    ErgoId.create("5732416e6765720000000000000000000000000000000000000000000000000a")

  // ─── register ─────────────────────────────────────────────────────────────

  private case class Registration(ctx: BlockchainContext,
                                  height: Int,
                                  dictIn: InputUTXO,
                                  funding: InputUTXO,
                                  nextDict: UTXO,
                                  dataBox: UTXO,
                                  prover: ErgoProver,
                                  identity: Contract,
                                  minerHash: Array[Byte],
                                  credentialId: ErgoId,
                                  entry: Array[Byte],
                                  commitHeight: Int,
                                  score: Long)

  private def registration(ctx: BlockchainContext,
                           score: Long = 100000L,
                           commitOffset: Long = nispWindow + 40L,
                           existing: Seq[(Array[Byte], Array[Byte])] = Seq.empty,
                           identity: Contract = null,
                           funder: BigInteger = null,
                           at: Int = -1,
                           dataCreationHeight: Int = -1,
                           validUntil: Long = Long.MinValue): Registration = {
    val height = if (at < 0) ctx.getHeight + 1 else at
    val prover = if (funder == null) miner(ctx) else proverWith(ctx, funder)
    val who = if (identity == null) contractOf(prover) else identity
    val minerHash = who.hashedPropBytes

    val tree = treeWith(existing)
    val dictBox = dictionaryUTXO(ctx, tree)
    val dictIn = inputAt(dictBox, ctx, 0)
    val credentialId = dictIn.id

    val commitHeight = height + commitOffset.toInt
    // Bounded by the contract rather than derived from HEIGHT, so a registration that lands late
    // still inserts the value it proved.
    val expiry = if (validUntil == Long.MinValue) height.toLong + dataLifetime else validUntil
    val entry = entryBytes(credentialId, expiry)

    val insertion = tree.insert(minerHash -> entry)
    val outTree = tree.ergoValue

    val withVars = dictIn.setCtxVars(
      opVar(0.toByte, 0.toByte),
      bytesVar(1.toByte, who.valueBytes),
      ContextVar.of(2.toByte, ErgoValue.of(score)),
      ContextVar.of(3.toByte, ErgoValue.of(commitHeight)),
      ContextVar.of(4.toByte, insertion.proof.ergoValue),
      ContextVar.of(8.toByte, ErgoValue.of(expiry)))

    val nextDict = dictionaryUTXO(ctx, tree).withReg(0, outTree)
    val dataBox = dataUTXO(ctx, credentialId, minerHash, Seq((commitHeight, score)),
      creationHeight = if (dataCreationHeight < 0) None else Some(dataCreationHeight))

    Registration(ctx, height, withVars,
      fundingInput(ctx, prover, 2L * Parameters.OneErg),
      nextDict, dataBox, prover, who, minerHash, credentialId, entry, commitHeight, score)
  }

  private def register(r: Registration,
                       outputs: Seq[UTXO] = null,
                       inputs: Seq[InputUTXO] = null): UnsignedTransaction =
    buildAt(r.ctx, r.height,
      if (inputs == null) Seq(r.dictIn, r.funding) else inputs,
      if (outputs == null) Seq(r.nextDict, r.dataBox) else outputs,
      r.prover.getAddress)

  property("register: accepts a well-formed registration") {
    withCtx { ctx =>
      val r = registration(ctx)
      accepts(r.prover, register(r))
    }
  }

  property("register: accepts a miner joining a dictionary that already holds others") {
    withCtx { ctx =>
      val other = Blake2b256.hash("someone else")
      val r = registration(ctx, existing = Seq(other -> entryBytes(mdToken, 999999L)))
      accepts(r.prover, register(r))
    }
  }

  /**
   * The point of executing the identity from a context variable: a miner identity is any script, so a
   * contract can hold a registration, exactly as `Holding_Guard` allows for submissions.
   */
  property("register: accepts a contract as the miner identity (executeFromVar)") {
    withCtx { ctx =>
      val r = registration(ctx, identity = Contract.SIGMA_TRUE, funder = funderSecret)
      accepts(r.prover, register(r))
    }
  }

  property("register: rejects an identity script that evaluates to false (signerProp)") {
    withCtx { ctx =>
      val r = registration(ctx, identity = Contract.SIGMA_FALSE, funder = funderSecret)
      rejectsAtSigning(r.prover, register(r))
    }
  }

  property("register: rejects a transaction the identity script has not authorised (signerProp)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(proverWith(ctx, funderSecret), register(r))
    }
  }

  property("register: rejects a data box under the wrong script (dataBoxMade)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict, r.dataBox.setContract(Contract.SIGMA_TRUE))))
    }
  }

  property("register: rejects a data box funded below CONST_MIN_DATA_BOX_AMNT (dataBoxFunded)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict, r.dataBox.subValue(1L))))
    }
  }

  property("register: rejects a data box funded above CONST_MIN_DATA_BOX_AMNT (dataBoxFunded)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict, r.dataBox.addValue(1L))))
    }
  }

  property("register: rejects a data box carrying no credential (credentialHeld)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict, r.dataBox.setTokens())))
    }
  }

  property("register: rejects a credential amount other than one (credentialHeld)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict, r.dataBox.setTokens(Token(r.credentialId, 2L)))))
    }
  }

  /**
   * What makes the credential a credential. Ergo lets this transaction mint any amount of a token
   * taking `INPUTS(0).id`, so constraining only the data box would let one authorised registration
   * hand out N units. The data box here is flawless; the second unit escapes to another output.
   */
  property("register: rejects a second credential unit escaping to another output (mintPinned)") {
    withCtx { ctx =>
      val r = registration(ctx)
      val escapee = UTXO(contractOf(r.prover), Parameters.MinFee, Seq(Token(r.credentialId, 1L)))
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict, r.dataBox, escapee)))
    }
  }

  property("register: rejects a data box holding a second token alongside the credential (credentialHeld)") {
    withCtx { ctx =>
      val r = registration(ctx)
      val funded = inputAt(UTXO(contractOf(r.prover), 2L * Parameters.OneErg,
        Seq(Token(mdToken, 5L))), ctx, 5)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict, r.dataBox.addToken(Token(mdToken, 1L))),
        inputs = Seq(r.dictIn, funded)))
    }
  }

  property("register: rejects a commitment that does not match the declared score (commitmentSet)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict,
        r.dataBox.withReg(0, commitmentsValue(Seq((r.commitHeight, r.score + 1L)))))))
    }
  }

  property("register: rejects a box registered with two commitments (commitmentSet)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict,
        r.dataBox.withReg(0, commitmentsValue(
          Seq((r.commitHeight, r.score), (r.commitHeight, r.score)))))))
    }
  }

  property("register: rejects a non-positive committed score (scorePositive)") {
    withCtx { ctx =>
      val r = registration(ctx, score = 0L)
      rejectsAtSigning(r.prover, register(r))
    }
  }

  /** The notice period. A commitment taking effect now is a difficulty chosen after the fact. */
  property("register: rejects a commitment taking effect before CONST_NISP_WINDOW (noticeGiven)") {
    withCtx { ctx =>
      val r = registration(ctx, commitOffset = nispWindow - 1L)
      rejectsAtSigning(r.prover, register(r))
    }
  }

  property("register: accepts a commitment exactly CONST_NISP_WINDOW out (noticeGiven)") {
    withCtx { ctx =>
      val r = registration(ctx, commitOffset = nispWindow)
      accepts(r.prover, register(r))
    }
  }

  property("register: rejects a data box stamped with someone else's identity (identityStamped)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict,
        r.dataBox.withReg(1, bytesValue(Blake2b256.hash("not me"))))))
    }
  }

  /** The structural bound that makes the rent floor a fact rather than an observation. */
  property("register: rejects a data box padded past R5 (dataBoxBounded)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict,
        r.dataBox.withRegNum(6, bytesValue(Array.fill(512)(7.toByte))))))
    }
  }

  /**
   * A builder picks a box's creation height, and collection falls a storage period after it. Backdate
   * far enough and the box is collectible while its entry is live, which puts the credential in a box
   * that is never spent and so never guarded. Run at a high pinned HEIGHT, since the eviction delay
   * exceeds the mocked context's own height.
   */
  property("register: rejects a data box backdated past the collection gap (notCollectibleEarly)") {
    withCtx { ctx =>
      val at = 2000000
      val r = registration(ctx, at = at, dataCreationHeight = (at - evictDelay).toInt)
      rejectsAtSigning(r.prover, register(r))
    }
  }

  property("register: accepts a data box one block inside the bound (notCollectibleEarly)") {
    withCtx { ctx =>
      val at = 2000000
      val r = registration(ctx, at = at, dataCreationHeight = (at - evictDelay + 1L).toInt)
      accepts(r.prover, register(r))
    }
  }

  // ─── the supplied expiry ──────────────────────────────────────────────────
  //
  // The expiry goes into the tree, so the builder picks it: a contract-derived one changes if the
  // transaction lands late, and the entry proved would not be the entry inserted. Bounded instead.

  property("register: rejects an expiry beyond the full lifetime (expiryScheduled)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(
        registration(ctx, validUntil = r.height.toLong + dataLifetime + 1L)))
    }
  }

  property("register: accepts an expiry at exactly the full lifetime (expiryScheduled)") {
    withCtx { ctx =>
      val r = registration(ctx)
      accepts(r.prover, register(
        registration(ctx, validUntil = r.height.toLong + dataLifetime)))
    }
  }

  /** The point of the bound: a registration built at H and mined at H+k still validates. */
  property("register: accepts an expiry a full CONST_REGISTER_SLACK short (expiryFresh)") {
    withCtx { ctx =>
      val r = registration(ctx)
      accepts(r.prover, register(
        registration(ctx, validUntil = r.height.toLong + dataLifetime - registerSlack)))
    }
  }

  property("register: rejects an expiry one block past the slack (expiryFresh)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(
        registration(ctx, validUntil = r.height.toLong + dataLifetime - registerSlack - 1L)))
    }
  }

  /** A miner cannot buy themselves a longer registration than the schedule allows. */
  property("register: rejects an expiry far in the future (expiryScheduled)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(
        registration(ctx, validUntil = r.height.toLong + 10L * dataLifetime)))
    }
  }

  property("register: rejects a successor whose tree does not match the insertion (treeUpdated)") {
    withCtx { ctx =>
      val r = registration(ctx)
      val wrong = treeWith(Seq(Blake2b256.hash("unrelated") -> entryBytes(mdToken, 1L)))
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict.withReg(0, wrong.ergoValue), r.dataBox)))
    }
  }

  property("register: rejects a successor that is not the dictionary contract (validUTXO)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict.setContract(Contract.SIGMA_TRUE), r.dataBox)))
    }
  }

  property("register: rejects a change in the singleton's value (valueConserved)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict.addValue(1L), r.dataBox)))
    }
  }

  property("register: rejects a successor that drops the dictionary NFT (tokensConserved)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r, outputs = Seq(r.nextDict.setTokens(), r.dataBox)))
    }
  }

  property("register: rejects a successor padded past R4 (noExtraRegisters)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejectsAtSigning(r.prover, register(r,
        outputs = Seq(r.nextDict.withRegNum(5, bytesValue(Array.fill(512)(7.toByte))), r.dataBox)))
    }
  }

  /**
   * `rejects` rather than `rejectsAtSigning`: the credential takes the id of `INPUTS(0)`, so moving
   * the dictionary off index 0 makes the transaction unbuildable. `onlyOne` would catch it; Ergo's
   * minting rule gets there first.
   */
  property("register: rejects when the dictionary is not INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val r = registration(ctx)
      rejects(r.prover, register(r, inputs = Seq(r.funding, r.dictIn)))
    }
  }

  property("register: rejects an unrecognised op byte (fail closed)") {
    withCtx { ctx =>
      val r = registration(ctx)
      val wrongOp = r.dictIn.setCtxVars(
        (r.dictIn.ctxVars.filterNot(_.getId == 0.toByte) :+ opVar(0.toByte, 9.toByte)): _*)
      rejectsAtSigning(r.prover, buildAt(ctx, r.height, Seq(wrongOp, r.funding),
        Seq(r.nextDict, r.dataBox), r.prover.getAddress))
    }
  }

  // ─── evict ────────────────────────────────────────────────────────────────
  //
  // Permissionless, takes no MinerData box, and waits CONST_EVICT_DELAY past expiry. It exists so an
  // identity whose box was collected for storage rent can be registered again at all: `remove` needs
  // that box as an input, and once the collector has it the tree key is otherwise stuck forever.
  //
  // These scenarios run at a high pinned HEIGHT: the eviction delay exceeds the mocked context's own
  // height, so at the default height the boundary cannot be reached from either side.

  private case class Eviction(ctx: BlockchainContext,
                              height: Int,
                              dictIn: InputUTXO,
                              funding: InputUTXO,
                              nextDict: UTXO,
                              prover: ErgoProver,
                              minerHash: Array[Byte],
                              validUntil: Long)

  private def eviction(ctx: BlockchainContext,
                       expiredBy: Long = evictDelay + 100L,
                       op: Byte = 1.toByte,
                       entry: Array[Byte] = null): Eviction = {
    val height = 2000000
    val prover = miner(ctx)
    val minerHash = contractOf(prover).hashedPropBytes
    val validUntil = height.toLong - expiredBy
    val oldEntry = if (entry == null) entryBytes(priorCredential, validUntil) else entry

    val tree = treeWith(Seq(minerHash -> oldEntry))
    val dictIn = inputAt(dictionaryUTXO(ctx, tree), ctx, 0)

    val lookup = tree.lookUp(minerHash)
    val removal = tree.delete(minerHash)
    val outTree = tree.ergoValue

    val withVars = dictIn.setCtxVars(
      opVar(0.toByte, op),
      bytesVar(5.toByte, minerHash),
      ContextVar.of(6.toByte, lookup.proof.ergoValue),
      ContextVar.of(7.toByte, removal.proof.ergoValue))

    Eviction(ctx, height, withVars, fundingInput(ctx, prover, Parameters.OneErg),
      dictionaryUTXO(ctx, tree).withReg(0, outTree), prover, minerHash, validUntil)
  }

  private def evict(e: Eviction, outputs: Seq[UTXO] = null): UnsignedTransaction =
    buildAt(e.ctx, e.height, Seq(e.dictIn, e.funding),
      if (outputs == null) Seq(e.nextDict) else outputs, e.prover.getAddress)

  property("evict: accepts retiring an entry long past its expiry") {
    withCtx { ctx =>
      val e = eviction(ctx)
      accepts(e.prover, evict(e))
    }
  }

  /** Anyone may do it. In practice the miner does, because they want their identity back. */
  property("evict: accepts eviction by someone who is not the miner") {
    withCtx { ctx =>
      val e = eviction(ctx)
      val stranger = proverWith(ctx, funderSecret)
      accepts(stranger, buildAt(ctx, e.height,
        Seq(e.dictIn, fundingInput(ctx, stranger, Parameters.OneErg)),
        Seq(e.nextDict), stranger.getAddress))
    }
  }

  /**
   * The boundary that makes permissionless eviction safe. A rollup still in evaluation at this height
   * was mined after the entry expired, so eviction changes nothing for it. Any sooner and a miner who
   * was registered at the time becomes a non-member retroactively.
   */
  property("evict: rejects an entry that expired exactly CONST_EVICT_DELAY ago (longExpired)") {
    withCtx { ctx =>
      val e = eviction(ctx, expiredBy = evictDelay)
      rejectsAtSigning(e.prover, evict(e))
    }
  }

  property("evict: accepts one block past the boundary (longExpired)") {
    withCtx { ctx =>
      val e = eviction(ctx, expiredBy = evictDelay + 1L)
      accepts(e.prover, evict(e))
    }
  }

  property("evict: rejects an entry that has not expired at all (longExpired)") {
    withCtx { ctx =>
      val e = eviction(ctx, expiredBy = -dataLifetime)
      rejectsAtSigning(e.prover, evict(e))
    }
  }

  property("evict: rejects a malformed entry (wellFormed)") {
    withCtx { ctx =>
      val e = eviction(ctx, entry = priorCredential.getBytes)
      rejectsAtSigning(e.prover, evict(e))
    }
  }

  property("evict: rejects an identity that is not in the dictionary at all") {
    withCtx { ctx =>
      val e = eviction(ctx)
      val emptyDict = inputAt(dictionaryUTXO(ctx, emptyTree), ctx, 0).setCtxVars(e.dictIn.ctxVars: _*)
      rejects(e.prover, buildAt(ctx, e.height, Seq(emptyDict, e.funding),
        Seq(e.nextDict), e.prover.getAddress))
    }
  }

  property("evict: rejects a successor whose entry was not deleted (treeUpdated)") {
    withCtx { ctx =>
      val e = eviction(ctx)
      val stale = treeWith(Seq(e.minerHash -> entryBytes(priorCredential, 1L)))
      rejectsAtSigning(e.prover, evict(e, outputs = Seq(e.nextDict.withReg(0, stale.ergoValue))))
    }
  }

  property("evict: rejects a change in the singleton's value (valueConserved)") {
    withCtx { ctx =>
      val e = eviction(ctx)
      rejectsAtSigning(e.prover, evict(e, outputs = Seq(e.nextDict.addValue(1L))))
    }
  }

  property("evict: rejects a successor that drops the dictionary NFT (tokensConserved)") {
    withCtx { ctx =>
      val e = eviction(ctx)
      rejectsAtSigning(e.prover, evict(e, outputs = Seq(e.nextDict.setTokens())))
    }
  }

  /**
   * Eviction never spends a MinerData box, so the credential survives. Safe because the entry is gone:
   * a fraud proof finds nothing and treats the miner as unregistered, which is what the expired entry
   * already produced.
   */
  property("evict: leaves the credential alone, and the entry is what stops mattering") {
    withCtx { ctx =>
      val e = eviction(ctx)
      accepts(e.prover, evict(e))
      e.dictIn.tokens.map(_.id.toString) shouldBe Seq(mdToken.toString)
    }
  }

  // ─── remove ───────────────────────────────────────────────────────────────

  private case class Removal(ctx: BlockchainContext,
                             height: Int,
                             dictIn: InputUTXO,
                             dataIn: InputUTXO,
                             funding: InputUTXO,
                             nextDict: UTXO,
                             prover: ErgoProver,
                             minerHash: Array[Byte],
                             credentialId: ErgoId)

  private def removal(ctx: BlockchainContext,
                      dataOp: Byte = 2.toByte,
                      dictNFT: ErgoId = null): Removal = {
    val height = ctx.getHeight + 1
    val prover = miner(ctx)
    val identity = contractOf(prover)
    val minerHash = identity.hashedPropBytes
    val credentialId = priorCredential
    val nft = if (dictNFT == null) mdToken else dictNFT
    val entry = entryBytes(credentialId, height.toLong + 1000L)
    val commitments = Seq((height - 500, 100000L), (height - 2000, 90000L))

    val tree = treeWith(Seq(minerHash -> entry))
    val dictBox = dictionaryUTXO(ctx, tree, token = nft)
    val dataBox = dataUTXO(ctx, credentialId, minerHash, commitments)

    val lookup = tree.lookUp(minerHash)
    val removeProof = tree.delete(minerHash)
    val outTree = tree.ergoValue

    val dictIn = inputAt(dictBox, ctx, 0).setCtxVars(
      opVar(0.toByte, 2.toByte),
      bytesVar(5.toByte, minerHash),
      ContextVar.of(6.toByte, lookup.proof.ergoValue),
      ContextVar.of(7.toByte, removeProof.proof.ergoValue))

    Removal(ctx, height, dictIn, dataInput(ctx, dataBox, identity, dataOp),
      fundingInput(ctx, prover, 2L * Parameters.OneErg),
      dictionaryUTXO(ctx, tree, token = nft).withReg(0, outTree),
      prover, minerHash, credentialId)
  }

  private def remove(r: Removal,
                     outputs: Seq[UTXO] = null,
                     burn: Seq[Token] = null): UnsignedTransaction =
    buildAt(r.ctx, r.height, Seq(r.dictIn, r.dataIn, r.funding),
      if (outputs == null) Seq(r.nextDict) else outputs,
      r.prover.getAddress,
      burn = if (burn == null) Seq(Token(r.credentialId, 1L)) else burn)

  property("remove: accepts a removal that burns the credential and deletes the entry") {
    withCtx { ctx =>
      val r = removal(ctx)
      accepts(r.prover, remove(r))
    }
  }

  /**
   * Burned rather than moved. A surviving unit outside a data box is a working claim to be this miner,
   * because a fraud proof authenticates a data input on the token alone.
   */
  property("remove: rejects a removal that keeps the credential alive (credentialBurned)") {
    withCtx { ctx =>
      val r = removal(ctx)
      val keeper = UTXO(contractOf(r.prover), Parameters.MinFee, Seq(Token(r.credentialId, 1L)))
      rejectsAtSigning(r.prover, remove(r, outputs = Seq(r.nextDict, keeper), burn = Seq.empty))
    }
  }

  property("remove: rejects a removal that recreates a data box (noDataBoxLeft)") {
    withCtx { ctx =>
      val r = removal(ctx)
      val survivor = dataUTXO(ctx, r.credentialId, r.minerHash, Seq((r.height + 500, 1L)))
      rejectsAtSigning(r.prover, remove(r, outputs = Seq(r.nextDict, survivor), burn = Seq.empty))
    }
  }

  property("remove: rejects a successor whose entry was not deleted (treeUpdated)") {
    withCtx { ctx =>
      val r = removal(ctx)
      val stale = treeWith(Seq(r.minerHash -> entryBytes(r.credentialId, 5L)))
      rejectsAtSigning(r.prover, remove(r, outputs = Seq(r.nextDict.withReg(0, stale.ergoValue))))
    }
  }

  property("remove: rejects removing an identity that is not registered") {
    withCtx { ctx =>
      val r = removal(ctx)
      val wrongHash = r.dictIn.setCtxVars(
        (r.dictIn.ctxVars.filterNot(_.getId == 5.toByte) :+
          bytesVar(5.toByte, Blake2b256.hash("not me"))): _*)
      rejects(r.prover, buildAt(ctx, r.height, Seq(wrongHash, r.dataIn, r.funding),
        Seq(r.nextDict), r.prover.getAddress, burn = Seq(Token(r.credentialId, 1L))))
    }
  }

  /**
   * The dictionary authenticates itself by its own NFT. Without it anyone builds a box under the
   * public dictionary script and mints credentials from a tree of their own.
   */
  property("remove: rejects a dictionary box that is not carrying CONST_MD_ID (authenticSelf)") {
    withCtx { ctx =>
      val r = removal(ctx, dictNFT = strangerNFT)
      rejectsAtSigning(r.prover, remove(r))
    }
  }

  /** The MinerData logic checks the same thing from its side, so the pair fails closed together. */
  property("remove: rejects a data box whose logic sees the wrong dictionary (authenticDictionary)") {
    withCtx { ctx =>
      val r = removal(ctx, dictNFT = strangerNFT)
      rejectsAtSigning(r.prover, remove(r))
    }
  }
}
