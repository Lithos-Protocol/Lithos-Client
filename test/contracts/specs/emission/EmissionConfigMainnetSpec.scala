package contracts.specs.emission

import lfsm.LFSMHelpers
import lfsm.contracts.CollateralContract
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

object EmissionConfigMainnetSpec {

  /** A token id standing in for the voting token, distinct from every id the harness uses. */
  val voteTokenId: ErgoId =
    ErgoId.create("2222222222222222222222222222222222222222222222222222222222222222")

  private var cache: Option[Contract] = None

  def compiled(ctx: BlockchainContext): Contract = synchronized {
    cache.getOrElse {
      val c = CollateralContract.mkMainnetEmConfigContract(ctx.getNetworkType, voteTokenId)
      cache = Some(c)
      c
    }
  }
}

/**
 * Emission_Config_Mainnet.ergo — one property per named condition in the contract.
 *
 * One spending path, `sigmaProp(allOf(Coll(quorum, nftKept)))`:
 *
 *   quorum   the inputs carry at least 3 of the 5 voting tokens, summed by amount across every input
 *   nftKept  OUTPUTS(0) carries the config NFT as its first token
 *
 * Nothing else is constrained. The successor's script and registers are the voters' choice, which is
 * what lets a vote hand the config to a DAO.
 */
class EmissionConfigMainnetSpec extends AnyPropSpec with EmissionSpecBase {

  import EmissionConfigMainnetSpec.voteTokenId

  private val voterSecrets: Seq[BigInteger] = (7001L to 7005L).map(BigInteger.valueOf)

  /** One prover holding the keys of the given voters, so a single `sign` can spend all their boxes. */
  private def voters(ctx: BlockchainContext, secrets: Seq[BigInteger]): ErgoProver =
    secrets.foldLeft(ctx.newProverBuilder())((b, s) => b.withDLogSecret(s)).build()

  private def voterContract(ctx: BlockchainContext, secret: BigInteger): Contract =
    contractOf(proverWith(ctx, secret))

  private def mainnetConfig(ctx: BlockchainContext): Contract = EmissionConfigMainnetSpec.compiled(ctx)

  private def config(ctx: BlockchainContext): UTXO =
    configBox(ctx, enforcerScript(ctx)).setContract(mainnetConfig(ctx))

  /** A voter's box: 1 ERG, which also funds the transaction, and `votes` voting tokens. */
  private def voteIn(ctx: BlockchainContext, secret: BigInteger, votes: Long, index: Int,
                     tokenId: ErgoId = voteTokenId): InputUTXO =
    inputAt(UTXO(voterContract(ctx, secret), Parameters.OneErg, Seq(Token(tokenId, votes))), ctx, index)

  /** The first `n` voters, one vote each, at inputs 1..n. */
  private def votesFrom(ctx: BlockchainContext, n: Int): Seq[InputUTXO] =
    voterSecrets.take(n).zipWithIndex.map { case (s, i) => voteIn(ctx, s, 1L, i + 1) }

  private val retunedParams: Array[Long] = Array(3000L * LIT, 50000L * LIT, 5L * LIT)

  private def retuned(box: UTXO): UTXO =
    box.withReg(0, ErgoValue.of(Colls.fromArray(retunedParams), scalaLongType))

  private def spend(ctx: BlockchainContext,
                    prover: ErgoProver,
                    inputs: Seq[InputUTXO],
                    outputs: Seq[UTXO],
                    dataInputs: Seq[InputUTXO] = Seq.empty[InputUTXO],
                    burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(ctx, inputs, outputs, prover.getAddress, dataInputs, burn)

  // ─── quorum ───────────────────────────────────────────────────────────────

  property("quorum: accepts retuned params with exactly 3 votes from 3 holders") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(3))
      accepts(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 3), Seq(retuned(box))))
    }
  }

  property("quorum: accepts all 5 votes") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets)
      accepts(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 5), Seq(retuned(box))))
    }
  }

  /** Votes are counted by amount, not by box, so one holder of 3 tokens is a quorum alone. */
  property("quorum: accepts 3 votes held in a single box") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(1))
      val votes = voteIn(ctx, voterSecrets.head, 3L, 1)
      accepts(prover, spend(ctx, prover, Seq(inputAt(box, ctx, 0), votes), Seq(retuned(box))))
    }
  }

  /** No `onlyOne` guard: the config box has no position of its own in the inputs. */
  property("quorum: accepts the config box at an input other than INPUTS(0)") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(3))
      val ins = votesFrom(ctx, 3) :+ inputAt(box, ctx, 4)
      accepts(prover, spend(ctx, prover, ins, Seq(retuned(box))))
    }
  }

  property("quorum: rejects 2 votes (votes >= 3)") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(2))
      rejectsAtSigning(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 2), Seq(retuned(box))))
    }
  }

  /** The old contract let anyone respend an unchanged box. This one does not. */
  property("quorum: rejects an unchanged respend with no votes (votes >= 3)") {
    withCtx { ctx =>
      val box = config(ctx)
      val stranger = miner(ctx)
      rejectsAtSigning(stranger, spend(ctx, stranger, Seq(inputAt(box, ctx, 0), fundingInput(ctx, stranger)), Seq(box)))
    }
  }

  /** A referenced box proves nothing about its holder's consent, so it must not count. */
  property("quorum: rejects a third vote supplied as a data input (votes >= 3)") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(2))
      val referenced = voteIn(ctx, voterSecrets(2), 1L, 3)
      rejectsAtSigning(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 2), Seq(retuned(box)),
        dataInputs = Seq(referenced)))
    }
  }

  property("quorum: rejects a third token that is not the voting token (CONST_VOTE_TOKEN_ID)") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(3))
      val impostor = voteIn(ctx, voterSecrets(2), 1L, 3, tokenId = unrelatedTokenId)
      rejectsAtSigning(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: (votesFrom(ctx, 2) :+ impostor),
        Seq(retuned(box))))
    }
  }

  // ─── nftKept ──────────────────────────────────────────────────────────────

  /** The DAO path: the NFT and its registers move to a script this contract knows nothing about. */
  property("nftKept: accepts the NFT moving to a different script") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(3))
      val dao = voterContract(ctx, BigInteger.valueOf(8008L))
      accepts(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 3), Seq(box.setContract(dao))))
    }
  }

  property("nftKept: rejects the NFT burned (OUTPUTS(0).tokens(0))") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(3))
      val burned = retuned(box).setTokens()
      rejectsAtSigning(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 3), Seq(burned),
        burn = Seq(Token(LFSMHelpers.EMCONFIG_NFT_MAINNET, 1L))))
    }
  }

  /** OUTPUTS(0) carries a token here, so the rejection is the comparison failing rather than a throw. */
  property("nftKept: rejects the NFT at OUTPUTS(1) (OUTPUTS(0).tokens(0))") {
    withCtx { ctx =>
      val box = config(ctx)
      val prover = voters(ctx, voterSecrets.take(3))
      val voteReturn = UTXO(voterContract(ctx, voterSecrets.head), Parameters.OneErg, Seq(Token(voteTokenId, 3L)))
      rejectsAtSigning(prover, spend(ctx, prover, inputAt(box, ctx, 0) +: votesFrom(ctx, 3),
        Seq(voteReturn, retuned(box))))
    }
  }

  // ─── size ─────────────────────────────────────────────────────────────────

  property("size: the mainnet config script is smaller than the testnet one") {
    withCtx { ctx =>
      val mainnet = mainnetConfig(ctx).propBytes.length
      val testnet = configContract(ctx).propBytes.length
      println(s"[size] Emission_Config_Mainnet ergoTree = $mainnet bytes (Emission_Config = $testnet)")
      mainnet should be < testnet
    }
  }

  /**
   * With no permissionless refresh, value is the only defence against rent: a collection must preserve
   * the box while its value covers the fee. Registers are unchecked, so this holds for the launch
   * layout only — a vote that pads them raises the fee.
   */
  property("rent: 5 ERG covers at least 5 storage periods at the maximum fee factor") {
    withCtx { ctx =>
      val factorMax = 2500000L
      val size = inputAt(config(ctx), ctx, 0).bytes.length
      val periods = (5L * Parameters.OneErg) / (factorMax * size)
      println(s"[rent] Emission_Config_Mainnet box = $size bytes, ${factorMax * size} nanoERG per period at max")
      periods should be >= 5L
    }
  }
}
