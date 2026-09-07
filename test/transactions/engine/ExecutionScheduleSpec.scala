package transactions.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExecutionScheduleSpec extends AnyFlatSpec with Matchers {
  import ExecutionSchedule._
  "The execution schedule" should "defer future work and preserve critical capacity while optional work runs" in {
    val schedule = new ExecutionSchedule[String]()
    schedule.register(Entry("later", "later", "optional", Scheduled(100, 1000), Automatic)) shouldBe true
    schedule.next("optional", 99) shouldBe None
    schedule.next("optional", 100).get.work shouldBe "later"
    schedule.register(Entry("critical", "proof", "critical", Immediate(1000), Critical)) shouldBe true
    schedule.next("critical", 100).get.work shouldBe "proof"
    schedule.next("optional", 101) shouldBe None
  }
  it should "back off without retaining an active worker and reject obsolete completions" in {
    val schedule = new ExecutionSchedule[String]()
    schedule.register(Entry("work", "work", "optional", Immediate(100000), Automatic))
    val first = schedule.next("optional", 0).get
    schedule.finish("work", first.attempt.get, retry = true, 1)
    schedule.busy("optional") shouldBe false
    schedule.next("optional", 15000) shouldBe None
    val second = schedule.next("optional", 15001).get
    schedule.finish("work", first.attempt.get, retry = false, 15002) shouldBe None
    schedule.busy("optional") shouldBe true
    schedule.finish("work", second.attempt.get, retry = false, 15003)
    schedule.contains("work") shouldBe false
  }
  it should "expire queued work without expiring an outstanding send" in {
    val schedule = new ExecutionSchedule[String]()
    schedule.register(Entry("active", "active", "optional", Immediate(10), Automatic))
    schedule.register(Entry("queued", "queued", "optional", Immediate(10), Automatic))
    schedule.next("optional", 0)
    schedule.expire(10).map(_.key) shouldBe Vector("queued")
    schedule.contains("active") shouldBe true
  }
  it should "never retry a one-off API request" in {
    val schedule = new ExecutionSchedule[String]()
    schedule.register(Entry("api", "api", "optional", Immediate(100), OneOff))
    val entry = schedule.next("optional", 0).get
    schedule.finish("api", entry.attempt.get, retry = true, 1)
    schedule.contains("api") shouldBe false
  }
}
