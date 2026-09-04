package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.Contract

object FraudProofContracts {

  def mkInvalidDiffContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidDiff"), Seq.empty)
  }
  def mkInvalidFormatContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_TX_PROOF_MIN", LFSMHelpers.TX_PROOF_MIN)
      .item("CONST_TX_PROOF_MAX", LFSMHelpers.TX_PROOF_MAX)
      .item("CONST_TX_SIZE_MIN", LFSMHelpers.TX_SIZE_MIN)
      .item("CONST_COLLAT_BOX_MIN", LFSMHelpers.COLLAT_BOX_MIN)
      .item("CONST_NUM_LVLS_MAX", LFSMHelpers.NUM_LVLS_MAX)
      .build()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidFormat"), Seq.empty)
  }
  def mkNonUniqueHeadersContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_NonUniqueHeaders"), Seq.empty)
  }
  def mkNotInWindowContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .build()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_NotInWindow"), Seq.empty)
  }

  def mkIncorrectNContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_IncorrectN"), Seq.empty)
  }

  def mkMalformedGEContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_MalformedGE"), Seq.empty)
  }

  def mkTransactionNotIncludedContract(network: NetworkType): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_TransactionNotIncluded"), Seq.empty)
  }

  /**
   * The only proof that reads the Miner Dictionary, so the only one needing its NFT and the MinerData
   * guard's hash. Compiling it compiles the guard and its logic underneath, so callers should cache.
   */
  def mkNonMatchingCommitmentContract(network: NetworkType, mdToken: ErgoId): Contract =
    mkNonMatchingCommitmentContract(network, mdToken,
      DictionaryContracts.mkMinerDataContract(network, mdToken).hashedPropBytes)

  /** For a caller that has already compiled the MinerData guard, so it is not compiled again. */
  def mkNonMatchingCommitmentContract(network: NetworkType,
                                      mdToken: ErgoId,
                                      minerDataHash: Array[Byte]): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_MINERDATA_PROPBYTES", Colls.fromArray(minerDataHash))
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .build()
    Contract.fromErgoScript(network, constants,
      ScriptGenerator.mkFraudProofScript("FP_NonMatchingCommitment"), Seq.empty)
  }

  /**
   * The proof that binds a super-share to the miner submitting it, by walking the genesis transaction
   * carried in the share down to the holding output's R8.
   *
   * Takes whole ergoTrees rather than hashes: it compares serialized bytes inside a transaction it
   * never deserializes, so it needs the bytes themselves.
   */
  def mkMalformedGenesisContract(network: NetworkType,
                                 collateral: Contract,
                                 holding: Contract): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_COLLAT_ERGOTREE", Colls.fromArray(collateral.ergoTree.bytes))
      .item("CONST_HOLDING_ERGOTREE", Colls.fromArray(holding.ergoTree.bytes))
      .item("CONST_COLLAT_TOKEN", Colls.fromArray(LFSMHelpers.COLLAT_TOKEN.getBytes))
      .build()
    Contract.fromErgoScript(network, constants, ScriptGenerator.mkFraudProofScript("FP_MalformedGenesis"), Seq.empty)
  }

  /**
   * The nine proofs, named rather than positional, so a caller identifying one it holds does not
   * recompile the set to find out which it is.
   */
  final case class FraudProofSet(nonMatchingCommitment: Contract,
                                 invalidFormat: Contract,
                                 malformedGE: Contract,
                                 notInWindow: Contract,
                                 nonUniqueHeaders: Contract,
                                 incorrectN: Contract,
                                 invalidDiff: Contract,
                                 transactionNotIncluded: Contract,
                                 malformedGenesis: Contract) {

    /**
     * The order `Evaluator` tries them in, and the order `FP_CONTROL` is minted against.
     *
     * A dependency order, not a convention: `FP_InvalidFormat` pins the framing every later proof
     * indexes against, `FP_MalformedGE` pins that a header's key is a real point before anything
     * deserializes one, and `FP_IncorrectN` pins N before `FP_InvalidDiff` feeds it to `powHit`.
     * Cheapest first otherwise, which is why `FP_MalformedGenesis` trails.
     */
    def ordered: Seq[Contract] = Seq(
      nonMatchingCommitment,
      invalidFormat,
      malformedGE,
      notInWindow,
      nonUniqueHeaders,
      incorrectN,
      invalidDiff,
      transactionNotIncluded,
      malformedGenesis)
  }

  /**
   * The whole set, built from the deployed collateral and holding trees its last member injects.
   * Uncached: the caller that owns the protocol's contracts holds the only copy.
   */
  def fraudProofSet(network: NetworkType,
                    collateral: Contract,
                    holding: Contract,
                    minerDataHash: Array[Byte]): FraudProofSet =
    FraudProofSet(
      mkNonMatchingCommitmentContract(network, LFSMHelpers.getMDToken(network), minerDataHash),
      mkInvalidFormatContract(network),
      mkMalformedGEContract(network),
      mkNotInWindowContract(network),
      mkNonUniqueHeadersContract(network),
      mkIncorrectNContract(network),
      mkInvalidDiffContract(network),
      mkTransactionNotIncludedContract(network),
      mkMalformedGenesisContract(network, collateral, holding))

}
