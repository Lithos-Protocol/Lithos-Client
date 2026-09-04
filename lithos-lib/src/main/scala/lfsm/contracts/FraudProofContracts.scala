package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.Contract

object FraudProofContracts {

  def mkInvalidDiffContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidDiff"))
  }
  def mkInvalidFormatContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_TX_PROOF_MIN", LFSMHelpers.TX_PROOF_MIN)
      .item("CONST_TX_PROOF_MAX", LFSMHelpers.TX_PROOF_MAX)
      .item("CONST_TX_SIZE_MIN", LFSMHelpers.TX_SIZE_MIN)
      .item("CONST_COLLAT_BOX_MIN", LFSMHelpers.COLLAT_BOX_MIN)
      .item("CONST_NUM_LVLS_MAX", LFSMHelpers.NUM_LVLS_MAX)
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidFormat"))
  }
  def mkNonUniqueHeadersContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_NonUniqueHeaders"))
  }
  def mkNotInWindowContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_NotInWindow"))
  }

  def mkIncorrectNContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_IncorrectN"))
  }

  def mkMalformedGEContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_MalformedGE"))
  }

  def mkTransactionNotIncludedContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_TransactionNotIncluded"))
  }

  /**
   * The only proof that reads the Miner Dictionary, so the only one needing its NFT and the MinerData
   * guard's hash. Compiling it compiles the guard and its logic underneath, so callers should cache.
   */
  def mkNonMatchingCommitmentContract(ctx: BlockchainContext, mdToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_MINERDATA_PROPBYTES",
        Colls.fromArray(DictionaryContracts.mkMinerDataContract(ctx, mdToken).hashedPropBytes))
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .build()
    Contract.fromErgoScript(ctx, constants,
      ScriptGenerator.mkFraudProofScript("FP_NonMatchingCommitment"))
  }

  /**
   * The proof that binds a super-share to the miner submitting it, by walking the genesis transaction
   * carried in the share down to the holding output's R8.
   *
   * Takes whole ergoTrees rather than hashes: it compares serialized bytes inside a transaction it
   * never deserializes, so it needs the bytes themselves.
   */
  def mkMalformedGenesisContract(ctx: BlockchainContext,
                                 collateral: Contract,
                                 holding: Contract): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_COLLAT_ERGOTREE", Colls.fromArray(collateral.ergoTree.bytes))
      .item("CONST_HOLDING_ERGOTREE", Colls.fromArray(holding.ergoTree.bytes))
      .item("CONST_COLLAT_TOKEN", Colls.fromArray(LFSMHelpers.COLLAT_TOKEN.getBytes))
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_MalformedGenesis"))
  }

  /**
   * The whole set, named rather than positional, so a caller dispatching on which proof it holds does
   * not have to recompile eight scripts to find out.
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
     * It is a dependency order, not a convention. `FP_InvalidFormat` validates the framing every
     * later proof indexes against, and `FP_MalformedGE` establishes that a header's miner key is a
     * real curve point — without which `deserializeTo[Header]` throws rather than returning a
     * verdict, and a throw is indistinguishable from a proof that never ran. `FP_IncorrectN` must
     * precede `FP_InvalidDiff`, which feeds the share's N straight into `powHit`.
     *
     * `FP_NonMatchingCommitment` leads because it reads only the eight score bytes and so depends on
     * nothing, and it is the cheapest way to establish fraud. `FP_MalformedGenesis` trails because it
     * walks ten serialized transactions, which is the most expensive check in the set.
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

  private var cache: Option[(String, FraudProofSet)] = None

  /**
   * Compiled once per network for the life of the JVM.
   *
   * Not only an optimisation: the dispatcher used to identify a proof by recompiling every candidate
   * and comparing hashes, which is eight compilations per miner per attempt, and `FP_MalformedGenesis`
   * needs the collateral and holding trees on top of that.
   */
  def fraudProofSet(ctx: BlockchainContext): FraudProofSet = synchronized {
    val network = ctx.getNetworkType.toString
    cache match {
      case Some((cached, set)) if cached == network => set
      case _ =>
        // FP_MalformedGenesis compares serialized bytes inside a transaction it never deserializes,
        // so it needs the deployed trees themselves rather than their hashes.
        val deployed = DeployedContracts(ctx)

        val set = FraudProofSet(
          mkNonMatchingCommitmentContract(ctx, LFSMHelpers.getMDToken(ctx.getNetworkType)),
          mkInvalidFormatContract(ctx),
          mkMalformedGEContract(ctx),
          mkNotInWindowContract(ctx),
          mkNonUniqueHeadersContract(ctx),
          mkIncorrectNContract(ctx),
          mkInvalidDiffContract(ctx),
          mkTransactionNotIncludedContract(ctx),
          mkMalformedGenesisContract(ctx, deployed.collateral, deployed.holding.guard))
        cache = Some((network, set))
        set
    }
  }

  def getFraudProofContracts(ctx: BlockchainContext): Seq[Contract] = fraudProofSet(ctx).ordered

}
