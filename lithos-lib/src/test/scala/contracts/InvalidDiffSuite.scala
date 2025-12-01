package contracts

import contracts.ContractTestHelpers._
import lfsm.fraudproofs.FraudProofContracts
import org.scalatest.funsuite.AnyFunSuite

class InvalidDiffSuite extends AnyFunSuite{




  test("Compile invalid diff contract"){

    client.execute{
      ctx =>
        val invalidDiff = FraudProofContracts.mkInvalidDiffContract(ctx)
        println(invalidDiff.address(ctx.getNetworkType))
    }
  }

}
