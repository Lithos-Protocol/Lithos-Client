package tools

import lfsm.contracts.RollupContracts
import lfsm.states.PlasmaDictionary
import lfsm.{CollateralParams, DeploymentIds, LFSMHelpers}
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{Address, ErgoType, ErgoValue, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import transactions.CompiledContracts
import work.lithos.mutations.{Contract, Token, UTXO}

import java.nio.charset.StandardCharsets

/**
 * One protocol token: its role in [[DeploymentIds]], what it is called on chain, and how many exist.
 *
 * Amounts are the ones the contracts assume rather than ones chosen here, because a contract cannot
 * check its own mint: LIT_Emissions states the emission box holds Long.MaxValue queue and collateral
 * tokens and 825,000,000 LIT, Emission_Config_Mainnet that exactly five vote tokens exist, and
 * FP_Control, the config and the dictionary that their NFTs are singletons.
 */
final case class TokenSpec(role: String, name: String, description: String, amount: Long, decimals: Int)

/** The four boxes a deployment consists of, in the order the creating transaction emits them. */
final case class ProtocolBoxes(emission: UTXO, config: UTXO, fpControl: UTXO, dictionary: UTXO) {
  def all: Seq[UTXO] = Seq(emission, config, fpControl, dictionary)
}

/**
 * What `DeployProtocol` builds, as values, so the shapes can be checked against the client's own
 * readers without a node. Every box here is shaped by what a reader or a contract checks, not by a
 * fixture: a fixture is free to use a stand-in where the chain cannot.
 */
object DeployPlan {

  /** EIP-4 R6 is the decimals as a UTF-8 string, which is what explorers and the node index read. */
  private def utf8(s: String): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(s.getBytes(StandardCharsets.UTF_8)), scalaByteType)

  /** 825,000,000 LIT at 9 decimals: what LIT_Emissions' ASSUMPTIONS say the emission box is funded with. */
  final val EmissionLit: Long = 825000000L * 1000000000L

  /**
   * The whole LIT mint, `LFSMHelpers.INIT_MINT` (one billion LIT). What the emission box does not hold
   * stays with the deployer, which is where `--fund` takes operators' permits from.
   */
  final val TotalLit: Long = LFSMHelpers.INIT_MINT

  /** Five, of which any three spend the config box (Emission_Config_Mainnet's `quorum`). */
  final val VoteTokens: Long = 5L

  /** What each freshly minted token box holds until step 3 spends it. Comfortably above min box value. */
  final val MintBoxValue: Long = Parameters.MinChangeValue

  /** LIT_Emissions: "Value: 0.01 ERG". Conserved by every spend, so it is fixed here for good. */
  final val EmissionBoxValue: Long = Parameters.OneErg / 100

  /** Emission_Config_Mainnet: "Value: 5 ERG, so storage rent can never take the box". */
  final val ConfigBoxValue: Long = 5L * Parameters.OneErg

  /** FP_Control_Mainnet: "Value: 5 ERG, held forever so storage rent can never take the box". */
  final val FpControlBoxValue: Long = 5L * Parameters.OneErg

  /**
   * MinerDictionary conserves its value on every path and states none. 0.01 ERG, as the dictionary
   * specs use (`DictionarySpecBase.dictValue`, "as the emission box").
   */
  final val DictionaryBoxValue: Long = Parameters.OneErg / 100

  /**
   * The config box's R6, the collateral extension script hash: 32 zero bytes.
   *
   * Collateral_Mainnet reads R6 only for a box whose extension flag is set, and Collateral_Enforcer
   * pins every queue box's flag to 0x00 ("No extensions at launch"), so nothing reads it today. Zero
   * bytes have no known blake2b256 preimage, so until a vote writes a real hash no extension can be
   * authenticated, which is the safe reading of "no extension". The fixture's stand-in (the hash of
   * a true script) would instead authorise a trivially satisfiable extension the moment a flag is set.
   */
  final val NoExtensionHash: Array[Byte] = Array.fill[Byte](32)(0)

  /** The eight tokens, in the order step 1 mints them. Names are what the idempotence check matches. */
  val tokens: Seq[TokenSpec] = Seq(
    TokenSpec(DeploymentIds.Keys.Lit, "Lithos devnet LIT", "LIT for a private Lithos deployment", TotalLit, 9),
    TokenSpec(DeploymentIds.Keys.EmissionNft, "Lithos devnet emission NFT",
      "Singleton of the emission box", 1L, 0),
    TokenSpec(DeploymentIds.Keys.EmConfigNft, "Lithos devnet emission config NFT",
      "Singleton of the emission config box", 1L, 0),
    TokenSpec(DeploymentIds.Keys.QueueToken, "Lithos devnet queue token",
      "Proposition token of the collateral queue", LFSMHelpers.PROP_TOKEN_AMNT, 0),
    TokenSpec(DeploymentIds.Keys.CollatToken, "Lithos devnet collateral token",
      "Proposition token of collateral and proof-of-spend boxes", LFSMHelpers.PROP_TOKEN_AMNT, 0),
    TokenSpec(DeploymentIds.Keys.FpToken, "Lithos devnet FP control NFT",
      "Singleton of the fraud proof control box", 1L, 0),
    TokenSpec(DeploymentIds.Keys.MdToken, "Lithos devnet miner dictionary NFT",
      "Singleton of the miner dictionary", 1L, 0),
    TokenSpec(DeploymentIds.Keys.VoteToken, "Lithos devnet vote token",
      "Governance vote over the emission config, three of five to spend", VoteTokens, 0))

  def tokenNames: Set[String] = tokens.map(_.name.toLowerCase).toSet

  /**
   * The box a mint creates. The new token's id is the first input's box id, which only the
   * transaction spending that box can choose, so the caller passes it in. R4-R6 are EIP-4 metadata.
   */
  def mintOutput(owner: Contract, spec: TokenSpec, firstInput: ErgoId): UTXO =
    UTXO(owner, MintBoxValue, Seq(Token(firstInput, spec.amount)),
      Seq(utf8(spec.name), utf8(spec.description), utf8(spec.decimals.toString)))

  /**
   * The FP control address a deployment compiles to. `FP_Control_Mainnet` takes no constants, so on
   * every network the address depends only on the network prefix.
   */
  def fpControlAddress(network: NetworkType): Address =
    RollupContracts.mkFPControlMainnetContract(network).address(network)

  private def bytes(v: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaByteType)

  /**
   * The four protocol boxes, compiled against `c`, which must have been compiled with `ids` installed.
   *
   * Emission box, read by `EmissionTransactions.readEmission`: R4 current block (Int) 0, R5 the empty
   * lender set (`Coll[Coll[Byte]]`), R6 head (Long) 0, R7 tail (Long) 0 (LIT_Emissions: "R4, R6 and R7
   * start at 0 and R5 starts empty"); tokens NFT, queue, collateral, LIT in that order, because the
   * contract reads them by index; under the guard, which is the tree `emissionTip` filters on.
   *
   * Config box, read by `readConfig` and found by `configBox` on its NFT and four registers: R4 the
   * permit params, R5 the enforcer's hashed VALUE bytes (Emission_Guard hashes what it executes), R6
   * [[NoExtensionHash]], R7 the holding contract's hashed PROP bytes, which Activate stamps into each
   * collateral box's R8 and `loadCollateral` then requires to match this build's holding contract. The
   * script is `mkMainnetEmConfigContract` with the deployment's vote token: consumers find the box by
   * its NFT, never its script, so the fixture's stand-in works in specs, but on a chain the script is
   * what decides who can rewrite the parameters, and that has to be the vote quorum.
   *
   * FP control box, read by `getFPControlBox` on its token and by Evaluation at `dataInputs(0)`: the FP
   * token first, R4 the blake2b256 of every fraud proof's VALUE bytes (Evaluation hashes the script it
   * is handed in context var 0, which is `contract.valueBytes`), in `FraudProofSet.ordered` order.
   *
   * Dictionary genesis box: the MD NFT, R4 the empty all-operations AVL tree with the plasma
   * parameters `MinerDictionary.initialState` replays from, so the first register lands on the same
   * digest the client starts from.
   */
  def protocolBoxes(ids: DeploymentIds, c: CompiledContracts, network: NetworkType): ProtocolBoxes = {
    val emptySet = ErgoValue.of(Colls.fromArray(Array.empty[sigma.Coll[Byte]]), ErgoType.collType(scalaByteType))

    val emission = UTXO(c.guard, EmissionBoxValue,
      Seq(Token(ids.emissionNft, 1L),
        Token(ids.queueToken, LFSMHelpers.PROP_TOKEN_AMNT),
        Token(ids.collatToken, LFSMHelpers.PROP_TOKEN_AMNT),
        Token(ids.lit, EmissionLit)),
      Seq(ErgoValue.of(0), emptySet, ErgoValue.of(0L), ErgoValue.of(0L)))

    val config = UTXO(lfsm.contracts.CollateralContract.mkMainnetEmConfigContract(network, ids.voteToken),
      ConfigBoxValue,
      Seq(Token(ids.emConfigNft, 1L)),
      Seq(ErgoValue.of(Colls.fromArray(LFSMHelpers.PERMIT_PARAMS), scalaLongType),
        bytes(c.enforcer.hashedValueBytes),
        bytes(NoExtensionHash),
        bytes(c.holding.hashedPropBytes)))

    val fpSet = ErgoValue.of(
      Colls.fromArray(c.fraudProofs.ordered.map(p => Colls.fromArray(p.hashedValueBytes)).toArray),
      ErgoType.collType(scalaByteType))
    val fpControl = UTXO(Contract.fromAddress(ids.fpControlAddress), FpControlBoxValue,
      Seq(Token(ids.fpToken, 1L)), Seq(fpSet))

    val dictionary = UTXO(c.minerDictionary, DictionaryBoxValue,
      Seq(Token(ids.mdToken, 1L)), Seq(PlasmaDictionary.empty().ergoValue))

    ProtocolBoxes(emission, config, fpControl, dictionary)
  }

  // ─── funding an operator ──────────────────────────────────────────────────

  /** One `--fund address:nanoERG:LIT` request. LIT in base units, 9 decimals. */
  final case class FundRequest(address: Address, nanoErg: Long, lit: Long)

  /**
   * The least ERG an operator needs for one self-join: the queue box's principal,
   * `CollateralParams.PRINCIPAL_FLOOR` (2.915 ERG, Collateral_Enforcer's `BLOCK_REWARD - FEE_CAP +
   * DUST_BUDGET` at a zero priority fee), plus the join's fee (`emission.txFee`, MinFee by default),
   * plus a min-value change box so the wallet's selection never has to land exactly.
   */
  final val MinJoinNanoErg: Long =
    CollateralParams.PRINCIPAL_FLOOR + Parameters.MinFee + Parameters.MinChangeValue

  /**
   * The least LIT for one self-join: the permit at an empty queue, the config's permit floor (R4(0),
   * `LFSMHelpers.PERMIT_FLOOR`, 2,000 LIT). Each further queued box raises it by the slope (20 LIT).
   */
  final val MinJoinLit: Long = LFSMHelpers.PERMIT_FLOOR

  /** ERG and LIT for `joins` self-joins into an empty queue, each at the permit the backlog demands. */
  def forJoins(joins: Int): (Long, Long) = {
    val lit = (0 until joins).map(k => CollateralParams.permitAt(k.toLong, LFSMHelpers.PERMIT_PARAMS.toSeq)).sum
    (joins * (CollateralParams.PRINCIPAL_FLOOR + Parameters.MinFee) + Parameters.MinChangeValue, lit)
  }

  /** Why a request would leave its operator unable to join, if it would. */
  def fundProblem(r: FundRequest): Option[String] =
    if (r.nanoErg < MinJoinNanoErg)
      Some(s"${r.address}: ${r.nanoErg} nanoERG is below the $MinJoinNanoErg one self-join needs " +
        "(principal floor + join fee + change)")
    else if (r.lit < MinJoinLit)
      Some(s"${r.address}: ${r.lit} LIT base units is below the $MinJoinLit permit an empty queue demands")
    else None

  /** `address:nanoERG:LIT`, every part required. */
  def parseFund(raw: String): Either[String, FundRequest] = raw.split(":") match {
    case Array(a, e, l) =>
      for {
        address <- scala.util.Try(Address.create(a)).toEither.left.map(ex => s"--fund $raw: bad address (${ex.getMessage})")
        erg <- scala.util.Try(e.toLong).toEither.left.map(_ => s"--fund $raw: nanoERG is not a number")
        lit <- scala.util.Try(l.toLong).toEither.left.map(_ => s"--fund $raw: LIT is not a number")
      } yield FundRequest(address, erg, lit)
    case _ => Left(s"--fund $raw: expected <address>:<nanoERG>:<LIT base units>")
  }

  /** What `--fund` pays: ERG and the deployment's LIT, to the operator's P2PK, in one box. */
  def fundOutput(r: FundRequest, litId: ErgoId): UTXO =
    UTXO(Contract.fromAddress(r.address), r.nanoErg, if (r.lit > 0) Seq(Token(litId, r.lit)) else Seq.empty[Token])
}
