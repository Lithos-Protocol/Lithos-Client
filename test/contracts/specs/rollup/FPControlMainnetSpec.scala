package contracts.specs.rollup

import contracts.specs.harness.ContractSpecBase
import lfsm.contracts.RollupContracts
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

object FPControlMainnetSpec {

  private var cache: Option[Contract] = None

  def compiled(ctx: BlockchainContext): Contract = synchronized {
    cache.getOrElse {
      val c = RollupContracts.mkFPControlMainnetContract(ctx.getNetworkType)
      cache = Some(c)
      c
    }
  }
}

/**
 * FP_Control_Mainnet.ergo — one property per named condition in the contract.
 *
 * One spending path, `sigmaProp(allOf(Coll(onlyOne, oldEnough, successorYoung, unchanged)))`: anyone may
 * recreate the box unchanged once it is half a storage period old. Nothing can change the fraud proof set.
 */
class FPControlMainnetSpec extends AnyPropSpec with ContractSpecBase {

  private val REFRESH_AGE: Int = 525600
  private val HEIGHT_SLACK: Int = 1000
  private val refreshHeight: Int = 1100000

  /** HEIGHT is fixed by the preHeader, so both thresholds are exact numbers rather than approximations. */
  private val ageThreshold: Int = refreshHeight - REFRESH_AGE
  private val slackThreshold: Int = refreshHeight - HEIGHT_SLACK

  private val boxValue5: Long = 5L * Parameters.OneErg

  private val fpToken: ErgoId =
    ErgoId.create("3333333333333333333333333333333333333333333333333333333333333333")
  private val otherToken: ErgoId =
    ErgoId.create("4444444444444444444444444444444444444444444444444444444444444444")

  /** One hash per live fraud proof, so the box is the size it will be on mainnet. */
  private val fpHashes: Seq[Array[Byte]] = (1 to 9).map(i => Blake2b256.hash(s"fraud-proof-$i".getBytes("UTF-8")))

  private def setValue(hashes: Seq[Array[Byte]]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(hashes.map(h => Colls.fromArray(h)).toArray), ErgoType.collType(scalaByteType))

  private def contract(ctx: BlockchainContext): Contract = FPControlMainnetSpec.compiled(ctx)

  private def fpControl(ctx: BlockchainContext, createdAt: Int = 500000): UTXO =
    UTXO(contract(ctx), boxValue5, Seq(Token(fpToken, 1L)), Seq(setValue(fpHashes))).setCreationHeight(createdAt)

  /** The honest successor: the same box, declared at the refresh height. */
  private def successorOf(box: UTXO): UTXO = box.setCreationHeight(refreshHeight)

  private def refresh(ctx: BlockchainContext,
                      prover: ErgoProver,
                      box: UTXO,
                      successor: UTXO,
                      inputs: Seq[InputUTXO] = null,
                      burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction = {
    val ins = if (inputs == null) Seq(inputAt(box, ctx, 0), fundingInput(ctx, prover)) else inputs
    buildAt(ctx, refreshHeight, ins, Seq(successor), prover.getAddress, burn = burn)
  }

  // ─── oldEnough ────────────────────────────────────────────────────────────

  property("refresh: accepts a box one block past CONST_REFRESH_AGE") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx, createdAt = ageThreshold - 1)
      accepts(p, refresh(ctx, p, box, successorOf(box)))
    }
  }

  /** A young box cannot be refreshed, so nobody can churn its id to invalidate fraud proofs in flight. */
  property("refresh: rejects a box exactly at CONST_REFRESH_AGE (oldEnough)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx, createdAt = ageThreshold)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box)))
    }
  }

  // ─── successorYoung ───────────────────────────────────────────────────────

  property("refresh: accepts a successor declared one block inside CONST_HEIGHT_SLACK") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      accepts(p, refresh(ctx, p, box, box.setCreationHeight(slackThreshold + 1)))
    }
  }

  /** A backdated successor would be old enough for rent the moment it lands. */
  property("refresh: rejects a successor declared exactly at CONST_HEIGHT_SLACK (successorYoung)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      rejectsAtSigning(p, refresh(ctx, p, box, box.setCreationHeight(slackThreshold)))
    }
  }

  // ─── onlyOne ──────────────────────────────────────────────────────────────

  property("refresh: rejects when the box is not INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box),
        inputs = Seq(fundingInput(ctx, p), inputAt(box, ctx, 2))))
    }
  }

  // ─── unchanged ────────────────────────────────────────────────────────────

  property("unchanged: rejects a fraud proof removed from the set (R4)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box).withReg(0, setValue(fpHashes.tail))))
    }
  }

  property("unchanged: rejects a fraud proof added to the set (R4)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      val added = fpHashes :+ Blake2b256.hash("sigmaProp(true)".getBytes("UTF-8"))
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box).withReg(0, setValue(added))))
    }
  }

  property("unchanged: rejects a successor under a different script (propositionBytes)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box).setContract(Contract.SIGMA_TRUE)))
    }
  }

  /** The refresher pays the fee from their own input, and may leave the box richer than they found it. */
  property("unchanged: accepts a successor holding more ERG (value >=)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      accepts(p, refresh(ctx, p, box, successorOf(box).addValue(Parameters.MinFee)))
    }
  }

  /** Otherwise anyone could skim the rent reserve down to nothing, one refresh at a time. */
  property("unchanged: rejects a successor holding less ERG (value >=)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box).subValue(1L)))
    }
  }

  property("unchanged: rejects the NFT burned (tokens)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box).setTokens(),
        burn = Seq(Token(fpToken, 1L))))
    }
  }

  /** A second token is padding: it raises the rent the box owes without raising its value. */
  property("unchanged: rejects an extra token (tokens)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      val carrier = inputAt(UTXO(contractOf(p), Parameters.OneErg, Seq(Token(otherToken, 1L))), ctx, 1)
      rejectsAtSigning(p, refresh(ctx, p, box, successorOf(box).addToken(Token(otherToken, 1L)),
        inputs = Seq(inputAt(box, ctx, 0), carrier)))
    }
  }

  property("unchanged: rejects an extra register (noExtraRegisters)") {
    withCtx { ctx =>
      val p = miner(ctx)
      val box = fpControl(ctx)
      val padded = successorOf(box).setRegs(setValue(fpHashes), ErgoValue.of(Colls.fromArray(new Array[Byte](512)), scalaByteType))
      rejectsAtSigning(p, refresh(ctx, p, box, padded))
    }
  }

  // ─── size ─────────────────────────────────────────────────────────────────

  /**
   * The refresh path is the first defence against rent; 5 ERG is the second, should nobody refresh. Nine
   * hashes make this box 476 bytes, so it buys four periods at the maximum factor and eight at today's.
   */
  property("rent: 5 ERG covers at least 4 storage periods at the maximum fee factor") {
    withCtx { ctx =>
      val factorMax = 2500000L
      val size = inputAt(fpControl(ctx), ctx, 0).bytes.length
      println(s"[size] FP_Control_Mainnet ergoTree = ${contract(ctx).propBytes.length} bytes")
      println(s"[rent] FP_Control_Mainnet box = $size bytes, ${factorMax * size} nanoERG per period at max")
      boxValue5 / (factorMax * size) should be >= 4L
    }
  }
}
