package contracts.specs.rollup

import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, TxBuilder, UTXO}

/**
 * The serialized shape of a genesis transaction, pinned byte by byte.
 *
 * `FP_MalformedGenesis` walks these bytes with no deserializer — it cannot use one, because the bytes
 * it is handed come out of a NISP the accused wrote, and a parser that throws on them is a miner
 * nobody can slash. So the proof carries hardcoded offsets, and this spec is what says those offsets
 * are still where the serializer puts them.
 *
 * A failure here means sigma changed a serialization and the proof is now reading the wrong field —
 * which would not show up as a rejected transaction, only as a proof that quietly stops binding.
 */
class GenesisLayoutSpec extends AnyPropSpec with EmissionSpecBase with RollupSpecBase {

  /** The transaction, the collateral box's bytes, and the holding box's own serialization. */
  private def genesis(ctx: BlockchainContext) = {
    val lenderProver = lender(ctx)
    val finderProver = miner(ctx)
    val height = ctx.getHeight + 1
    val rollup = holdingContract(ctx)
    val permit = LFSMHelpers.PERMIT_FLOOR
    val (emitted, pub, split) = emissionAt(0, litSupply)
    val finderLIT = pub / 25L
    val poolLIT = pub - finderLIT

    val collatBox = collateralUTXO(ctx, lenderProver, lit = emitted + permit, pub = pub,
      founderSplit = split, rollupHash = rollup.hashedPropBytes, contract = collateralContract(ctx))
    val collatIn = inputAt(collatBox, ctx, 0)

    val founderBoxes = split.map { case (key, amount) =>
      val recipient = Seq(LFSMHelpers.FOUNDER_1, LFSMHelpers.FOUNDER_2, LFSMHelpers.FOUNDER_3)
        .find(c => java.util.Arrays.equals(c.hashedPropBytes, key)).get
      UTXO(recipient, 1000000L, Seq(Token(LFSMHelpers.LIT_ID, amount)))
    }
    val permitBox = UTXO(contractOf(lenderProver), 1000000L, Seq(Token(LFSMHelpers.LIT_ID, permit)))
    val pos = UTXO(gateContract(ctx), 200000L, Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)),
      Seq(bytesValue(setEntry(lenderProver)))).setCreationHeight(height)
    val finderBox = UTXO(contractOf(finderProver), 200000L, Seq(Token(LFSMHelpers.LIT_ID, finderLIT)))

    val trailing = founderBoxes ++ Seq(permitBox, pos, finderBox)
    val minerHash = contractOf(finderProver).hashedPropBytes
    val holding = UTXO(rollup, collatBox.value - trailing.map(_.value).sum,
      Seq(Token(collatIn.id, 1L), Token(LFSMHelpers.LIT_ID, poolLIT)),
      Seq(emptyTree.ergoValue, ErgoValue.of(0), ErgoValue.of(BigInt(0).bigInteger),
        stateReg(height.toLong, height.toLong, 0L), bytesValue(minerHash)))

    val uTx = TxBuilder(ctx)
      .setInputs(collatIn)
      .setOutputs((holding +: trailing): _*)
      .setPreHeader(ctx.createPreHeader().height(height)
        .minerPk(lenderProver.getAddress.getPublicKeyGE).build())
      .buildTx(0, lenderProver.getAddress)
    accepts(finderProver, uTx) // a layout read off a transaction the contract rejects means nothing

    (uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign, collatIn, minerHash, rollup, height)
  }

  private def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)

  // ─── the transaction prefix ───────────────────────────────────────────────
  //
  // ErgoLikeTransactionSerializer over inputs whose proofs are emptied:
  //   [0]      inputs count, VLQ
  //   [1,33)   input 0 box id
  //   [33]     length of input 0's spending proof, VLQ — zero while unsigned
  //   [34]     input 0's context extension size — zero on a genesis transaction
  //   [35]     data inputs count, VLQ — zero
  //   [36]     distinct token id count, VLQ
  //   [37..]   distinct token ids, 32 bytes each, in first-appearance order

  property("prefix: one input, no data inputs, and the collateral box id at byte 1") {
    withCtx { ctx =>
      val (tx, collatIn, _, _, _) = genesis(ctx)

      tx(0) shouldBe 1.toByte
      withClue("the collateral box id is the hash of the bytes a super-share carries: ") {
        hex(tx.slice(1, 33)) shouldBe hex(collatIn.id.getBytes)
        hex(scorex.crypto.hash.Blake2b256.hash(collatIn.bytes)) shouldBe hex(collatIn.id.getBytes)
      }
      tx(33) shouldBe 0.toByte
      tx(34) shouldBe 0.toByte
      tx(35) shouldBe 0.toByte

      println(s"[genesis] tx = ${tx.length} bytes, collateral box = ${collatIn.bytes.length}")
      println(s"[genesis] distinct token ids = ${tx(36)}")
    }
  }

  /**
   * The rollup NFT is the holding box's first token and the holding box is output 0, so the NFT leads
   * the distinct-token table. That is what lets the proof read it at a fixed offset instead of
   * following a token index.
   */
  property("prefix: the first distinct token id is the rollup NFT, which is the collateral box id") {
    withCtx { ctx =>
      val (tx, collatIn, _, _, _) = genesis(ctx)
      tx(36) should be >= 1.toByte
      hex(tx.slice(37, 69)) shouldBe hex(collatIn.id.getBytes)
    }
  }

  // ─── the collateral box ───────────────────────────────────────────────────
  //
  // ErgoBoxSerializer, which is the candidate body with full token ids plus a trailing ref:
  //   value VLQ, ergoTree, creationHeight VLQ, token count, (32-byte id + amount VLQ)*, ...

  property("collateral: value VLQ, then the collateral ergoTree, then the collateral token") {
    withCtx { ctx =>
      val (_, collatIn, _, _, _) = genesis(ctx)
      val bytes = collatIn.bytes
      val tree = collateralContract(ctx).ergoTree.bytes

      val valueLen = vlqLen(bytes, 0)
      hex(bytes.slice(valueLen, valueLen + tree.length)) shouldBe hex(tree)

      val afterTree = valueLen + tree.length
      val heightLen = vlqLen(bytes, afterTree)
      val tokenCount = bytes(afterTree + heightLen)
      tokenCount should be >= 1.toByte
      hex(bytes.slice(afterTree + heightLen + 1, afterTree + heightLen + 33)) shouldBe
        hex(LFSMHelpers.COLLAT_TOKEN.getBytes)

      println(s"[collateral] valueVLQ=$valueLen tree=${tree.length} heightVLQ=$heightLen tokens=$tokenCount")
    }
  }

  // ─── output 0, the holding box ────────────────────────────────────────────

  property("holding: the walk from the output table to R8") {
    withCtx { ctx =>
      val (tx, _, minerHash, rollup, height) = genesis(ctx)
      val tree = rollup.ergoTree.bytes

      val outCountPos = 37 + tx(36).toInt * 32
      val outCount = tx(outCountPos)
      outCount should be > 0.toByte

      val outStart = outCountPos + 1
      val valueLen = vlqLen(tx, outStart)
      val scriptStart = outStart + valueLen
      withClue("output 0 is the holding box: ") {
        hex(tx.slice(scriptStart, scriptStart + tree.length)) shouldBe hex(tree)
      }

      val afterScript = scriptStart + tree.length
      val heightLen = vlqLen(tx, afterScript)
      val tokenCountPos = afterScript + heightLen
      val holdingTokens = tx(tokenCountPos).toInt
      holdingTokens shouldBe 2

      // each token is an index into the distinct table, then an amount, both VLQ
      var p = tokenCountPos + 1
      (0 until holdingTokens).foreach { _ =>
        p += vlqLen(tx, p)
        p += vlqLen(tx, p)
      }

      val nRegs = tx(p).toInt
      nRegs shouldBe 5
      val r4 = p + 1

      println(s"[holding] outCount=$outCount valueVLQ=$valueLen heightVLQ=$heightLen " +
        s"tokens=$holdingTokens nRegs=$nRegs")
      println(s"[holding] R4 type=${tx(r4)} R4+35=${tx(r4 + 35)} R4+36=${tx(r4 + 36)}")

      // R4 AvlTree: type, 33-byte digest, flags, key length, value-length option
      tx(r4) shouldBe 100.toByte
      val r5 = r4 + 37
      println(s"[holding] R5 type=${tx(r5)} value=${tx(r5 + 1)}")
      tx(r5) shouldBe 4.toByte
      tx(r5 + 1) shouldBe 0.toByte

      val r6 = r5 + 2
      println(s"[holding] R6 type=${tx(r6)} len=${tx(r6 + 1)} value=${tx(r6 + 2)}")
      tx(r6) shouldBe 6.toByte

      val r7 = r6 + 3
      println(s"[holding] R7 type=${tx(r7)} size=${tx(r7 + 1)}")
      withClue("R7 is a Coll[Long] now, not a Long — collection code 12 plus SLong 5: ") {
        tx(r7) shouldBe 17.toByte
      }
      tx(r7 + 1) shouldBe 3.toByte

      var q = r7 + 2
      (0 until 3).foreach { _ => q += vlqLen(tx, q) }

      val r8 = q
      println(s"[holding] R8 type=${tx(r8)} len=${tx(r8 + 1)} at offset $r8")
      tx(r8) shouldBe 14.toByte
      tx(r8 + 1) shouldBe 32.toByte
      withClue("R8 is the submitting miner's hash, which is what binds a share to them: ") {
        hex(tx.slice(r8 + 2, r8 + 34)) shouldBe hex(minerHash)
      }
      println(s"[holding] creation height $height, R7 slots both $height")
    }
  }

  /**
   * Where the lender's compressed key sits in a collateral box. R4 is the fee value, a Long; R5 is
   * the key, whose 33 encoded bytes follow the SigmaProp type byte and the proveDlog opcode. The
   * proof compares those bytes against the header's miner key without decoding either.
   */
  property("collateral: the lender key's 33 encoded bytes, reachable without deserializing") {
    withCtx { ctx =>
      val (_, collatIn, _, _, _) = genesis(ctx)
      val bytes = collatIn.bytes
      val tree = collateralContract(ctx).ergoTree.bytes

      val afterTree = vlqLen(bytes, 0) + tree.length
      val tokenCountPos = afterTree + vlqLen(bytes, afterTree)
      val tokenCount = bytes(tokenCountPos).toInt

      var p = tokenCountPos + 1
      (0 until tokenCount).foreach { _ =>
        p += 32
        p += vlqLen(bytes, p)
      }

      val regCount = bytes(p).toInt
      regCount shouldBe 5
      val r4 = p + 1
      bytes(r4) shouldBe 5.toByte // SLong, the fee value
      val keyStart = r4 + 1 + vlqLen(bytes, r4 + 1) + 2

      val expected = lender(ctx).getAddress.getPublicKeyGE.getEncoded.toArray
      println(s"[collateral] regCount=$regCount keyStart=$keyStart key=${hex(bytes.slice(keyStart, keyStart + 33))}")
      hex(bytes.slice(keyStart, keyStart + 33)) shouldBe hex(expected)
    }
  }

  /** Length of the VLQ beginning at `at`: continuation is the high bit. */
  private def vlqLen(bytes: Array[Byte], at: Int): Int = {
    var i = at
    while (bytes(i) < 0) i += 1
    i - at + 1
  }
}
