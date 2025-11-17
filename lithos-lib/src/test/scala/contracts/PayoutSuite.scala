package contracts

import contracts.ContractTestHelpers.{CHANGE_ADDRESS, DUMMY_UTXO, ONE_ERG, SCORE_VALUES, SIXTY_ERG, TOTAL_SCORE, client, getMiners, getProver, insertValuesNoNISP, localClient}
import lfsm.LFSMHelpers
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.scalatest.funsuite.AnyFunSuite
import scorex.crypto.authds.avltree.batch.PersistentBatchAVLProver
import scorex.utils.Longs
import sigma.{Coll, Colls}
import sigma.data.AvlTreeFlags
import work.lithos.mutations._
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.{LocalPlasmaMap, PlasmaMap}
import org.ergoplatform.appkit.JavaHelpers

import java.math.BigInteger

class PayoutSuite extends AnyFunSuite{




  test("Compile payout contract"){

    client.execute{
      ctx =>
        val payout: Contract = RollupContracts.mkPayoutContract(ctx)
        println(payout.mainnetAddress)
        println(payout.testnetAddress)
        println(Hex.toHexString(payout.propBytes))
    }
  }

  def makePayoutUTXO(ctx: BlockchainContext) = {
    val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
    println(Hex.toHexString(tree.digest))
    tree.insert(insertValuesNoNISP(ctx):_*)
    println(Hex.toHexString(tree.digest))

    val payout: Contract = RollupContracts.mkPayoutContract(ctx)

    UTXO(payout, SIXTY_ERG,
      registers = Seq(
        tree.ergoValue,
        ErgoValue.of(SCORE_VALUES.size),
        ErgoValue.of(BigInt(TOTAL_SCORE).bigInteger),
        ErgoValue.of(SIXTY_ERG)
      ))
  }

  test("Make payout box"){
    client.execute{
      ctx: BlockchainContext =>
        val prover = getProver(ctx)
        val input = DUMMY_UTXO.toDummyInput(ctx)
        val output = makePayoutUTXO(ctx)

        val uTx = TxBuilder(ctx)
          .setInputs(input)
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)

        println(s"Size of Payout UTXO with reference: ${output.toDummyInput(ctx).bytes.length}")
        prover.sign(uTx)

    }
  }
  test("Fully Spend payout box"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        tree.insert(insertValuesNoNISP(ctx):_*)
        println(Hex.toHexString(tree.digest))

        val keys = getMiners(ctx).map(_.hashedPropBytes)
        val lookup = tree.lookUp(keys:_*)
        val remove = tree.delete(keys:_*)


        val keyErgoVal: ErgoValue[Coll[Coll[Byte]]] = ErgoValue.ofArray(
          keys.toArray.map(x => Colls.fromArray(x)), ErgoType.collType(ErgoType.byteType()))
        val payoutInput = makePayoutUTXO(ctx).toDummyInput(ctx).setCtxVars(
          ContextVar.of(0.toByte, keyErgoVal),
          ContextVar.of(1.toByte, lookup.proof.ergoValue),
          ContextVar.of(2.toByte, remove.proof.ergoValue)
        )
        val outputs = getMiners(ctx).zip(SCORE_VALUES).map{
          cl =>
            UTXO(cl._1, LFSMHelpers.paymentFromScore(cl._2, BigInt(TOTAL_SCORE), SIXTY_ERG))
        }

        println(s"Output Total ${outputs.map(_.value).sum}")
        val uTx = TxBuilder(ctx)
          .setInputs(payoutInput, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(outputs:_*)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        val sTx = getProver(ctx).sign(uTx)
        println(sTx.toJson(true))
    }

  }


  test("Partially Spend payout box"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        tree.insert(insertValuesNoNISP(ctx):_*)
        println(Hex.toHexString(tree.digest))

        val keys = getMiners(ctx, 2).map(_.hashedPropBytes)
        val lookup = tree.lookUp(keys:_*)
        val remove = tree.delete(keys:_*)


        val keyErgoVal: ErgoValue[Coll[Coll[Byte]]] = ErgoValue.ofArray(
          keys.toArray.map(x => Colls.fromArray(x)), ErgoType.collType(ErgoType.byteType()))
        val payoutInput = makePayoutUTXO(ctx).toDummyInput(ctx).setCtxVars(
          ContextVar.of(0.toByte, keyErgoVal),
          ContextVar.of(1.toByte, lookup.proof.ergoValue),
          ContextVar.of(2.toByte, remove.proof.ergoValue)
        )
        val outputs = getMiners(ctx, 2).zip(SCORE_VALUES.slice(0, 2)).map{
          cl =>
            UTXO(cl._1, LFSMHelpers.paymentFromScore(cl._2, BigInt(TOTAL_SCORE), SIXTY_ERG))
        }
        val nextPayoutBox = payoutInput.toUTXO.withReg(0, tree.ergoValue).subValue(outputs.map(_.value).sum)
        val totalOutputs = Seq(nextPayoutBox) ++ outputs
        println(s"Output Total ${outputs.map(_.value).sum}")
        val uTx = TxBuilder(ctx)
          .setInputs(payoutInput, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(totalOutputs:_*)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        val sTx = getProver(ctx).sign(uTx)
        println(sTx.toJson(true))
    }

  }
}
