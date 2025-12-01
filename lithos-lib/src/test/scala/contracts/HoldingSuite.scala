package contracts

import contracts.ContractTestHelpers._
import lfsm.LFSMHelpers
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit._
import org.scalatest.funsuite.AnyFunSuite
import scorex.crypto.authds.avltree.batch.BatchAVLProver
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.utils.Longs
import sigma.Colls
import sigma.data.AvlTreeFlags
import sigma.exceptions.InterpreterException
import work.lithos.mutations._
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

class HoldingSuite extends AnyFunSuite{




  test("Compile holding contract"){

    client.execute{
      ctx =>
        val payout = RollupContracts.mkPayoutContract(ctx)
        val eval: Contract =
          RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
        val holding = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
        println(holding.mainnetAddress)
        println(holding.testnetAddress)
        println(Hex.toHexString(holding.propBytes))
    }
  }



  def makeHoldingUTXO(ctx: BlockchainContext, existingMiners: Seq[(Contract, Long)], addHeight: Long) = {
    val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)

    val payout = RollupContracts.mkPayoutContract(ctx)
    val eval: Contract =
      RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
    val holding = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
    println(s"Setting period start to ${ctx.getHeight.toLong - LFSMHelpers.HOLDING_PERIOD + addHeight}")
    for(m <- existingMiners){
      tree.insert(m._1.hashedPropBytes -> (Longs.toByteArray(m._2) ++ Array(0.toByte)))
    }
    println(s"Setting current miners to ${existingMiners.size} and totalScore to ${existingMiners.map(_._2).sum}")
    UTXO(holding, SIXTY_ERG,
      registers = Seq(
        tree.ergoValue,
        ErgoValue.of(existingMiners.size),
        ErgoValue.of(BigInt(existingMiners.map(_._2).sum).bigInteger),
        ErgoValue.of(ctx.getHeight.toLong - LFSMHelpers.HOLDING_PERIOD + addHeight)
      ))
  }

  test("Make Holding box"){
    client.execute{
      ctx: BlockchainContext =>
        val prover = getProver(ctx)
        val input = DUMMY_UTXO.toDummyInput(ctx)
        val output = makeHoldingUTXO(ctx, Seq(), 1)

        val uTx = TxBuilder(ctx)
          .setInputs(input)
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)

        println(s"Size of Holding UTXO with reference: ${output.toDummyInput(ctx).bytes.length}")
        println(prover.sign(uTx).toJson(true))

    }
  }
  test("spend holding box after period"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        tree.insert(insertValuesNoNISP(ctx):_*)
        val holdingInput = makeHoldingUTXO(ctx, getMiners(ctx).zip(SCORE_VALUES), -100L).toDummyInput(ctx)
        val payout = RollupContracts.mkPayoutContract(ctx)
        val eval: Contract =
          RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
        val holding: Contract = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)

        val output = UTXO(eval, SIXTY_ERG,
          registers = Seq(
            tree.ergoValue,
            ErgoValue.of(SCORE_VALUES.size),
            ErgoValue.of(BigInt(TOTAL_SCORE).bigInteger),
            ErgoValue.of(ctx.getHeight.toLong),
            holdingInput.registers(3)
          ))

        val uTx = TxBuilder(ctx)
          .setInputs(holdingInput, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        val sTx = getProver(ctx).sign(uTx)
        println(sTx.toJson(true))
    }

  }

  test("add miner to new holding box"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)

        val holdingInput = makeHoldingUTXO(ctx, Seq(), 100L).toDummyInput(ctx)

        val payout = RollupContracts.mkPayoutContract(ctx)
        val eval: Contract =
          RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
        val holding: Contract = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
        val keyVal = getMiners(ctx).zip(SCORE_VALUES)
          .slice(0, 1)
          .map(x => x._1.hashedPropBytes -> (Longs.toByteArray(x._2) ++ Array(0.toByte)))
        val insertion = tree.insert(
          keyVal:_*
        )
        val inputWithContext = holdingInput.setCtxVars(
          ContextVar.of(0.toByte, getProverContract(ctx).sigmaBoolean.get),
          ContextVar.of(
            1.toByte,
            ErgoValue.pairOf(
              ErgoValue.ofColl(Colls.fromArray(keyVal.head._1), ErgoType.byteType()),
              ErgoValue.ofColl(Colls.fromArray(keyVal.head._2), ErgoType.byteType())
            )
          ),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
        val output = UTXO(holding, SIXTY_ERG,
          registers = Seq(
            tree.ergoValue,
            ErgoValue.of(1),
            ErgoValue.of(BigInt(Longs.fromByteArray(keyVal.head._2.slice(0, 8))).bigInteger),
            ErgoValue.of(ctx.getHeight.toLong - LFSMHelpers.HOLDING_PERIOD + 100)
          ))

        val uTx = TxBuilder(ctx)
          .setInputs(inputWithContext, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        val sTx = getProver(ctx).sign(uTx)
        println(sTx.toJson(true))
    }

  }


  test("add miner to existing holding box"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)

        val holdingInput = makeHoldingUTXO(ctx, getMiners(ctx).zip(SCORE_VALUES).slice(1,3), 100L).toDummyInput(ctx)

        val payout = RollupContracts.mkPayoutContract(ctx)
        val eval: Contract =
          RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
        val holding: Contract = RollupContracts.mkHoldingContract(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)

        val existingKeyVal = getMiners(ctx).zip(SCORE_VALUES)
          .slice(1,3)
          .map(x => x._1.hashedPropBytes -> (Longs.toByteArray(x._2) ++ Array(0.toByte)))
        tree.insert(existingKeyVal:_*)
        val newKeyVal = getMiners(ctx).zip(SCORE_VALUES)
          .slice(0, 1)
          .map(x => x._1.hashedPropBytes -> (Longs.toByteArray(x._2) ++ Array(0.toByte)))
        val copy = tree.copy()
        copy.prover.generateProof()
        println(s"Copy Digest: ${copy.toString()} Real Digest: ${tree.toString()}")

        val insertion = tree.insert(
          newKeyVal:_*
        )

        val insertion2 = copy.insert(
          newKeyVal:_*
        )

        println(s"Copy Digest: ${copy.toString()} Real Digest: ${tree.toString()}")
        println(insertion2.proof.bytes sameElements insertion.proof.bytes)
        println("Real Insertion: " + Hex.toHexString(insertion.proof.bytes))
        println("Copy Insertion: " + Hex.toHexString(insertion2.proof.bytes))

        val inputWithContext = holdingInput.setCtxVars(
          ContextVar.of(0.toByte, getProverContract(ctx).sigmaBoolean.get),
          ContextVar.of(
            1.toByte,
            ErgoValue.pairOf(
              ErgoValue.ofColl(Colls.fromArray(newKeyVal.head._1), ErgoType.byteType()),
              ErgoValue.ofColl(Colls.fromArray(newKeyVal.head._2), ErgoType.byteType())
            )
          ),
          ContextVar.of(2.toByte, insertion2.proof.ergoValue)
        )
        val output = UTXO(holding, SIXTY_ERG,
          registers = Seq(
            copy.ergoValue,
            ErgoValue.of(3),
            ErgoValue.of(BigInt(
              Longs.fromByteArray(newKeyVal.head._2.slice(0, 8)) + SCORE_VALUES.slice(1,3).sum).bigInteger
            ),
            ErgoValue.of(ctx.getHeight.toLong - LFSMHelpers.HOLDING_PERIOD + 100)
          ))

        val uTx = TxBuilder(ctx)
          .setInputs(inputWithContext, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        val sTx = getProver(ctx).sign(uTx)
        println(sTx.toJson(true))
    }

  }

}
