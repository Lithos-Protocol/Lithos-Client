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
    "eval" -> "082a34d4b781a686e5aeb2af2b88d8e5ef2d3253c143dcac2c1b0a3cbeae972a",
    "holding" -> "60e8e611ea08c1927efa535e637c7a6b15ce24a6701a7888e79889c2b075db61",
    "holdingLogic" -> "d12396a9bf87b82f9ff62fd0d73d18738d4d1f6fc25b51103a811cba9b0d340a",
    "gate" -> "806c5508e54398504bc1952f4162405d5cc55a8b46743150418d6cdd3d260e98",
    "collateral" -> "62817f1c20f3bca3a79c93a6a3954fefd7fef808fa58483a8b125f679e8db6ce",
    "emission" -> "96c18431499e25dbdfaec808a93cc0565a262a2607c248c760005670c75d7970",
    "emissionGuard" -> "abdb737514b05ce73a6252f323ede02e33c6cc35e6067980a2864bb53e15b195",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "1bfa1547c4457b05ec7b6a8aef64e6046b3268ca8c5d743ea4acb926bfc0b29c",
    "minerData" -> "05f291b5381faf855efd4c4c3cd4113d1513cc12a3f9a8c6973d59f6899274cb",
    "minerDataLogic" -> "a1bfe7bd44c019b82554dd5a2481d912e1d16e78cfcb643b226196c00afd827d",
    "fpNonMatchingCommitment" -> "4ea3d6ebe342e006158f4f7620da7d4a4ecf9eaa3981551c98fd84cf73e100f6",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "ede1e0b08e44c39618c9e413b0ade3e8ba1aba6c2b3734910a8f3a05bf9769b5")

  private val mainnet: Map[String, String] = Map(
    "payout" -> "f5c2fd04b3a95d2767c137389359be6854972126d9f0e53104e3684778655f51",
    "eval" -> "6170156717e706f6b3cd53d4ed908e9b9b4736aa09103eadc4dcddceb9108d27",
    "holding" -> "33687e21eecf755211e104b7346b594ccb171c149a501116efc5af9effae0a55",
    "holdingLogic" -> "28f8083002a1bb352b0a1b843d0f890da168a8e70d278af230ba1ef6d1ae5eff",
    "gate" -> "b4c11d0e04ff78de8d302c19cbc740ff7e88068aaf6db6c85a0d9769468057d8",
    "collateral" -> "8b46196f81955540d84d8900c428cdeb86046f845893611ede69e80a6fb42dcd",
    "emission" -> "587e3f8eaf2234dd9c9efd5bedf08c771e4e796fdad30ca11e25c3209f2d9490",
    "emissionGuard" -> "dc602a1c259f6399fb07296e9a26b368fd89d30e3f4cc71ce6e15af63a566eea",
    "enforcer" -> "e0c4bbd53e5f1ca793f55a0f695a8b9ca3299ddc57980eb16903848fd8f2df1f",
    "minerDictionary" -> "710e3d3cb043bafb67e87cd1498a4ad04c7281b252e39e941bae987cf30d24b3",
    "minerData" -> "c12bb70eb55435182861614223ec023a6cf1a7bda6dc7ce017184bd3c8997c1e",
    "minerDataLogic" -> "f4a96a6a7619641f4cb49fc06c1b3b70bf023234e9c56750b78e342dcfe1a96e",
    "fpNonMatchingCommitment" -> "38e1a375b096c8bd22c2c75d9ab4919a96b213cd601896f4a5ac53e7d05009fd",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "1fc1db29651fc27d88659cf3c4aacbfe89b8e7e725dc17d6aaaa1c3879bfb8b8")

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
