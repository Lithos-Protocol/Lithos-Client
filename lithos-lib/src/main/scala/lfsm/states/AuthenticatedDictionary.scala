package lfsm.states

import org.ergoplatform.appkit.ErgoValue
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

  def copy(): AuthenticatedDictionary
  def getManifest(depth: Int = 0): Manifest
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
  /** Compatibility conversion for callers constructing state from Plasma Toolkit fixtures. */
  implicit def fromPlasmaMap(map: PlasmaMap[Array[Byte], Array[Byte]]): AuthenticatedDictionaryView =
    PlasmaDictionary.wrap(map)
}

final class PlasmaDictionary private (private val map: PlasmaMap[Array[Byte], Array[Byte]])
  extends AuthenticatedDictionary {

  override def digest: Array[Byte] = map.digest.clone()
  override def ergoValue: ErgoValue[AvlTree] = map.ergoValue
  override def flags: AvlTreeFlags = map.flags
  override def parameters: PlasmaParameters = map.params

  override def insert(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]] =
    map.insert(entries: _*)

  override def update(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]] =
    map.update(entries: _*)

  override def delete(keys: Array[Byte]*): ProvenResult[Array[Byte]] = map.delete(keys: _*)

  override def lookUp(keys: Array[Byte]*): ProvenResult[Array[Byte]] = map.lookUp(keys: _*)

  override def insertOrUpdate(entries: (Array[Byte], Array[Byte])*): ProvenResult[Array[Byte]] =
    map.insertOrUpdate(entries: _*)

  override def generateProof(): Array[Byte] = map.prover.generateProof()

  override def copy(): AuthenticatedDictionary = new PlasmaDictionary(map.copy())

  override def getManifest(depth: Int): Manifest = map.getManifest(depth)
}

object PlasmaDictionary {
  private[states] def wrap(map: PlasmaMap[Array[Byte], Array[Byte]]): PlasmaDictionary =
    new PlasmaDictionary(map)

  def empty(flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed,
            parameters: PlasmaParameters = PlasmaParameters.default): PlasmaDictionary =
    new PlasmaDictionary(PlasmaMap[Array[Byte], Array[Byte]](flags, parameters))

  def fromManifest(flags: AvlTreeFlags,
                   parameters: PlasmaParameters,
                   manifest: Manifest): Either[Throwable, PlasmaDictionary] =
    try {
      val loaded = PlasmaMap[Array[Byte], Array[Byte]](flags, parameters).loadManifest(manifest)
      if (!java.util.Arrays.equals(loaded.digest, manifest.digest))
        Left(new IllegalArgumentException("Loaded dictionary digest does not match its manifest"))
      else Right(new PlasmaDictionary(loaded))
    } catch {
      case t: Throwable => Left(t)
    }

}
