package nisp

import lfsm.LFSMHelpers
import nisp.NISPDatabase.{CURRENT_HEIGHT, LAST_HEIGHT, NISP_DIR}
import scorex.crypto.hash.Blake2b256
import scorex.utils.Ints
import storage.KeyValueMutation.{Delete, Put}
import storage.{KeyValueMutation, KeyValueReadView, KeyValueStore, LevelDbKeyValueStore, StoreError}

import java.nio.file.Paths

class NISPDatabase(private val kvstore: KeyValueStore) extends NISPStorage {

  def this() = this(LevelDbKeyValueStore.openOrThrow(Paths.get(NISP_DIR)))

  def getAll: Seq[(Array[Byte], Array[Byte])] =
    KeyValueStore.orThrow(kvstore.scanPrefix(Array.emptyByteArray))

  def size: Int = getAll.size

  /**
   * Adds SuperShare to create new NISP at given height, or adds the share to an existing NISP
   * @param score Score associated with the share, only used in new insertions
   * @param shareBytes Bytes of SuperShare to add to database
   * @return Whether all operations returned successfully
   */
  def addNISP(score: Long, shareBytes: Array[Byte]): Boolean = {
    addNISP(score, SuperShare.deserialize(shareBytes))
  }

  /**
   * Adds SuperShare to create new NISP at given height, or adds the share to an existing NISP
   * @param score Score associated with the share, only used in new insertions
   * @param share Share to add to database
   * @return Whether all operations returned successfully
   */
  def addNISP(score: Long, share: SuperShare): Boolean = synchronized {
    val header = share.toHeader
    val heightKey = Ints.toByteArray(header.height)
    val (storedNisp, storedLastHeight, storedCurrentHeight) = KeyValueStore.orThrow {
      kvstore.readSnapshot { view =>
        for {
          nisp <- view.get(heightKey)
          last <- view.get(LAST_HEIGHT)
          current <- view.get(CURRENT_HEIGHT)
        } yield (nisp, last, current)
      }
    }

    val serialized = storedNisp match {
      case Some(bytes) =>
        val nextNisp = NISP.deserialize(bytes).withSuperShare(share)
        require(nextNisp.isDistinct, "Cannot store non-distinct NISP")
        nextNisp.serialize
      case None =>
        NISP(score, Seq(share)).serialize
    }

    val mutations = Vector.newBuilder[KeyValueMutation]
    mutations += Put(heightKey, serialized)
    if (storedLastHeight.isEmpty)
      mutations += Put(LAST_HEIGHT, heightKey)
    if (storedCurrentHeight.forall(bytes => Ints.fromByteArray(bytes) < header.height))
      mutations += Put(CURRENT_HEIGHT, heightKey)

    kvstore.write(mutations.result()).isRight
  }

  /**
   * Remove NISPs from the db until the given height (exclusive)
   * @param height Threshold height such that all NISPs under this height are removed
   */
  def removeUntil(height: Int): Boolean = synchronized {
    val state = KeyValueStore.orThrow {
      kvstore.readSnapshot { view =>
        for {
          current <- view.get(CURRENT_HEIGHT)
          last <- view.get(LAST_HEIGHT)
          plan <- planRemoval(view, current, last, height)
        } yield (current, last, plan)
      }
    }

    val (currentHeightValue, lastHeightValue, plan) = state
    if (currentHeightValue.isEmpty || height > Ints.fromByteArray(currentHeightValue.get)) {
      throw new Exception(s"Cannot remove NISPs until height $height due to currHeight " +
        s"$currentHeightValue being too small or undefined")
    }
    if (lastHeightValue.isEmpty)
      throw new Exception("Cannot remove NISPs because lastHeight is undefined")

    if (plan.removals.isEmpty) true
    else kvstore.write(removalMutations(plan)).isRight
  }

  def removeLastNISP: Boolean = synchronized {
    val state = KeyValueStore.orThrow {
      kvstore.readSnapshot { view =>
        for {
          last <- view.get(LAST_HEIGHT)
          current <- view.get(CURRENT_HEIGHT)
          next <- nextHeightFor(view, last, current)
        } yield (last, current, next)
      }
    }

    val (lastHeightValue, currentHeightValue, nextHeight) = state
    val last = lastHeightValue.getOrElse {
      throw new Exception("Failed to remove last NISP because lastHeight was not defined")
    }
    if (currentHeightValue.isEmpty)
      throw new Exception("Can't get next height when current height is undefined.")

    val metadata = nextHeight match {
      case Some(next) => Vector[KeyValueMutation](Put(LAST_HEIGHT, next))
      case None => Vector[KeyValueMutation](Delete(LAST_HEIGHT), Delete(CURRENT_HEIGHT))
    }
    kvstore.write(Delete(last) +: metadata).isRight
  }

  /** Gets the next height containing a NISP after the supplied height. */
  def getNextHeight(start: Array[Byte]): Option[Array[Byte]] = {
    val startHeight = Ints.fromByteArray(start)
    val result = KeyValueStore.orThrow {
      kvstore.readSnapshot { view =>
        view.get(CURRENT_HEIGHT).flatMap {
          case Some(current) => findNextHeight(view, startHeight, Ints.fromByteArray(current)).map(Some(_))
          case None => Right(None)
        }
      }
    }
    result.getOrElse(throw new Exception("Can't get next height when current height is undefined."))
  }

  def lastHeight: Option[Array[Byte]] =
    KeyValueStore.orThrow(kvstore.get(LAST_HEIGHT))

  def currentHeight: Option[Array[Byte]] =
    KeyValueStore.orThrow(kvstore.get(CURRENT_HEIGHT))

  def getNISPBytes(height: Int): Option[Array[Byte]] =
    KeyValueStore.orThrow(kvstore.get(Ints.toByteArray(height)))

  def getNISP(height: Int): Option[NISP] =
    getNISPBytes(height).map(NISP.deserialize)

  /**
   * Gets the best valid NISP before a given height and above a given score. If a NISP with 10 super-shares cannot be
   * made, `None` is returned.
   *
   * NOTE: The passed in height should correspond to periodStart set on the holding contract.
   */
  def getBestValidNISP(height: Int, score: Long): Option[NISP] = {
    val snapshot = KeyValueStore.orThrow {
      kvstore.readSnapshot { view =>
        view.get(LAST_HEIGHT).flatMap {
          case Some(last) =>
            val minHeight = height - LFSMHelpers.NISP_WINDOW.toInt
            val start = Math.max(minHeight, Ints.fromByteArray(last))
            readNispRange(view, start, height).map(values => Some(values))
          case None => Right(None)
        }
      }
    }
    require(snapshot.isDefined, "Cannot search for NISPs when lastHeight is undefined")

    val validNisps = snapshot.get.flatten.map(NISP.deserialize).filter(_.score >= score)
    val bestNisp = validNisps.foldLeft(Option.empty[NISP]) {
      (best: Option[NISP], candidate: NISP) =>
        if (best.isEmpty) {
          Some(candidate.copy(score = score, shares = makeUnique(candidate.shares)))
        } else if (best.get.shares.size >= 10) {
          Some(best.get.copy(score = score))
        } else {
          val uniqueShares = makeUnique(candidate.shares)
            .filter(share => !best.get.shares.exists(existing => existing.headerBytes sameElements share.headerBytes))
          Some(best.get.copy(score = score, shares = best.get.shares ++ uniqueShares))
        }
    }
    bestNisp.filter(_.shares.size >= 10).map(nisp => nisp.copy(shares = nisp.shares.take(10)))
  }

  def makeUnique(shares: Seq[SuperShare]): Seq[SuperShare] = {
    shares.foldLeft(Seq.empty[SuperShare]) {
      (unique, share) =>
        if (unique.exists(existing => existing.headerBytes sameElements share.headerBytes)) unique
        else unique :+ share
    }
  }

  def updateLastHeight(newLastHeight: Array[Byte], storedLastHeight: Option[Array[Byte]]): Boolean = synchronized {
    replaceHeight(LAST_HEIGHT, newLastHeight, storedLastHeight)
  }

  def updateCurrentHeight(newCurrHeight: Array[Byte], storedCurrHeight: Option[Array[Byte]]): Boolean = synchronized {
    replaceHeight(CURRENT_HEIGHT, newCurrHeight, storedCurrHeight)
  }

  def close(): Unit = KeyValueStore.orThrow(kvstore.close())

  private def replaceHeight(key: Array[Byte], value: Array[Byte], stored: Option[Array[Byte]]): Boolean = {
    val mutations = stored match {
      case Some(_) => Vector[KeyValueMutation](Delete(key), Put(key, value))
      case None => Vector[KeyValueMutation](Put(key, value))
    }
    kvstore.write(mutations).isRight
  }

  private def nextHeightFor(view: KeyValueReadView,
                            last: Option[Array[Byte]],
                            current: Option[Array[Byte]]): Either[StoreError, Option[Array[Byte]]] =
    (last, current) match {
      case (Some(lastBytes), Some(currentBytes)) =>
        findNextHeight(view, Ints.fromByteArray(lastBytes), Ints.fromByteArray(currentBytes))
      case _ => Right(None)
    }

  private def findNextHeight(view: KeyValueReadView,
                             startHeight: Int,
                             currentHeightValue: Int): Either[StoreError, Option[Array[Byte]]] = {
    var height = startHeight.toLong + 1L
    val maximum = currentHeightValue.toLong
    var found: Option[Array[Byte]] = None
    var failure: Option[StoreError] = None
    while (height <= maximum && found.isEmpty && failure.isEmpty) {
      val key = Ints.toByteArray(height.toInt)
      view.get(key) match {
        case Right(Some(_)) => found = Some(key)
        case Right(None) => height += 1L
        case Left(error) => failure = Some(error)
      }
    }
    failure.map(Left(_)).getOrElse(Right(found))
  }

  private def planRemoval(view: KeyValueReadView,
                          current: Option[Array[Byte]],
                          last: Option[Array[Byte]],
                          threshold: Int): Either[StoreError, RemovalPlan] =
    (current, last) match {
      case (Some(currentBytes), Some(lastBytes)) if threshold <= Ints.fromByteArray(currentBytes) =>
        scanRemovalRange(view, Ints.fromByteArray(lastBytes), Ints.fromByteArray(currentBytes), threshold)
      case _ => Right(RemovalPlan(Vector.empty, None))
    }

  private def scanRemovalRange(view: KeyValueReadView,
                               firstHeight: Int,
                               currentHeightValue: Int,
                               threshold: Int): Either[StoreError, RemovalPlan] = {
    val removals = Vector.newBuilder[Array[Byte]]
    var nextLast: Option[Array[Byte]] = None
    var height = firstHeight.toLong
    val maximum = currentHeightValue.toLong
    var failure: Option[StoreError] = None
    while (height <= maximum && nextLast.isEmpty && failure.isEmpty) {
      val key = Ints.toByteArray(height.toInt)
      view.get(key) match {
        case Right(Some(_)) if height < threshold.toLong => removals += key
        case Right(Some(_)) => nextLast = Some(key)
        case Right(None) => ()
        case Left(error) => failure = Some(error)
      }
      height += 1L
    }
    failure.map(Left(_)).getOrElse(Right(RemovalPlan(removals.result(), nextLast)))
  }

  private def removalMutations(plan: RemovalPlan): Vector[KeyValueMutation] = {
    val removals = plan.removals.map(Delete)
    plan.nextLast match {
      case Some(next) => removals :+ Put(LAST_HEIGHT, next)
      case None => removals ++ Vector(Delete(LAST_HEIGHT), Delete(CURRENT_HEIGHT))
    }
  }

  private def readNispRange(view: KeyValueReadView,
                            startHeight: Int,
                            endHeight: Int): Either[StoreError, Vector[Option[Array[Byte]]]] = {
    val values = Vector.newBuilder[Option[Array[Byte]]]
    var height = startHeight.toLong
    val maximum = endHeight.toLong
    var failure: Option[StoreError] = None
    while (height <= maximum && failure.isEmpty) {
      view.get(Ints.toByteArray(height.toInt)) match {
        case Right(value) => values += value
        case Left(error) => failure = Some(error)
      }
      height += 1L
    }
    failure.map(Left(_)).getOrElse(Right(values.result()))
  }

  private final case class RemovalPlan(removals: Vector[Array[Byte]], nextLast: Option[Array[Byte]])
}

object NISPDatabase {
  final val NISP_DIR = ".lithos/nisp"
  final val LAST_HEIGHT = Blake2b256.hash("LAST_HEIGHT")
  final val CURRENT_HEIGHT = Blake2b256.hash("CURRENT_HEIGHT")
}
