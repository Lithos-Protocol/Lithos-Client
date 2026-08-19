package contracts.specs.rollup

import nisp.SuperShare
import org.scalatest.propspec.AnyPropSpec

/**
 * Sanity checks on the synthetic NISP bytes before the fraud-proof specs rely on them.
 * If a super-share does not round-trip through the client's own deserializer, no fraud proof will
 * be able to parse it either.
 */
class NispFixtureProbeSpec extends AnyPropSpec with RollupSpecBase {

  property("a synthetic super-share round-trips through SuperShare.deserialize") {
    withCtx { ctx =>
      val pk = NispFixtures.pkOf(miner(ctx))
      val share = NispFixtures.superShare(1000, pk)
      val bytes = share.serialize
      println(s"[fixture] share size=${bytes.length} header=${share.headerBytes.length} " +
        s"yCoord=${share.yCoord.length} n=${share.getN} height=${share.getHeight}")

      val parsed = SuperShare.deserialize(bytes)
      parsed.getHeight shouldBe 1000
      parsed.serialize.length shouldBe bytes.length
      parsed.id shouldBe share.id
    }
  }

  property("ten shares round-trip as a sequence") {
    withCtx { ctx =>
      val pk = NispFixtures.pkOf(miner(ctx))
      val shares = NispFixtures.cleanShares(2000, pk)
      val joined = shares.flatMap(_.serialize).toArray
      val parsed = SuperShare.deserializeMany(joined)
      parsed.size shouldBe 10
      parsed.map(_.getHeight) shouldBe shares.map(_.getHeight)
      println(s"[fixture] 10-share NISP payload=${joined.length} bytes")
    }
  }

  property("a full NISP lands inside Holding's size bounds") {
    withCtx { ctx =>
      val pk = NispFixtures.pkOf(miner(ctx))
      val bytes = NispFixtures.nisp(5000L, NispFixtures.cleanShares(2000, pk))
      println(s"[fixture] full NISP=${bytes.length} bytes (NISP_MIN=7948, NISP_MAX=26000)")
      bytes.length should be >= 7948
      bytes.length should be < 26000
    }
  }
}
