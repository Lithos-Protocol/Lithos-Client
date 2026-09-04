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
    "payout" -> "12ab7ba7565d2628374e260cfdf4481bb9d9083b226c7b3378ec6a4b85f7f923",
    "eval" -> "0d7b5aca9ee71f640b03de90c1f27fe8e3875a35767d502ca5c16ca4e8d06157",
    "holding" -> "dd4b44212a8d971eb3ab7c10256004c95e6beb9a073821e06dac1d45416eb515",
    "holdingLogic" -> "e543d5abed9e9e6a2945edcaa18e6472de05928adb6c62a416ce2ff758f35bd5",
    "gate" -> "2db6a0360f8010ec7b18e3450162c4357f7e35b170b3f5e9b38f1764ff7282e4",
    "collateral" -> "42ccac373eed1435803f5f754a033e20da4c00b84479153218dd7a662d675331",
    "emission" -> "2f6a5d44f4ff25041446fe5f546c97ffe1ecfcec94f71b6259aaf4028510c1fd",
    "emissionGuard" -> "862ea023d740b2b0aeb43d502a245c32cfa316eafdac621e1d76de8f5557836e",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "3492f6c1f8fe87f40733561906f3ebabab471d02928537d1ccfe7a7fdd75808c",
    "minerData" -> "467560c2a396b6b3d113682a918a0f0843aeb8cdcfe580276ed0292676c01cd4",
    "minerDataLogic" -> "23ce34a6d7bebec7489d0999a50b499fae94d96bc4beb1756804843fb3ba4e3f",
    "fpNonMatchingCommitment" -> "19ba40d84149d8bab3f9ff875b593eee56318d1c9ae61616e0497a034ec2abb6",
    "fpInvalidFormat" -> "6ca1a95d2d8c5618c0fed52f16e795e109ed27e31f6a1485a2e162819b5022bf",
    "fpMalformedGE" -> "f379dbc1d43aefaf9ebef79943c51ee523c3543d509b15978abc651fcf970cfe",
    "fpNotInWindow" -> "8db6c6da0df8160dbcf49f97f3a5842ccf64612234896ca1583aa784b0234972",
    "fpNonUniqueHeaders" -> "3f4cf258f4ae1e715baefe3792d245f6f6d04faaad8df1f124ad0453e23f5689",
    "fpIncorrectN" -> "8c15c502e6d796b0e6ea8acc05617b5449e24d98c7c1218946a7f76546386cf6",
    "fpInvalidDiff" -> "f90af6e36ba0639af9b8ffcab41c95a3e5690031143ec7b4633c25c5ac9fc913",
    "fpTransactionNotIncluded" -> "b63bd5373a714e1d6821bf7c1adaaffe878e1df3786699717121c1649e1701df",
    "fpMalformedGenesis" -> "829a382ac269dc5988251d85aea783d206b197b8605f37e961acc74b48e2155a")

  /**
   * Mainnet ids are still placeholders, so these move once the real token ids are set. That is the
   * change this spec exists to make visible.
   */
  private val mainnet: Map[String, String] = Map(
    "payout" -> "12ab7ba7565d2628374e260cfdf4481bb9d9083b226c7b3378ec6a4b85f7f923",
    "eval" -> "ad73f9bbadc73d315d22141e7d8d714ca283c6d85a3fd46a2ff8c2351c928ba9",
    "holding" -> "d3f6cfcde85dc7276ecec20884c94e2e3ecdb04aa131599f4d9dfcf19f464177",
    "holdingLogic" -> "b2ad79487ee8879a147b65270eb7df957b577989b1d7cc5679515315391d8916",
    "gate" -> "2db6a0360f8010ec7b18e3450162c4357f7e35b170b3f5e9b38f1764ff7282e4",
    "collateral" -> "42ccac373eed1435803f5f754a033e20da4c00b84479153218dd7a662d675331",
    "emission" -> "2f6a5d44f4ff25041446fe5f546c97ffe1ecfcec94f71b6259aaf4028510c1fd",
    "emissionGuard" -> "862ea023d740b2b0aeb43d502a245c32cfa316eafdac621e1d76de8f5557836e",
    "enforcer" -> "0c4473b46f52bb122f33ec1d25e1e963cd7ddada0559f970f93a2401b0f94d52",
    "minerDictionary" -> "73c823cb4e884a7abb2f89e5334368119d7484eea9fdf733881b6e41608a504c",
    "minerData" -> "5c642d854b48610fb86542616dea5530ea52e586f19d368038fffb65e396e00b",
    "minerDataLogic" -> "66902d2629e07c67d5b14789321859afbd716d710bc49a12ee566dd44a5134bd",
    "fpNonMatchingCommitment" -> "0ea268ac57dc6e9c3c7906036b261368b4a8d55db0f692735f26116ee225912c",
    "fpInvalidFormat" -> "6ca1a95d2d8c5618c0fed52f16e795e109ed27e31f6a1485a2e162819b5022bf",
    "fpMalformedGE" -> "f379dbc1d43aefaf9ebef79943c51ee523c3543d509b15978abc651fcf970cfe",
    "fpNotInWindow" -> "8db6c6da0df8160dbcf49f97f3a5842ccf64612234896ca1583aa784b0234972",
    "fpNonUniqueHeaders" -> "3f4cf258f4ae1e715baefe3792d245f6f6d04faaad8df1f124ad0453e23f5689",
    "fpIncorrectN" -> "8c15c502e6d796b0e6ea8acc05617b5449e24d98c7c1218946a7f76546386cf6",
    "fpInvalidDiff" -> "f90af6e36ba0639af9b8ffcab41c95a3e5690031143ec7b4633c25c5ac9fc913",
    "fpTransactionNotIncluded" -> "b63bd5373a714e1d6821bf7c1adaaffe878e1df3786699717121c1649e1701df",
    "fpMalformedGenesis" -> "7f7127385f57f97d0480f0ffb9beda1f021738e87cccff240bab68d3d2342d2d")

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
