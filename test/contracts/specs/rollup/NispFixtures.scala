package contracts.specs.rollup

import nisp.{SuperShare, TransactionProof}
import org.ergoplatform.{AutolykosSolution, ErgoHeader, HeaderWithoutPow}
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.bytesToId
import scorex.utils.{Ints, Longs}
import sigma.crypto.Platform
import sigma.pow.Autolykos2PowValidation

/**
 * Synthetic NISP data for the fraud-proof specs.
 *
 * A NISP is `[score: 8 BE bytes][10 super-shares]`, and each super-share is
 * `[N: 4][header][txProofSize: 2][numLevels: 1][txProof][levels: 33n][yCoord: 32]`. The fraud proofs
 * parse that layout byte by byte, so these fixtures go through the real serializers rather than
 * faking the bytes.
 */
object NispFixtures {

  final val LevelSize = 33
  /** TransactionProof.deserialize requires tx > TX_SIZE_MIN (450) and the whole proof >= 502. */
  final val TxBytesSize = 480
  final val CollateralBoxSize = 64

  private def filler(size: Int, seed: Byte): Array[Byte] = Array.fill(size)(seed)

  private def digest32(seed: String): Digest32 = Blake2b256.hash(seed)

  def pkOf(prover: org.ergoplatform.appkit.ErgoProver): Platform.Ecp =
    prover.getAddress.getPublicKey.value

  /** A header at `height`, carrying `pk` as the Autolykos miner key. Version 4 is what is mined. */
  def header(height: Int,
             pk: Platform.Ecp,
             timestamp: Long = 1700000000000L,
             txRoot: Digest32 = digest32("txRoot"),
             nonce: Array[Byte] = Array.fill(8)(0.toByte),
             version: Byte = 4.toByte): ErgoHeader = {
    val withoutPow = new HeaderWithoutPow(
      version,
      bytesToId(Array.fill(32)(0.toByte)),
      digest32("adProofsRoot"),
      ADDigest @@ (digest32("stateRoot") ++ Array(0.toByte)),
      txRoot,
      timestamp,
      117825982L,
      height,
      digest32("extensionRoot"),
      Array(0.toByte, 0.toByte, 0.toByte),
      Array.emptyByteArray
    )
    withoutPow.toHeader(new AutolykosSolution(pk, pk, nonce, BigInt(0)), null)
  }

  def txProof(seed: Byte = 1.toByte): TransactionProof =
    TransactionProof(filler(TxBytesSize, seed), filler(CollateralBoxSize, seed))

  def levels(count: Int, seed: Byte = 2.toByte): Seq[Array[Byte]] =
    (0 until count).map(i => filler(LevelSize, (seed + i).toByte))

  def superShare(height: Int,
                 pk: Platform.Ecp,
                 numLevels: Int = 2,
                 timestamp: Long = 1700000000000L,
                 txRoot: Digest32 = digest32("txRoot"),
                 proof: Option[TransactionProof] = None,
                 nonce: Array[Byte] = Array.fill(8)(0.toByte)): SuperShare =
    SuperShare(
      header(height, pk, timestamp, txRoot, nonce).bytes,
      proof.getOrElse(txProof()),
      levels(numLevels)
    )

  /**
   * A share whose Autolykos2 hit clears `threshold`, found by walking nonces upward from `from`.
   *
   * A real super-share is found by mining, so a spec that needs one has to mine it. The search is
   * deterministic — the same header and starting nonce always land on the same share — which is why
   * a caller pins the nonce it found and pays nothing on later runs.
   */
  def minedShare(height: Int,
                 pk: Platform.Ecp,
                 threshold: BigInt,
                 timestamp: Long = 1700000000000L,
                 from: Long = 0L,
                 tries: Int = 400000): (SuperShare, Long) = {
    var n = from
    while (n < from + tries) {
      val candidate = superShare(height, pk, timestamp = timestamp, nonce = Longs.toByteArray(n))
      if (candidate.powHit <= threshold) return (candidate, n)
      n += 1
    }
    throw new IllegalStateException(
      s"no nonce in [$from, ${from + tries}) reaches a hit below $threshold")
  }

  /** `[score][shares...]`, the layout every fraud proof parses. */
  def nisp(score: Long, shares: Seq[SuperShare]): Array[Byte] =
    Longs.toByteArray(score) ++ shares.flatMap(_.serialize)

  // ─── raw assembly, for headers the client's own deserializer would reject ──
  //
  // `SuperShare` derives its N and yCoord fields by deserializing the header, so it cannot hold one
  // that does not parse. An attacker is under no such constraint: the NISP is just bytes in a tree.
  // These build the same layout from parts, so a spec can pose a header the contracts must cope with.

  /** `[score][shares...]` over already-serialized shares. */
  def rawNisp(score: Long, shares: Seq[Array[Byte]]): Array[Byte] =
    Longs.toByteArray(score) ++ shares.flatten

  /** `base.serialize` with its header swapped out, keeping every other field intact. */
  def shareWithHeader(base: SuperShare, headerBytes: Array[Byte]): Array[Byte] =
    base.nBytes ++ headerBytes ++ scorex.utils.Shorts.toByteArray(base.txProof.size.toShort) ++
      Array(base.levels.length.toByte) ++ base.txProof.serialize ++ base.levels.flatten ++ base.yCoord

  /** Length of the VLQ starting at `at`: bytes with the high bit set continue it. */
  def vlqLength(bytes: Array[Byte], at: Int): Int = {
    var i = at
    while (bytes(i) < 0) i += 1
    i - at + 1
  }

  /** Where the height VLQ starts: the fixed 130-byte prefix, the timestamp VLQ, then 36 fixed bytes. */
  def heightVlqOffset(headerBytes: Array[Byte]): Int =
    130 + vlqLength(headerBytes, 130) + 36

  /** The same header with its height re-encoded as the given raw VLQ. */
  def withHeightVlq(headerBytes: Array[Byte], vlq: Array[Byte]): Array[Byte] = {
    val at = heightVlqOffset(headerBytes)
    headerBytes.slice(0, at) ++ vlq ++ headerBytes.drop(at + vlqLength(headerBytes, at))
  }

  /** The same header under a different protocol version. Byte 0, and nothing else moves. */
  def withVersion(headerBytes: Array[Byte], version: Byte): Array[Byte] =
    version +: headerBytes.tail

  /** The byte that says how many unparsed bytes follow, three back from the miner key. */
  def unparsedSizeOffset(headerBytes: Array[Byte]): Int = headerBytes.length - 42

  def withUnparsedSize(headerBytes: Array[Byte], size: Byte): Array[Byte] = {
    val at = unparsedSizeOffset(headerBytes)
    headerBytes.slice(0, at) ++ Array(size) ++ headerBytes.drop(at + 1)
  }

  /** Ten shares all at the same height — the shape of an honest NISP. */
  def cleanShares(height: Int, pk: Platform.Ecp, count: Int = 10): Seq[SuperShare] =
    (0 until count).map(i => superShare(height - i, pk, timestamp = 1700000000000L + i))

  def calcN(height: Int): Int = Autolykos2PowValidation.calcN(height)

  // ─── patching a serialized share ──────────────────────────────────────────
  //
  // `SuperShare` recomputes N and yCoord from its header on construction, so it cannot hold a share
  // whose stored fields disagree with it — which is exactly what several proofs exist to catch. These
  // patch the serialized bytes instead, leaving every other field where it was.

  private def patch(bytes: Array[Byte], at: Int, replacement: Array[Byte]): Array[Byte] =
    bytes.slice(0, at) ++ replacement ++ bytes.drop(at + replacement.length)

  /** The header's 33 compressed miner-key bytes sit 41 back from its end. */
  def withGroupElement(headerBytes: Array[Byte], ge: Array[Byte]): Array[Byte] = {
    require(ge.length == 33, "a compressed group element is 33 bytes")
    patch(headerBytes, headerBytes.length - 41, ge)
  }

  def groupElementOf(headerBytes: Array[Byte]): Array[Byte] =
    headerBytes.slice(headerBytes.length - 41, headerBytes.length - 8)

  /** The share's declared N, which `FP_IncorrectN` checks against the height in its header. */
  def withN(shareBytes: Array[Byte], n: Int): Array[Byte] =
    patch(shareBytes, 0, Ints.toByteArray(n))

  /** The share's trailing y-coordinate, which `FP_MalformedGE` reads instead of decompressing x. */
  def withYCoord(shareBytes: Array[Byte], y: Array[Byte]): Array[Byte] = {
    require(y.length == 32, "a y coordinate is 32 bytes")
    patch(shareBytes, shareBytes.length - 32, y)
  }

  // ─── merkle proofs that actually verify ───────────────────────────────────

  /** `blake2b256(0x00 ++ blake2b256(tx))`, the leaf `FP_TransactionNotIncluded` derives. */
  def leafHash(tx: Array[Byte]): Array[Byte] = Blake2b256.hash(0.toByte +: Blake2b256.hash(tx))

  /**
   * The transactions root a proof of `tx` through `levels` derives.
   *
   * Each level is `[side: 1][sibling: 32]`, side 0 meaning the sibling goes on the right. Mirrors the
   * fold in the contract, so a header carrying this root is one the proof accepts.
   */
  def merkleRoot(tx: Array[Byte], levels: Seq[Array[Byte]]): Array[Byte] =
    levels.foldLeft(leafHash(tx)) { (acc, lvl) =>
      val sibling = lvl.drop(1)
      val joined = if (lvl(0) == 0.toByte) acc ++ sibling else sibling ++ acc
      Blake2b256.hash(1.toByte +: joined)
    }

  /** A share whose header commits to its own transaction proof, so the inclusion proof verifies. */
  def includedShare(height: Int,
                    pk: Platform.Ecp,
                    numLevels: Int = 2,
                    timestamp: Long = 1700000000000L,
                    seed: Byte = 1.toByte): SuperShare = {
    val proof = txProof(seed)
    val lvls = levels(numLevels)
    val root = Digest32 @@ merkleRoot(proof.tx, lvls)
    SuperShare(header(height, pk, timestamp, root).bytes, proof, lvls)
  }

  /** Ten shares, each proving its own transaction against its own header. */
  def includedShares(height: Int, pk: Platform.Ecp, count: Int = 10): Seq[SuperShare] =
    (0 until count).map(i =>
      includedShare(height - i, pk, timestamp = 1700000000000L + i, seed = (1 + i).toByte))
}
