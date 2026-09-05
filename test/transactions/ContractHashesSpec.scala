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
    "payout" -> "4e301ac09f27f180c5d7d7452ad324bae9d06cf639dc07081bc831886e27c0e2",
    "eval" -> "34df23183c1df016509f741e4b987d58306097ecd0991f7de62f8c305bcf3b81",
    "holding" -> "0f9cc0028494359d835371e7c823b3e6c62b3f18b5e6aa6111c6572c574b43f7",
    "holdingLogic" -> "808d98ae24f09fb69af0254e4e1f96d0e8dae143fa58288e53dbd1e1e4cfe535",
    "gate" -> "6dcf188e8f2db1597fbf83469386bdafb00785257145ac2b332e19254458709e",
    "collateral" -> "a69414359a096b110d1eed239bb567063c9bd2bc231b39e1946d209bbd1d45fd",
    "emission" -> "77efeea52664a7e4dfa95bc8e7fdc848c3d7432ffce43c880c7ca00be3175b1c",
    "emissionGuard" -> "1b729e2bbf73cc97f914bae64d8414649a64bcaf135b5b3a469865133a16aee3",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "6b144f6a6b19e3eca09a0c9ebd969a286424308b439f3633e303ab5ef86e4224",
    "minerData" -> "7ca41a5cde2a77ab0e49bc9364fcb9b51bc68d5bc2aebbad32e26fbee7d97138",
    "minerDataLogic" -> "712e58cd8fcef8629eef74a89a10c57878a6a1b9f2acc881bc6240f90ebb39b6",
    "fpNonMatchingCommitment" -> "a831ae641b9e75ba7a4e84c2cf1f628321a5b31370e7ce53f6836164967391b9",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "d2725acfb8ba23116aa011018a49285f75c35db6eb9781c66acf609dc660aaab")

  /**
   * Mainnet ids are still placeholders, so these move once the real token ids are set. That is the
   * change this spec exists to make visible.
   */
  private val mainnet: Map[String, String] = Map(
    "payout" -> "4e301ac09f27f180c5d7d7452ad324bae9d06cf639dc07081bc831886e27c0e2",
    "eval" -> "91d2343d2cf7a1895597e232280a3b16d884995da1356cd8045ce8b98e0cedb9",
    "holding" -> "264b559124f8038db240775f586b0ccad2b5407640fc8fe3882cd93f586a68fb",
    "holdingLogic" -> "a14070808230940695510aaf102730a4f600e8db057dbadbcd138913eb11b623",
    "gate" -> "6dcf188e8f2db1597fbf83469386bdafb00785257145ac2b332e19254458709e",
    "collateral" -> "a69414359a096b110d1eed239bb567063c9bd2bc231b39e1946d209bbd1d45fd",
    "emission" -> "77efeea52664a7e4dfa95bc8e7fdc848c3d7432ffce43c880c7ca00be3175b1c",
    "emissionGuard" -> "1b729e2bbf73cc97f914bae64d8414649a64bcaf135b5b3a469865133a16aee3",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "761ce101a2a034f755098583f006b861c6ff31519223997408029baaa4491317",
    "minerData" -> "a2fb6a14f49a74dea4fd8470dcbc29625e9b20b82d130151a180edf02752d547",
    "minerDataLogic" -> "c38754910eb1ae25dd3d9e496fb51101f82298f3d10ba05c595e0122d4c5582f",
    "fpNonMatchingCommitment" -> "4f47be3d3decd1c907b061a3e73e951171c1e248979d456f8155198bfab8bce2",
    "fpInvalidFormat" -> "bde8f71188d50558628b1a85e7075dfa592f9bf1b4ec996195aed6a33e1a9022",
    "fpMalformedGE" -> "a431231abf1c26b2d1821cf20abe10565fe7543c24de83a59598b9da047032f1",
    "fpNotInWindow" -> "078c9b1c1e81b0fe83cc4aa8df21efe935948bc12f825e8b8c066e24865ce845",
    "fpNonUniqueHeaders" -> "1be2c4acef617d8831072f3dafce5ef16260dcb5d4791949f7335c74363dbdb4",
    "fpIncorrectN" -> "a5e75aa4c35403def6f8939788e5127f54825f5de36ea993efccfbab4f476ede",
    "fpInvalidDiff" -> "012f277f15829047125d8d5bcd5393fcea35b1a3c4bad321974a2183e0f81f0b",
    "fpTransactionNotIncluded" -> "1b998bb51bad5f4d5d4eba2bb8a6de02013d80ff4139602cec542cc0c103c0bb",
    "fpMalformedGenesis" -> "834cad010d11f3b8eac702da4e625706abba1ee66452008e08ad2fb75009c581")

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
