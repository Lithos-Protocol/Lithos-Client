package contracts

import contracts.ContractTestHelpers._
import lfsm.LFSMHelpers
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.scalatest.funsuite.AnyFunSuite
import sigma.data.AvlTreeFlags
import sigma.exceptions.InterpreterException
import sigma.{Coll, Colls}
import work.lithos.mutations._
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

class EvalSuite extends AnyFunSuite{




  test("Compile eval contract"){

    client.execute{
      ctx =>
        val payout = RollupContracts.mkPayoutContract(ctx)
        val eval: Contract =
          RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
        println(eval.mainnetAddress)
        println(eval.testnetAddress)
        println(Hex.toHexString(eval.propBytes))
    }
  }



  def makeEvalUTXO(ctx: BlockchainContext, addHeight: Long) = {
    val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
    println(Hex.toHexString(tree.digest))
    tree.insert(insertValuesNoNISP(ctx):_*)
    println(Hex.toHexString(tree.digest))

    val payout = RollupContracts.mkPayoutContract(ctx)
    val eval: Contract =
      RollupContracts.mkEvalContract(ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, dummyId)
    println(s"Setting period start to ${ctx.getHeight.toLong - LFSMHelpers.EVAL_PERIOD + addHeight}")
    UTXO(eval, SIXTY_ERG,
      registers = Seq(
        tree.ergoValue,
        ErgoValue.of(SCORE_VALUES.size),
        ErgoValue.of(BigInt(TOTAL_SCORE).bigInteger),
        ErgoValue.of(ctx.getHeight.toLong - LFSMHelpers.EVAL_PERIOD + addHeight)
      ))
  }

  test("Make Eval box"){
    client.execute{
      ctx: BlockchainContext =>
        val prover = getProver(ctx)
        val input = DUMMY_UTXO.toDummyInput(ctx)
        val output = makeEvalUTXO(ctx, 1)

        val uTx = TxBuilder(ctx)
          .setInputs(input)
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)

        println(s"Size of Eval UTXO with reference: ${output.toDummyInput(ctx).bytes.length}")
        prover.sign(uTx)

    }
  }

  test("spend eval box after period"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        tree.insert(insertValuesNoNISP(ctx):_*)
        val evalInput = makeEvalUTXO(ctx, -100L).toDummyInput(ctx)

        val payout: Contract = RollupContracts.mkPayoutContract(ctx)

        val output = UTXO(payout, SIXTY_ERG,
          registers = Seq(
            tree.ergoValue,
            ErgoValue.of(SCORE_VALUES.size),
            ErgoValue.of(BigInt(TOTAL_SCORE).bigInteger),
            ErgoValue.of(SIXTY_ERG)
          ))

        val uTx = TxBuilder(ctx)
          .setInputs(evalInput, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        val sTx = getProver(ctx).sign(uTx)
        println(sTx.toJson(true))
    }

  }
  test("cant spend eval box before period"){
    client.execute{
      ctx =>

        val tree = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
        tree.insert(insertValuesNoNISP(ctx):_*)
        val evalInput = makeEvalUTXO(ctx, 200L).toDummyInput(ctx)

        val payout: Contract = RollupContracts.mkPayoutContract(ctx)

        val output = UTXO(payout, SIXTY_ERG,
          registers = Seq(
            tree.ergoValue,
            ErgoValue.of(SCORE_VALUES.size),
            ErgoValue.of(BigInt(TOTAL_SCORE).bigInteger),
            ErgoValue.of(SIXTY_ERG)
          ))

        val uTx = TxBuilder(ctx)
          .setInputs(evalInput, DUMMY_UTXO.toDummyInput(ctx))
          .setOutputs(output)
          .buildTx(Parameters.MinFee, CHANGE_ADDRESS)
        assertThrows[InterpreterException](getProver(ctx).sign(uTx))

    }

  }
}
