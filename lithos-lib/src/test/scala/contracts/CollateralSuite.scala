package contracts

import contracts.ContractTestHelpers.{client, localClient}
import lfsm.LFSMHelpers
import lfsm.collateral.CollateralContract
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit._
import org.json.JSONObject
import org.scalatest.funsuite.AnyFunSuite
import sigma.{Colls, SigmaProp}
import sigma.data.{CSigmaProp, ProveDHTuple, ProveDlog, SigmaBoolean, SigmaConjecture, SigmaLeaf, TrivialProp}
import sigma.serialization.GroupElementSerializer
import work.lithos.mutations._

import java.math.BigInteger
import scala.util.Try

class CollateralSuite extends AnyFunSuite{

  test("Compile collateral contract"){
    client.execute{
      ctx =>
        val collateral: Contract = CollateralContract.mkTestnetCollatContract(ctx, Contract.SIGMA_TRUE.hashedPropBytes)
        println(collateral.mainnetAddress)
        println(collateral.testnetAddress)

    }
  }

  test("Make collateral box"){
    client.execute{
      ctx =>
        val prover = ctx.newProverBuilder().withMnemonic(SecretString.create("Put Secret Key Here :)"),
          SecretString.empty()).withEip3Secret(0).build()

        println(prover.getAddress)
        val collateral: Contract = CollateralContract.mkTestnetCollatContract(ctx, Contract.SIGMA_TRUE.hashedPropBytes)
        val inputs = ctx.getBoxesById("5a3f8a958178fc6e3b37aeea8fb94d8e6d33a7e4d2c7e70aa7db4e13c08a9903", "33f52c7df5a518cbe4269862728e763ef7970b398977c9b1554716e1af2aa447")
        val output = UTXO(collateral, ((67.5 * Parameters.OneErg) - (Parameters.MinFee * 100)).toLong, registers = Seq(
          ErgoValue.of(0L),
          ErgoValue.of(prover.getAddress.getPublicKey) // Use default prover address as this is what is used to mine by the node
        ))

        val uTx = TxBuilder(ctx)
          .setInputs(inputs.map(InputUTXO.apply(_)).toSeq :_*)
          .setOutputs(output)
          .buildTx(Parameters.MinFee, Address.create("3WxKZGZBdGsfBMhajuVHkqRtqS5NEDPUA23hmECHBMygZChBiPpA"))

        val sTx = prover.sign(uTx)

        //val txId = ctx.sendTransaction(sTx)
        println(sTx.toJson(true))
    }
  }



  test("Pay Collateral No Change"){
    client.execute{
      ctx =>
        val collateral: Contract = CollateralContract.mkTestnetCollatContract(ctx, Contract.SIGMA_TRUE.hashedPropBytes)
        val feeValue: Long = 0L
        val lender   = Address.create("9exChj86cfLEV1A3fuGYo4ciBVgHaDHU1UnyHrdBvvtVYFUgUxn")
        val lenderPk = lender.getPublicKey
        val currentBlockReward = CollateralContract.coinsToIssue(ctx.getHeight)
        val collateralUTXO = UTXO(
          collateral,
          currentBlockReward - feeValue,
          registers = Seq(
            ErgoValue.of(0L),
            ErgoValue.of(lenderPk)
          )
        ).toDummyInput(ctx)

        val fakeEmissionUTXO = UTXO(
          Contract.fromAddressString("2Z4YBkDsDvQj8BX7xiySFewjitqp2ge9c99jfes2whbtKitZTxdBYqbrVZUvZvKv6aqn9by4kp3LE1c26LCyosFnVnm6b6U1JYvWpYmL2ZnixJbXLjWAWuBThV1D6dLpqZJYQHYDznJCk49g5TUiS4q8khpag2aNmHwREV7JSsypHdHLgJT7MGaw51aJfNubyzSKxZ4AJXFS27EfXwyCLzW1K6GVqwkJtCoPvrcLqmqwacAWJPkmh78nke9H4oT88XmSbRt2n9aWZjosiZCafZ4osUDxmZcc5QVEeTWn8drSraY3eFKe8Mu9MSCcVU"),
          1000000L * Parameters.OneErg
        ).toDummyInput(ctx)

        val sigTrue = UTXO(
          Contract.SIGMA_TRUE,
          currentBlockReward - feeValue
        )

        val txB = TxBuilder(ctx)
        val uTx = txB
          .setInputs(collateralUTXO)
          .setDataInputs(fakeEmissionUTXO)
          .setOutputs(sigTrue)
          .setPreHeader(ctx.createPreHeader().minerPk(lender.getPublicKeyGE).build())
          .buildTx(0, lender)

        val dummyProver = ctx.newProverBuilder().withDLogSecret(BigInteger.ZERO).build()
        println("CoinsToIssue: " + currentBlockReward)
        val sTx = dummyProver.sign(uTx)
        println(sTx.toJson(true, true))
    }

  }

//  test("Pay Collateral With Change"){
//    client.execute{
//      ctx =>
//        val collateral: Contract = CollateralContract.mkOfflineTestCollatContract(ctx)
//        val feeValue: Long = Parameters.MinFee * 100
//        val lender   = Address.create("9exChj86cfLEV1A3fuGYo4ciBVgHaDHU1UnyHrdBvvtVYFUgUxn")
//        val lenderPk = lender.getPublicKey
//        val currentBlockReward = CollateralContract.coinsToIssue(ctx.getHeight)
//        val collateralUTXO = UTXO(
//          collateral,
//          currentBlockReward + Parameters.OneErg * 5,
//          registers = Seq(
//            ErgoValueBuilder.buildFor(feeValue),
//            ErgoValue.of(lenderPk)
//          )
//        ).toDummyInput(ctx)
//
//        val fakeEmissionUTXO = UTXO(
//          Contract.fromAddressString("2Z4YBkDsDvQj8BX7xiySFewjitqp2ge9c99jfes2whbtKitZTxdBYqbrVZUvZvKv6aqn9by4kp3LE1c26LCyosFnVnm6b6U1JYvWpYmL2ZnixJbXLjWAWuBThV1D6dLpqZJYQHYDznJCk49g5TUiS4q8khpag2aNmHwREV7JSsypHdHLgJT7MGaw51aJfNubyzSKxZ4AJXFS27EfXwyCLzW1K6GVqwkJtCoPvrcLqmqwacAWJPkmh78nke9H4oT88XmSbRt2n9aWZjosiZCafZ4osUDxmZcc5QVEeTWn8drSraY3eFKe8Mu9MSCcVU"),
//          1000000L * Parameters.OneErg,
//          tokens = Seq(Token(emissionId, 1)),
//          registers = Seq(ErgoValueBuilder.buildFor(Colls.fromArray(collateralUTXO.id.getBytes)))
//        ).toDummyInput(ctx)
//
//        val sigTrue = UTXO(
//          ScriptGenerator.mkSigTrue(ctx),
//          currentBlockReward - feeValue - Parameters.MinFee,
//          registers = Seq(ErgoValueBuilder.buildFor(Colls.fromArray(collateralUTXO.id.getBytes)))
//        )
//
//        val changeBox = UTXO(
//          Contract.fromAddress(lender),
//          Parameters.OneErg * 5 + feeValue
//        )
//
//        val txB = TxBuilder(ctx)
//        val uTx = txB
//          .setInputs(collateralUTXO)
//          .setDataInputs(fakeEmissionUTXO)
//          .setOutputs(sigTrue, changeBox)
//          .setPreHeader(ctx.createPreHeader().minerPk(lender.getPublicKeyGE).build())
//          .buildTx(Parameters.MinFee, lender)
//
//        val dummyProver = ctx.newProverBuilder().withDLogSecret(BigInteger.ZERO).build()
//        println("CoinsToIssue: " + currentBlockReward)
//        val sTx = dummyProver.sign(uTx)
//        println(sTx.toJson(true, true))
//    }
//
//  }
}
