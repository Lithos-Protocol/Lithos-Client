package transactions.rent

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.BlockchainContext
import transactions.ProtocolContracts
import work.lithos.mutations.InputUTXO

/**
 * The boxes this protocol runs on, which a rent collection must never take.
 *
 * Age is the only thing Ergo's rule cares about, so a singleton that has sat still long enough is
 * as collectable as anyone's forgotten change — including this client's own. Taking one is not a
 * loss of ERG but a loss of protocol state: the dictionary, the fraud-proof whitelist, the emission
 * singleton and every live lender position are identified by exactly these scripts and tokens.
 *
 * Identified two ways because either alone leaves a gap. A box under a protocol script is protocol
 * state whatever it carries, and a box carrying a protocol singleton is protocol state wherever it
 * happens to sit — an NFT resting at an ordinary key between spends is still the thing it names.
 */
final case class ProtocolBoxes(trees: Set[String], singletons: Set[String]) {

  /** Whether this box belongs to the protocol, by the script it sits at or the token it carries. */
  def owns(box: InputUTXO): Boolean =
    trees.contains(box.contract.ergoTreeHex) ||
      box.tokens.exists(token => singletons.contains(token.id.toString))

  /** The same test against a box as the node reports it, which is what a scan has in hand. */
  def owns(box: node.model.NodeBox): Boolean =
    trees.contains(box.ergoTree) || box.assets.exists(a => singletons.contains(a.tokenId))
}

object ProtocolBoxes {

  /**
   * Every script this client compiles and every singleton it identifies them by.
   *
   * LIT is deliberately absent: it is the protocol's currency rather than one of its boxes, held by
   * anyone who has ever been paid, and excluding it would put ordinary wallets out of reach for no
   * protocol reason.
   */
  def apply(ctx: BlockchainContext): ProtocolBoxes = {
    val c = ProtocolContracts(ctx)
    val trees = (Seq(c.payout, c.eval, c.holding, c.holdingLogic, c.gate, c.collateral,
      c.emission, c.guard, c.enforcer, c.minerDictionary, c.minerData, c.minerDataLogic) ++
      c.fraudProofs.ordered).map(_.ergoTreeHex).toSet

    val singletons = Set(
      LFSMHelpers.getCollatToken(ctx.getNetworkType),
      LFSMHelpers.getEmissionNft(ctx.getNetworkType),
      LFSMHelpers.getMDToken(ctx.getNetworkType),
      LFSMHelpers.getFPToken(ctx.getNetworkType)).map(_.toString)

    ProtocolBoxes(trees, singletons)
  }
}
