package contracts.specs.arbitrage

import arbitrage.contracts.ArbStakeContract
import contracts.specs.harness.ContractSpecBase
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{NetworkType, Parameters}
import org.scalatest.propspec.AnyPropSpec
import sigma.ast.{ErgoTree, SigmaPropConstant}
import work.lithos.mutations.{Contract, TxBuilder, UTXO}

/**
 * The arb stake script, pinned rather than compiled. A miner's stake is found by reading the address this tree
 * gives its key, so the tree a key gets must never move, must never be the key's own plain address, and must be
 * spendable by that key and no other.
 */
class ArbStakeContractSpec extends AnyPropSpec with ContractSpecBase {

  /**
   * The only place the script is compiled. A difference means every stake already sitting at an arb address is
   * at a script this build no longer reads, so re-pinning is a decision about those boxes, not a fix.
   */
  property("pin: the tree is exactly what ArbStake.ergo compiles to") {
    withCtx { _ =>
      Hex.toHexString(ArbStakeContract.compileSource().bytes) shouldBe ArbStakeContract.PinnedTreeHex
    }
  }

  property("a key's stake tree is the pinned tree with that key as its one changed constant") {
    withCtx { ctx =>
      val owner = miner(ctx).getAddress.getPublicKey
      val pinned = ErgoTree.fromHex(ArbStakeContract.PinnedTreeHex)
      val tree = ArbStakeContract.forKey(owner).ergoTree
      tree.header shouldBe pinned.header
      tree.template shouldEqual pinned.template
      tree.constants.size shouldBe pinned.constants.size
      tree.constants.head shouldBe SigmaPropConstant(owner)
      tree.constants.tail shouldBe pinned.constants.tail
      // What the node hashes for the box's address: the bytes parse back to themselves
      Hex.toHexString(ErgoTree.fromHex(Hex.toHexString(tree.bytes)).bytes) shouldBe Hex.toHexString(tree.bytes)
    }
  }

  property("a stake tree is never its owner's plain address, and two keys never share one") {
    withCtx { ctx =>
      val owner = miner(ctx)
      val other = proverWith(ctx, otherSecret)
      val stake = ArbStakeContract.forKey(owner.getAddress.getPublicKey)
      val plain = contractOf(owner)
      stake.ergoTreeHex should not be plain.ergoTreeHex
      stake.address(NetworkType.MAINNET).toString should not be owner.getAddress.toString
      stake.address(NetworkType.MAINNET).isP2PK shouldBe false
      stake.ergoTreeHex should not be ArbStakeContract.forKey(other.getAddress.getPublicKey).ergoTreeHex
    }
  }

  property("the owner is read back from a stake tree, and from nothing else") {
    withCtx { ctx =>
      val owner = miner(ctx)
      val key = Hex.toHexString(owner.getAddress.getPublicKey.pkBytes)
      val stake = ArbStakeContract.forKey(owner.getAddress.getPublicKey).ergoTreeHex
      val plain = contractOf(owner).ergoTreeHex
      ArbStakeContract.ownerOf(stake) shouldBe Some(key)
      ArbStakeContract.ownerP2PK(stake) shouldBe Some(plain)
      ArbStakeContract.forP2PK(plain) shouldBe Some(stake)
      ArbStakeContract.isStake(plain) shouldBe false
      ArbStakeContract.isStake(Contract.SIGMA_TRUE.ergoTreeHex) shouldBe false
      // One changed byte outside the key, and the same length with another script's bytes
      ArbStakeContract.isStake(stake.updated(stake.length - 1, '0')) shouldBe false
      ArbStakeContract.isStake("00" + stake.drop(2)) shouldBe false
      ArbStakeContract.forP2PK(stake) shouldBe None
      an[IllegalArgumentException] should be thrownBy ArbStakeContract.treeHexFor("04" + key.drop(2))
    }
  }

  property("only the owner's key spends a stake box") {
    withCtx { ctx =>
      val owner = miner(ctx)
      val stranger = proverWith(ctx, otherSecret)
      val stake = ArbStakeContract.forKey(owner.getAddress.getPublicKey)
      val input = inputAt(UTXO(stake, 10 * Parameters.OneErg), ctx, 0)
      def spend = TxBuilder(ctx).setInputs(input)
        .setOutputs(UTXO(contractOf(stranger), 10 * Parameters.OneErg - Parameters.MinFee), UTXO.feeBox(Parameters.MinFee))
        .buildTx(0, owner.getAddress)
      accepts(owner, spend)
      rejects(stranger, spend)
    }
  }
}
