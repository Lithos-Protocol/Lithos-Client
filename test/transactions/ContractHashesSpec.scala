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
    "eval" -> "dfbfe2461a70d77b89efd8b5c8e0b4211a7700f9d63f36980e55d54cc66c8d84",
    "holding" -> "7886dfb5f8abed30520e2275225284a370263f42215cc86328bdb90b6192611e",
    "holdingLogic" -> "aabb8021fe069e119317fee69c031348e50d0921c9844b0128b1bf912e0d4660",
    "gate" -> "dda22e579776ea8fc38008259b7fddfe562a9f4db4283c795f86010548d3981b",
    "collateral" -> "aa9c56a23c0a128cbdcbd7ca89d0dfab894c9143ca72420eafd65c6e00b6e3df",
    "emission" -> "02bce974645238eb88e9fa6f11b729973d2bc36c1c684f2470421aa86dbaa23c",
    "emissionGuard" -> "a48a351c5fc21456435495b982ebeaa458cfb815db06fa3de49ed9e77aaac5ea",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "74a0a911038b05454deba8e7e9f3deec6fc51c2172d18bdfb3e668be9ce81b6f",
    "minerData" -> "7dbcb73308ce52d1a3c619c90d06be7889aa7fb7aacf9402a6737b42edc6ea9c",
    "minerDataLogic" -> "cf8e8429cf0170156ce29af5468e2aad15ccf14fba32a4c87aa1341b0e2a2952",
    "fpNonMatchingCommitment" -> "c2baeb398c8502fdf65c5ff0b396db65c485d619d50706d85d9f3bb54a47942b",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "553a742b48d44379e248c7de4a1787156d91dc57b06ae88747b5579763f6fbc8")

  /**
   * Mainnet ids are still placeholders, so these move once the real token ids are set. That is the
   * change this spec exists to make visible.
   */
  private val mainnet: Map[String, String] = Map(
    "payout" -> "f5c2fd04b3a95d2767c137389359be6854972126d9f0e53104e3684778655f51",
    "eval" -> "e1c90aca561b83b57d113564a82407c9853256c35e51ded1bd5e6dcec13fedb1",
    "holding" -> "6af1839c5959a98edf4cb9a6dc58dc881ffb3b6392adfc6763750426c6ca0158",
    "holdingLogic" -> "c4d94317e20eba53f32704adb87906115f03fbafcbf646c92a5307c1189327d1",
    "gate" -> "dda22e579776ea8fc38008259b7fddfe562a9f4db4283c795f86010548d3981b",
    "collateral" -> "b7d507188824bae3e7c9d4ece04166337818c799d3060f7ba47c6841386a911b",
    "emission" -> "a6f028b7f99e7f734a6059651ebda1baff96877d3a988f6c47a6b8bd91f58e36",
    "emissionGuard" -> "b55f8f32d70d571a6228586a67d0e2117106c29239ee2370aa72be99787c0686",
    "enforcer" -> "e0c4bbd53e5f1ca793f55a0f695a8b9ca3299ddc57980eb16903848fd8f2df1f",
    "minerDictionary" -> "0a5769b4f274930c19189b39d31e3e29818099a71aec0412217e28560c0d3759",
    "minerData" -> "cfe4a19aa8f61132e29865062c7d80c19d58a11a1280a77df73aeaccf6bc89f6",
    "minerDataLogic" -> "f22fc627af52798728299c8b200eb8ed4da24792a88103351d0f2f5f548be6b2",
    "fpNonMatchingCommitment" -> "9e9074d5c5c978de66c5372c8816cafe44a7382b9a3c5b0679c023a6bfe689cd",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "39cb04b1a89e5bbfc370b1e146cc30eafa6dbce1dfd3c693372060fb922794b4")

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
