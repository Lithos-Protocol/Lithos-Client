package state.synchronization

import lfsm.states.PlasmaDictionary
import org.ergoplatform.appkit.ErgoValue
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.utils.Longs
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, TxInput}
import support.{ReducerFixtures, SyncFixtures}

/**
 * The reducer must return a typed failure for anything a stranger can put on the chain.
 *
 * An escaped exception reaches the state owner as an unattributable failure, which stops the cursor —
 * the one remaining way a single transaction can halt every client running this build.
 */
class ReducerTotalitySpec extends AnyFlatSpec with Matchers with OptionValues {

  private val protocol = ReducerFixtures.protocol()

  /**
   * The dictionary contract accepts any SigmaProp as the signer, so the address derived from it is not
   * necessarily the ordinary single-key path. That derivation is the last expression on the reducer's
   * success path, and it runs on data a stranger chose.
   */
  "BlockReducer" should "commit a block whose dictionary registration uses a non-dlog proposition" in {
    val base = ReducerFixtures.emptyState()
    val proposition: sigma.data.SigmaBoolean = sigma.data.TrivialProp.TrueProp
    val tree = sigma.ast.ErgoTree.fromProposition(
      ErgoValue.of(proposition).getValue.asInstanceOf[sigma.SigmaProp])
    // Derived exactly as the reducer does, so the run reaches the address rather than stopping at the
    // key check before it.
    val key = new work.lithos.mutations.Contract(tree).hashedPropBytes
    val block = BlockInfo(SyncFixtures.id(100), 100,
      Seq(dictionaryAdd(ErgoValue.of(proposition).toHex, key)), base.cursor.blockId)

    val result = BlockReducer.applyBlock(base, block, protocol)

    // Either outcome is acceptable; an exception escaping to the caller is not.
    result.isRight shouldBe true
    result.toOption.value.state.minerTree.minerMap.keySet should
      contain(org.bouncycastle.util.encoders.Hex.toHexString(key))
  }

  it should "return a typed failure for every malformed shape rather than throwing" in {
    val base = ReducerFixtures.emptyState()
    val dictionary = PlasmaDictionary.empty()
    val rollupId = SyncFixtures.id(910001)
    val utxoId = SyncFixtures.id(910002)
    val tracked = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, utxoId, dictionary)
    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head

    val malformed = Seq(
      "missing spending proof" ->
        BlockTx(SyncFixtures.id(1), Seq(TxInput(utxoId, None)), Seq.empty,
          Seq(ReducerFixtures.holdingOutput(SyncFixtures.id(2), SyncFixtures.id(1), 100, dictionary))),
      "context variable that is not a pair" ->
        BlockTx(SyncFixtures.id(3),
          Seq(TxInput(utxoId, Some(InputSpendingProof("", Map("1" -> ErgoValue.of(7).toHex,
            "2" -> ReducerFixtures.proofHex(Array[Byte](1))))))), Seq.empty,
          Seq(ReducerFixtures.holdingOutput(SyncFixtures.id(4), SyncFixtures.id(3), 100, dictionary))),
      "undecodable register" ->
        BlockTx(SyncFixtures.id(5),
          Seq(TxInput(utxoId, Some(InputSpendingProof("", Map("1" -> pair(key, value),
            "2" -> ReducerFixtures.proofHex(Array[Byte](1))))))), Seq.empty,
          Seq(ReducerFixtures.holdingOutput(SyncFixtures.id(6), SyncFixtures.id(5), 100, dictionary)
            .copy(registers = Seq("not-a-register")))),
      "output with no registers at all" ->
        BlockTx(SyncFixtures.id(7),
          Seq(TxInput(utxoId, Some(InputSpendingProof("", Map("1" -> pair(key, value),
            "2" -> ReducerFixtures.proofHex(Array[Byte](1))))))), Seq.empty,
          Seq(ReducerFixtures.holdingOutput(SyncFixtures.id(8), SyncFixtures.id(7), 100, dictionary)
            .copy(registers = Seq.empty))))

    malformed.foreach { case (name, tx) =>
      withClue(s"$name: ") {
        val block = BlockInfo(SyncFixtures.id(100), 100, Seq(tx), tracked.cursor.blockId)
        val transition = BlockReducer.applyBlock(tracked, block, protocol)
        // The block commits with the rollup quarantined; nothing escapes as an exception.
        transition.isRight shouldBe true
        transition.toOption.value.quarantined should not be empty
      }
    }
  }

  private def pair(key: Array[Byte], value: Array[Byte]): String =
    ErgoValue.pairOf(
      ErgoValue.of(sigma.Colls.fromArray(key), org.ergoplatform.appkit.scalaapi.scalaByteType),
      ErgoValue.of(sigma.Colls.fromArray(value), org.ergoplatform.appkit.scalaapi.scalaByteType)).toHex

  private def dictionaryAdd(signerHex: String, key: Array[Byte]): BlockTx = {
    val txId = SyncFixtures.id(920001)
    val value = Longs.toByteArray(1L) ++ Array.fill[Byte](24)(2)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(key -> value)
    val proof = InputSpendingProof("", Map(
      "0" -> ErgoValue.of(0.toByte).toHex,
      "1" -> signerHex,
      "2" -> pair(key, value),
      "3" -> ReducerFixtures.proofHex(insertion.proof.ergoValue.getValue
        .asInstanceOf[sigma.Coll[Byte]].toArray)))
    val dataToken = work.lithos.mutations.Token(
      org.ergoplatform.sdk.ErgoId.create(SyncFixtures.id(920003)), 1L)
    val dataBox = state.messages.TxOutput(SyncFixtures.id(920004), 1000000L, "miner-data",
      Seq(ErgoValue.of(0L).toHex, ReducerFixtures.collBytes(key).toHex), Seq(dataToken), txId, 100, 1)
    BlockTx(txId, Seq(TxInput(protocol.minerDictionaryGenesisId, Some(proof))), Seq.empty,
      Seq(ReducerFixtures.dictionaryOutput(SyncFixtures.id(920002), txId, 100,
        expected, ReducerFixtures.MinerDictionaryToken), dataBox))
  }
}
