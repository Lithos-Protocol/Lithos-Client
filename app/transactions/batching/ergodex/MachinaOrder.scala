package transactions.batching.ergodex

import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256

import scala.util.Try

/** A Machina Finance order contract. */
sealed abstract class MachinaKind(val name: String)
object MachinaKind {
  /** ERG against a token, one way: R6 says whether the box sells its tokens or buys them. */
  case object Limit extends MachinaKind("e2t-limit")
  /** ERG against a token, both ways at once: R5 holds the ask and the bid. */
  case object Grid extends MachinaKind("e2t-grid")
  /**
   * Its token against a base token, both ways: the base is the box's first token, R5 the ask and bid in base units.
   * Not the ErgoDEX batcher's: a fill leaves its gap in the base token, which only the arb stage sells for ERG.
   */
  case object TokenGrid extends MachinaKind("t2t-grid")
}

/**
 * The current Machina Finance templates, and only those: older versions had bugs since patched, so their
 * orders are never filled. Each carries the order's token id as constant 0; prices and the owner are registers.
 */
object MachinaContracts {

  /**
   * @param headerHex    the tree's header, size and constant count, then the token constant's type and length
   * @param constantsHex every constant after the token id, as every mainnet order of the kind carries them
   * @param otherHeaders other values of `headerHex` live orders carry over the same constants and template. Each
   *                     differs only in the ErgoTree version, 1 or 2, which the interpreter evaluates alike
   */
  final case class Template(kind: MachinaKind, templateHex: String, executable: Boolean,
                            headerHex: String = "", constantsHex: String = "", otherHeaders: Seq[String] = Seq.empty) {
    lazy val templateHash: String = Hex.toHexString(Blake2b256(Hex.decode(templateHex)))

    /** The whole tree of an order for `tokenId`, so a box differing anywhere but its token is not taken for one. */
    def treeHex(tokenId: String): String = treeHex(tokenId, headerHex)

    private def treeHex(tokenId: String, header: String): String = header + tokenId + constantsHex + templateHex

    /** Whether `tree` is this contract's for `tokenId` under any header it is accepted with. */
    def matches(tree: String, tokenId: String): Boolean =
      (headerHex +: otherHeaders).exists(header => tree.equalsIgnoreCase(treeHex(tokenId, header)))
  }

  val templates: Vector[Template] = Vector(
    Template(MachinaKind.Limit,
      "d804d601e30004d6027300d603860272027301d604e4c6a7040895e67201d807d605e4c6a70601d606b2a5e4720100d6" +
      "07b2db630872067302017203d6088c720702d609e4c6a70505d60a8c720701d60b8cb2db6308a7730301720302d1ed95" +
      "9572059172087304d801d60cc17206ed91720c730592720c7209ededededed93c27206c2a793720a720293e4c6720604" +
      "08720493e4c672060505720993e4c672060601720593e4c67206070ec5a7eded93c27206d0720493720a720293e4c672" +
      "06040ec5a7957205d801d60c99c17206c1a7ed91720c730692720c9c99720b72087209d801d60c997208720bed91720c" +
      "7307929c720c720999c1a7c172067204",
      executable = true, headerHex = "19b302080e20", constantsHex = "05000400040005000580897a05000500"),

    Template(MachinaKind.Grid,
      "d804d601e30104d6027300d603860272027301d604e4c6a7040895e67201d805d605b2a5e4720100d606b2db63087205" +
      "7302017203d607e4c6a70511d6088cb2db6308a7730301720302d6098c720602d1ededededed93c27205c2a7938c7206" +
      "01720293e4c672050408720493e4c672050511720793e4c67205060ec5a795e4e30001d801d60a99c17205c1a7ed9172" +
      "0a730492720a9c9972087209b27207730500d801d60a9972097208ed91720a7306929c720ab2720773070099c1a7c172" +
      "057204",
      executable = true, headerHex = "19f401080e20", constantsHex = "0500040004000500040005000402",
      otherHeaders = Seq("1af401080e20")),

    Template(MachinaKind.TokenGrid,
      "d804d601e30104d6027300d603860272027301d604e4c6a7040895e67201d80bd605b2a5e4720100d606db6308a7d607" +
      "b27206730200d608db63087205d609b27208730300d60ab272087304017203d60be4c6a70511d60c8c720902d60d8c72" +
      "0702d60e8cb27206730501720302d60f8c720a02d1ededededededed93c27205c2a793c17205c1a7938c7207018c7209" +
      "01938c720a01720293e4c672050408720493e4c672050511720b93e4c67205060ec5a795e4e30001d801d61099720c72" +
      "0ded91721073069272109c99720e720fb2720b730700d801d61099720f720eed9172107308929c7210b2720b73090099" +
      "720d720c7204",
      executable = false, headerHex = "1aab020a0e20", constantsHex = "050004000400040204020500040005000402"))

  lazy val executableByTemplateHash: Map[String, Template] =
    templates.filter(_.executable).map(t => t.templateHash -> t).toMap

  /** The token/token grid, which only [[MachinaOrder.parseTokenGrid]] reads. */
  lazy val tokenGrid: Template = templates.find(_.kind == MachinaKind.TokenGrid).get

  lazy val byTemplateHash: Map[String, Template] = templates.map(t => t.templateHash -> t).toMap

  /** The limit contract's SAFE_MIN_BOX_VALUE, which a buying box must keep more than to be recreated. */
  final val SafeMinBoxValue: Long = 1000000L

  /**
   * Least ERG a buying order's recreated box may keep. The limit contract recreates it only above
   * SAFE_MIN_BOX_VALUE and with enough to pay for one more unit, and otherwise pays out in full. The grid contract
   * sets no floor, so a grid box keeps SAFE_MIN_BOX_VALUE, well above its minimum value.
   */
  def childFloor(order: MachinaOrder, price: Long): Long = order.kind match {
    case MachinaKind.Limit => math.max(SafeMinBoxValue + 1, price)
    case _ => SafeMinBoxValue
  }
}

/**
 * A Machina Finance order: a box trading `tokenId` against its base at its own prices, the base ERG, or for a
 * token/token grid the token `base` names. `ask` is what it takes in base units for a unit of its tokens and `bid`
 * what it pays for one; a limit order has one of the two, a grid both. `poolNft` is the pool a fill is routed
 * through, chosen per discovery pass.
 */
final case class MachinaOrder(box: NodeBox,
                              kind: MachinaKind,
                              tokenId: String,
                              ownerPropBytes: Array[Byte],
                              ask: Option[Long],
                              bid: Option[Long],
                              poolNft: String = "",
                              base: Option[String] = None) extends PoolOrder {

  /** The contracts bound no miner fee, so a broadcast fill pays up to the configured ceiling. */
  override def maxMinerFee: Long = Long.MaxValue

  /** Units of `tokenId` the box holds. */
  def tokens: Long = box.assets.find(_.tokenId == tokenId).map(_.amount).getOrElse(0L)

  /** What the box holds of its base: its nanoERG, or a token/token grid's base token, its first. */
  def baseHeld: Long =
    base.fold(box.value)(id => box.assets.headOption.filter(_.tokenId == id).map(_.amount).getOrElse(0L))

  def routedThrough(nft: String): MachinaOrder = copy(poolNft = nft)
}

object MachinaOrder {

  /**
   * An executable Machina order, or None for any other box, an older contract's, a malformed one, one whose tree
   * differs from its kind's anywhere but the token id, or one holding anything but its own token, which no honest
   * fill puts there.
   */
  def parse(box: NodeBox): Option[MachinaOrder] =
    Try(sigma.ast.ErgoTree.fromHex(box.ergoTree)).toOption.flatMap { tree =>
      val hash = Hex.toHexString(Blake2b256(tree.template))
      MachinaContracts.executableByTemplateHash.get(hash).flatMap { template =>
        for {
          tokenId <- tokenOf(tree)
          // Pricing assumes the contract's own constants, so a box that changed one would only fail at signing
          if template.matches(box.ergoTree, tokenId)
          if box.value > 0 && box.assets.size <= 1 && box.assets.forall(a => a.tokenId == tokenId && a.amount > 0)
          owner <- register(box, 4).collect { case p: sigma.SigmaProp => p.propBytes.toArray }
          (ask, bid) <- prices(template.kind, box)
        } yield MachinaOrder(box, template.kind, tokenId, owner, ask, bid)
      }
    }

  /**
   * A token/token grid order, or None for any other box: its tree the grid's with its own token id and nothing else
   * changed, its first token another token, which is its base, its second, if it holds one, its own token, and
   * nothing more, which no honest fill adds.
   */
  def parseTokenGrid(box: NodeBox): Option[MachinaOrder] = {
    val template = MachinaContracts.tokenGrid
    Try(sigma.ast.ErgoTree.fromHex(box.ergoTree)).toOption
      .filter(tree => Hex.toHexString(Blake2b256(tree.template)) == template.templateHash).flatMap { tree =>
        for {
          tokenId <- tokenOf(tree)
          if template.matches(box.ergoTree, tokenId)
          base <- box.assets.headOption if base.tokenId != tokenId && base.amount > 0
          if box.value > 0 && box.assets.size <= 2 && box.assets.drop(1).forall(a => a.tokenId == tokenId && a.amount > 0)
          owner <- register(box, 4).collect { case p: sigma.SigmaProp => p.propBytes.toArray }
          (ask, bid) <- prices(MachinaKind.TokenGrid, box)
        } yield MachinaOrder(box, MachinaKind.TokenGrid, tokenId, owner, ask, bid, base = Some(base.tokenId))
      }
  }

  /** Constant 0, the order's token id, when it is 32 bytes. */
  private def tokenOf(tree: sigma.ast.ErgoTree): Option[String] =
    Try(tree.constants.head.value).toOption.collect { case c: sigma.Coll[_] =>
      Try(c.asInstanceOf[sigma.Coll[Byte]].toArray).toOption }.flatten.filter(_.length == 32).map(Hex.toHexString)

  /** The limit's one price, on the side R6 names; a grid's ask and bid, the first two entries of R5. */
  private def prices(kind: MachinaKind, box: NodeBox): Option[(Option[Long], Option[Long])] = kind match {
    case MachinaKind.Limit =>
      for {
        price <- register(box, 5).collect { case p: java.lang.Long => p.longValue() } if price > 0
        sells <- register(box, 6).collect { case s: java.lang.Boolean => s.booleanValue() }
      } yield if (sells) (Some(price), None) else (None, Some(price))
    case MachinaKind.Grid | MachinaKind.TokenGrid =>
      register(box, 5).collect { case c: sigma.Coll[_] => Try(c.toArray.toSeq.map(_.asInstanceOf[Long])).toOption }
        .flatten.filter(p => p.size >= 2 && p.head > 0 && p(1) > 0).map(p => (Some(p.head), Some(p(1))))
  }

  private def register(box: NodeBox, index: Int): Option[Any] =
    box.additionalRegisters.get(index).flatMap(hex =>
      Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue).toOption)
}
