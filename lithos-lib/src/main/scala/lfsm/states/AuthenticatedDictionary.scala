package lfsm.states

import org.ergoplatform.appkit.ErgoValue
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.authds.avltree.batch.{InternalProverNode, ProverLeaf, ProverNodes}
import scorex.crypto.authds.avltree.batch.serialization.{BatchAVLProverSerializer, ProxyInternalNode}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.utils.Logger
import sigma.AvlTree
import sigma.data.AvlTreeFlags
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.{Manifest, PlasmaMap, ProvenResult}

/**
 * Backend-neutral authenticated-map operations.
 * Committed instances are read-only; mutations require a private copy.
 */
trait AuthenticatedDictionaryView {
  def digest: Array[Byte]
  def ergoValue: ErgoValue[AvlTree]
  def flags: AvlTreeFlags
  def parameters: PlasmaParameters

  /** False for a digest-only reference that must be loaded before dictionary operations. */
  def materialized: Boolean

  /**
   * Conservative retained-heap weight used by synchronization cache admission. This is deliberately
   * larger than serialized prover bytes and is carried through copies and mutations, so measuring a
   * hot dictionary never requires serializing its complete AVL tree.
   */
  def estimatedHeapBytes: Long

  def copy(): AuthenticatedDictionary

  /**
   * Visits authenticated user keys without exposing mutable prover nodes.
   * Implementations must omit the AVL infinity sentinel and provide a defensive key copy.
   */
  def foreachKey(visit: Array[Byte] => Unit): Unit

  final def foldKeys[A](initial: A)(fold: (A, Array[Byte]) => A): A = {
    var result = initial
    foreachKey(key => result = fold(result, key))
    result
  }

  /**
   * The complete AVL manifest.
   */
  def getManifest(): Manifest

  /**
   * Serializes one bounded subtree at a time and returns the small manifest header. The callback must
   * consume or copy each array before returning; the implementation does not retain serialized arrays.
   */
  def writeManifest(subtreeDepth: Int)(writeSubtree: (Int, Array[Byte]) => Unit): DictionaryManifestHeader
}

final case class DictionaryManifestHeader(flags: AvlTreeFlags,
                                          parameters: PlasmaParameters,
                                          digest: Array[Byte],
                                          manifest: Array[Byte],
                                          subtreeCount: Int)

/**
 * A small content-addressed reference used for always-resident synchronization metadata.
 * Any operation that needs prover nodes must first resolve it through the snapshot repository.
 */
final class DeferredDictionary private (private val ownedDigest: Array[Byte],
                                        override val flags: AvlTreeFlags,
                                        override val parameters: PlasmaParameters)
  extends AuthenticatedDictionaryView {

  override def digest: Array[Byte] = ownedDigest.clone()
  override val materialized: Boolean = false
  override val estimatedHeapBytes: Long = 0L

  override def ergoValue: ErgoValue[AvlTree] = unavailable()
  override def copy(): AuthenticatedDictionary = unavailable()
  override def foreachKey(visit: Array[Byte] => Unit): Unit = unavailable()
  override def getManifest(): Manifest = unavailable()
  override def writeManifest(subtreeDepth: Int)
                            (writeSubtree: (Int, Array[Byte]) => Unit): DictionaryManifestHeader = unavailable()

  private def unavailable[A](): A = throw new IllegalStateException(
    s"Dictionary ${Hex.toHexString(ownedDigest)} is not materialized")
}

object DeferredDictionary {
  def apply(digest: Array[Byte],
            flags: AvlTreeFlags,
            parameters: PlasmaParameters): DeferredDictionary =
    new DeferredDictionary(digest.clone(), flags, parameters)
}

/** Mutable dictionary used only by an isolated reducer or transaction-building projection. */
trait AuthenticatedDictionary extends AuthenticatedDictionaryView {

  def insert(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]]
  def update(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]]
  def delete(keys: Array[Byte]*): ProvenResult[Array[Byte]]
  def lookUp(keys: Array[Byte]*): ProvenResult[Array[Byte]]
  def insertOrUpdate(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]]
  def generateProof(): Array[Byte]

}

object AuthenticatedDictionaryView {
  /** Convenience conversion for callers constructing state directly from Plasma Toolkit maps. */
  implicit def fromPlasmaMap(map: PlasmaMap[Array[Byte], Array[Byte]]): AuthenticatedDictionaryView =
    PlasmaDictionary.wrap(map)
}

final class PlasmaDictionary private (private val map: PlasmaMap[Array[Byte], Array[Byte]],
                                      private var retainedHeapEstimate: Long)
  extends AuthenticatedDictionary {

  override def digest: Array[Byte] = map.digest.clone()
  override def ergoValue: ErgoValue[AvlTree] = map.ergoValue
  override def flags: AvlTreeFlags = map.flags
  override def parameters: PlasmaParameters = map.params
  override val materialized: Boolean = true
  override def estimatedHeapBytes: Long = retainedHeapEstimate

  override def insert(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]] = {
    val result = map.insert(entries: _*)
    account(entries)
    result
  }

  override def update(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]] = {
    val result = map.update(entries: _*)
    // Keep replacements conservative without looking up and retaining the previous values.
    account(entries)
    result
  }

  override def delete(keys: Array[Byte]*): ProvenResult[Array[Byte]] = map.delete(keys: _*)

  override def lookUp(keys: Array[Byte]*): ProvenResult[Array[Byte]] = map.lookUp(keys: _*)

  override def insertOrUpdate(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]] = {
    val result = map.insertOrUpdate(entries: _*)
    account(entries)
    result
  }

  override def generateProof(): Array[Byte] = map.prover.generateProof()

  override def copy(): AuthenticatedDictionary =
    new PlasmaDictionary(map.copy(), retainedHeapEstimate)

  override def foreachKey(visit: Array[Byte] => Unit): Unit = {
    implicit val hash: Blake2b256.type = Blake2b256
    implicit val logger: Logger = Logger.Default
    val serializer = new BatchAVLProverSerializer[Digest32, Blake2b256.type]()
    val sliced = serializer.slice(map.prover, Int.MaxValue)
    val negativeInfinity = Array.fill[Byte](map.params.keySize)(0)
    val positiveInfinity = Array.fill[Byte](map.params.keySize)(-1)

    def walk(node: ProverNodes[Digest32]): Unit = node match {
      case leaf: ProverLeaf[Digest32] =>
        val key = leaf.key
        if (!java.util.Arrays.equals(key, negativeInfinity) &&
          !java.util.Arrays.equals(key, positiveInfinity)) visit(key.clone())
      case proxy: ProxyInternalNode[Digest32] if proxy.isEmpty =>
      case internal: InternalProverNode[Digest32] =>
        walk(internal.left)
        walk(internal.right)
    }

    // Shallow leaves remain embedded in the manifest, while deeper leaves are in its detached subtrees.
    // Visiting both is required for unbalanced trees; the corrected scrypto slicer does not duplicate either.
    walk(sliced._1.root)
    sliced._2.foreach(subtree => walk(subtree.subtreeTop))
  }

  override def getManifest(): Manifest = map.getManifest(0)

  override def writeManifest(subtreeDepth: Int)
                            (writeSubtree: (Int, Array[Byte]) => Unit): DictionaryManifestHeader = {
    implicit val hash: Blake2b256.type = Blake2b256
    implicit val logger: Logger = Logger.Default
    val serializer = new BatchAVLProverSerializer[Digest32, Blake2b256.type]()
    val sliced = serializer.slice(map.prover, subtreeDepth)
    sliced._2.iterator.zipWithIndex.foreach { case (subtree, index) =>
      writeSubtree(index, serializer.subtreeToBytes(subtree))
    }
    DictionaryManifestHeader(map.flags, map.params, map.digest.clone(),
      serializer.manifestToBytes(sliced._1), sliced._2.size)
  }

  private def account(entries: Seq[(Array[Byte], Array[Byte])]): Unit = {
    val growth = entries.foldLeft(0L) { case (total, (key, value)) =>
      PlasmaDictionary.saturatingAdd(total,
        PlasmaDictionary.mutationWeight(key.length.toLong + value.length.toLong))
    }
    retainedHeapEstimate = PlasmaDictionary.saturatingAdd(retainedHeapEstimate, growth)
  }
}

object PlasmaDictionary {
  private[states] def wrap(map: PlasmaMap[Array[Byte], Array[Byte]]): PlasmaDictionary =
    new PlasmaDictionary(map, manifestWeight(map.getManifest()))

  def empty(flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed,
            parameters: PlasmaParameters = PlasmaParameters.default): PlasmaDictionary =
    new PlasmaDictionary(PlasmaMap[Array[Byte], Array[Byte]](flags, parameters), EmptyWeight)

  def fromManifest(flags: AvlTreeFlags,
                   parameters: PlasmaParameters,
                   manifest: Manifest): Either[Throwable, PlasmaDictionary] =
    try {
      val loaded = PlasmaMap[Array[Byte], Array[Byte]](flags, parameters).loadManifest(manifest)
      // Required by the toolkit after loadManifest, and what PlasmaMap.copy does for the same reason:
      // without it the reconstructed prover produces proofs that differ from an equivalent map's, so a
      // submission built from restored state carries a proof its contract rejects.
      loaded.prover.generateProof()
      if (!java.util.Arrays.equals(loaded.digest, manifest.digest))
        Left(new IllegalArgumentException("Loaded dictionary digest does not match its manifest"))
      else Right(new PlasmaDictionary(loaded, manifestWeight(manifest)))
    } catch {
      case t: Throwable => Left(t)
    }

  private val EmptyWeight = 4096L
  private val EntryOverhead = 256L
  private val SerializedToHeapMultiplier = 4L

  private[states] def mutationWeight(bytes: Long): Long =
    saturatingAdd(EntryOverhead, saturatingMultiply(bytes, SerializedToHeapMultiplier))

  private[states] def manifestWeight(manifest: Manifest): Long = {
    val serialized = manifest.subTrees.foldLeft(
      saturatingAdd(manifest.digest.length.toLong, manifest.bytes.length.toLong)) {
      case (total, subtree) => saturatingAdd(total, subtree.length.toLong)
    }
    math.max(EmptyWeight, saturatingMultiply(serialized, SerializedToHeapMultiplier))
  }

  private[states] def saturatingAdd(left: Long, right: Long): Long =
    if (left >= Long.MaxValue - right) Long.MaxValue else left + right

  private def saturatingMultiply(value: Long, multiplier: Long): Long =
    if (value == 0L || multiplier == 0L) 0L
    else if (value > Long.MaxValue / multiplier) Long.MaxValue
    else value * multiplier

}
