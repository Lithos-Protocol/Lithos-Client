package contracts.specs.rollup

import nisp.{SuperShare, TransactionProof}
import org.ergoplatform.{AutolykosSolution, ErgoHeader, HeaderWithoutPow}
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.bytesToId
import scorex.utils.Longs
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

  /** A header at `height`, carrying `pk` as the Autolykos miner key. */
  def header(height: Int,
             pk: Platform.Ecp,
             timestamp: Long = 1700000000000L,
             txRoot: Digest32 = digest32("txRoot"),
             nonce: Array[Byte] = Array.fill(8)(0.toByte),
             version: Byte = 3.toByte): ErgoHeader = {
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
                 proof: Option[TransactionProof] = None): SuperShare =
    SuperShare(
      header(height, pk, timestamp, txRoot).bytes,
      proof.getOrElse(txProof()),
      levels(numLevels)
    )

  /** `[score][shares...]`, the layout every fraud proof parses. */
  def nisp(score: Long, shares: Seq[SuperShare]): Array[Byte] =
    Longs.toByteArray(score) ++ shares.flatMap(_.serialize)

  /** Ten shares all at the same height — the shape of an honest NISP. */
  def cleanShares(height: Int, pk: Platform.Ecp, count: Int = 10): Seq[SuperShare] =
    (0 until count).map(i => superShare(height - i, pk, timestamp = 1700000000000L + i))

  def calcN(height: Int): Int = Autolykos2PowValidation.calcN(height)
}
