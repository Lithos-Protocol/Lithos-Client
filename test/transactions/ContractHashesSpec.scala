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
    "eval" -> "428e2f1699dc0b345f3dbe8c3ae702076af7fffcdd10262ad39783354c9de3bd",
    "holding" -> "46445f68144c364a9f3fe8afd7580748e2d997e130f4963ed07eb6a52cd3ef56",
    "holdingLogic" -> "a4f9c9d0ca72ea2d51f1209f94476180a2a5078553f4c45d9d4f0e1590e5f259",
    "gate" -> "6dcf188e8f2db1597fbf83469386bdafb00785257145ac2b332e19254458709e",
    "collateral" -> "a69414359a096b110d1eed239bb567063c9bd2bc231b39e1946d209bbd1d45fd",
    "emission" -> "77efeea52664a7e4dfa95bc8e7fdc848c3d7432ffce43c880c7ca00be3175b1c",
    "emissionGuard" -> "1b729e2bbf73cc97f914bae64d8414649a64bcaf135b5b3a469865133a16aee3",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "4765846215cbe5a4bfd3a98faaa2bdd4432acadf640fc614877f1451e7c5208b",
    "minerData" -> "1e5ec661626db7f749dbe9e539b0c6d1e1e920511f608a399510fb049d637e3d",
    "minerDataLogic" -> "853864bbc75d5e8b75f204e75fab87b82e0817fbde8354e59d61cd3bea6c0528",
    "fpNonMatchingCommitment" -> "51c4132791e7bdc46d7900879b74d9af8c53eaa1d6cfdefe791f4f9516594d78",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "fa479fb285bbf670f91af78489382c6373fb4498ccc0bf11dfbdbc8918b704f9")

  /**
   * Mainnet ids are still placeholders, so these move once the real token ids are set. That is the
   * change this spec exists to make visible.
   */
  private val mainnet: Map[String, String] = Map(
    "payout" -> "f5c2fd04b3a95d2767c137389359be6854972126d9f0e53104e3684778655f51",
    "eval" -> "e1c90aca561b83b57d113564a82407c9853256c35e51ded1bd5e6dcec13fedb1",
    "holding" -> "6af1839c5959a98edf4cb9a6dc58dc881ffb3b6392adfc6763750426c6ca0158",
    "holdingLogic" -> "c4d94317e20eba53f32704adb87906115f03fbafcbf646c92a5307c1189327d1",
    "gate" -> "6dcf188e8f2db1597fbf83469386bdafb00785257145ac2b332e19254458709e",
    "collateral" -> "a69414359a096b110d1eed239bb567063c9bd2bc231b39e1946d209bbd1d45fd",
    "emission" -> "77efeea52664a7e4dfa95bc8e7fdc848c3d7432ffce43c880c7ca00be3175b1c",
    "emissionGuard" -> "1b729e2bbf73cc97f914bae64d8414649a64bcaf135b5b3a469865133a16aee3",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
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
    "fpMalformedGenesis" -> "d8cf8b1b0985bf6f28472cfac9e7fa3c41a7a55fba32e9a9a821f9cfda0580a3")

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
