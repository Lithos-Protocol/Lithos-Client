package transactions

import lfsm.contracts.FraudProofContracts.FraudProofSet
import org.ergoplatform.appkit.NetworkType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import work.lithos.mutations.Contract

/**
 * The hashed prop bytes of every contract this client compiles, per network, against checked-in
 * values.
 *
 * Every other contract test compares this build against this build — the proof set is compiled from
 * the same holding tree the assertions read back, so a changed period, a wrong token id or a sigma
 * release that emits a tree differently moves both sides at once and no test notices. These literals
 * are the only thing outside the build.
 *
 * `FP_CONTROL` whitelists nine proof hashes and is minted once; the emission box and its config bake
 * in the collateral, gate and rollup-holding hashes. A hash that moves after any of those are minted
 * is a hardfork, so it has to move deliberately.
 *
 * A failure here is not necessarily a defect. Update the literal in the same commit that re-mints
 * whatever carried the old one.
 *
 * Revert-checked: raising `EVAL_PERIOD` by one moved eight of the twenty-one hashes on each network
 * — eval, holding and its logic, all three dictionary contracts, and the two proofs that carry them.
 */
class ContractHashesSpec extends AnyFlatSpec with Matchers {

  private def named(set: FraudProofSet, c: CompiledContracts): Seq[(String, Contract)] = Seq(
    "payout" -> c.payout,
    "eval" -> c.eval,
    "holding" -> c.holding,
    "holdingLogic" -> c.holdingLogic,
    "gate" -> c.gate,
    "collateral" -> c.collateral,
    "emission" -> c.emission,
    "emissionGuard" -> c.guard,
    "enforcer" -> c.enforcer,
    "minerDictionary" -> c.minerDictionary,
    "minerData" -> c.minerData,
    "minerDataLogic" -> c.minerDataLogic,
    "fpNonMatchingCommitment" -> set.nonMatchingCommitment,
    "fpInvalidFormat" -> set.invalidFormat,
    "fpMalformedGE" -> set.malformedGE,
    "fpNotInWindow" -> set.notInWindow,
    "fpNonUniqueHeaders" -> set.nonUniqueHeaders,
    "fpIncorrectN" -> set.incorrectN,
    "fpInvalidDiff" -> set.invalidDiff,
    "fpTransactionNotIncluded" -> set.transactionNotIncluded,
    "fpMalformedGenesis" -> set.malformedGenesis)

  private val testnet: Map[String, String] = Map(
    "payout" -> "f5c2fd04b3a95d2767c137389359be6854972126d9f0e53104e3684778655f51",
    "eval" -> "07469e45dfc9b7dd030aac1d5f757d31ac25c17e7a9624207d3ad0d5f40cd789",
    "holding" -> "42d023e08cc52fa8206485358e6f397db94b9f2ca39ea42884c5ec955e73a382",
    "holdingLogic" -> "f214b5d08e367f38fd822ee6a427e94b150e8686790c9928e803db1f2987f43e",
    "gate" -> "8ae4e8effb3d830a6ddb766a46702da7a4216bc065d263c413e58ebfed564cdf",
    "collateral" -> "8b64fb465140108e6a54f872486a6c52cacd923a014332de87a7b688f4a93f48",
    "emission" -> "ce0a4e2736170fe94481903295bfda8e98e729003ec026adb8a0c92e50b91a45",
    "emissionGuard" -> "6961c280448afca211f3719f9cdef64da7c1d3c4e7e7ab6dd6e8646986dc0a8b",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "f73a8788c08e4d432d6510957de0909f38f5f0bf6e842d72ed81c839e7cb0088",
    "minerData" -> "1f6b9b73891ed53215945226ac677e86b70a7d6e4f6b9b8c9e2cd33ac5b22eb0",
    "minerDataLogic" -> "c4cb616c90dad53d6969ea8ad84f6590e7e79031647c3177e6de1584ccadb5f6",
    "fpNonMatchingCommitment" -> "305fa4c7939cdcd0af73eabfe6d0a81f64c711ae0edd9810fbd3de09ae968a58",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "cd93a814df198897351f1837ae525081f761858467f7fff180fd41e8cfeeca6f")

  /**
   * Mainnet ids are still placeholders, so these move once the real token ids are set. That is the
   * change this spec exists to make visible.
   */
  private val mainnet: Map[String, String] = Map(
    "payout" -> "f5c2fd04b3a95d2767c137389359be6854972126d9f0e53104e3684778655f51",
    "eval" -> "e1c90aca561b83b57d113564a82407c9853256c35e51ded1bd5e6dcec13fedb1",
    "holding" -> "6af1839c5959a98edf4cb9a6dc58dc881ffb3b6392adfc6763750426c6ca0158",
    "holdingLogic" -> "c4d94317e20eba53f32704adb87906115f03fbafcbf646c92a5307c1189327d1",
    "gate" -> "bc1ba023a45d5fe7f21d9863cd1127c9c7033f995710b14dd25be93abeea19bf",
    "collateral" -> "e3c39138b463332d3d7b51c26ea67eb11e129840d4678dc11ccc6b74ff4ee075",
    "emission" -> "399df073003b306a077c8a4cb965308f3a67c8acb7b0830ae59850fa39c1d27f",
    "emissionGuard" -> "6a5be64c9d0f614f96660cffab60196aba6d81e99a2fb5d5867cf5c47c05b4d6",
    "enforcer" -> "e0c4bbd53e5f1ca793f55a0f695a8b9ca3299ddc57980eb16903848fd8f2df1f",
    "minerDictionary" -> "cc05b77156604c434dac30b8d6a2fed9800f52afa00f9423761e4efe888ef75e",
    "minerData" -> "86235fde1c23b2b96d9c955a1d343b64aaffcaf82edc76dabe0470536573e751",
    "minerDataLogic" -> "0da4a7d9ee46ae820a758305edda5c32b021d4b905f460f64576e00773c3eb61",
    "fpNonMatchingCommitment" -> "75752dd436b678acbb388af5d97fce98e4101dbf59df355069ca049f01917ef3",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "04be61a7acfc50126e6689d28485bc9d12d5165025ba7ef3aa347efc50e9a9d6")

  private def check(network: NetworkType, expected: Map[String, String]): Unit = {
    val contracts = ProtocolContracts.forNetwork(network)
    val actual = named(contracts.fraudProofs, contracts)

    withClue(s"$network pins ${expected.size} hashes for ${actual.size} contracts; a contract " +
      "added to CompiledContracts is not deployable until it is pinned here: ") {
      actual.map(_._1).toSet shouldEqual expected.keySet
    }

    // Collected rather than asserted one at a time: constants chain, so one edit moves several
    // hashes and a caller updating them wants the whole replacement block at once.
    val q = "\""
    val moved = actual.filter { case (name, c) => c.hashedPropBytesHex != expected(name) }
    if (moved.nonEmpty) {
      val block = moved
        .map { case (name, c) => s"    $q$name$q -> $q${c.hashedPropBytesHex}$q," }
        .mkString("\n")
      fail(s"$network: ${moved.size} of ${actual.size} contract hashes moved — " +
        s"${moved.map(_._1).mkString(", ")}.\nAnything minted against the old hashes (FP_CONTROL, " +
        "the emission box, its config) no longer accepts this build. If the change is deliberate, " +
        s"replace these lines in the same commit that re-mints:\n$block")
    }
  }

  "Testnet contract hashes" should "match the checked-in values" in {
    check(NetworkType.TESTNET, testnet)
  }

  "Mainnet contract hashes" should "match the checked-in values" in {
    check(NetworkType.MAINNET, mainnet)
  }

  /**
   * The pinned set covers every field, so a contract added to either case class fails here rather
   * than reaching a mint unpinned.
   */
  "The pinned set" should "cover every compiled contract" in {
    val contracts = ProtocolContracts.forNetwork(NetworkType.TESTNET)
    withClue("CompiledContracts gained or lost a field: ") {
      contracts.productArity shouldEqual 12
    }
    withClue("the fraud-proof set gained or lost a proof: ") {
      contracts.fraudProofs.ordered.size shouldEqual 9
    }
    // Twelve fields minus holdingScripts and fraudProofs, plus the guard and logic that pair
    // unpacks and the nine proofs the set holds.
    named(contracts.fraudProofs, contracts) should have size 21
  }
}
