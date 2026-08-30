package state.synchronization

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.{ForkedJvm, ProductionBudgetMetrics}

import scala.concurrent.duration._

/**
 * Opt-in fixed-heap production-shape measurements.
 *
 * Run `productionBudgetTest`. Override profiles or scenarios with, for example:
 * `set Test / javaOptions += "-Dlithos.productionBudget.heapsMiB=1024,2048"` and
 * `set Test / javaOptions += "-Dlithos.productionBudget.scenarios=mempool,dictionary"`.
 * `operationalQualificationTest` runs the deliberately heavy large-dictionary fanout profile.
 */
class SynchronizationProductionBudgetSpec extends AnyFlatSpec with Matchers {
  private val heaps = System.getProperty("lithos.productionBudget.heapsMiB",
    "1024,2048,4096,8192").split(',').iterator.map(_.trim).filter(_.nonEmpty).map(_.toInt).toVector
  private val scenarios = System.getProperty("lithos.productionBudget.scenarios", "all")

  "The synchronization production-budget matrix" should
    "run every selected workload in isolated fixed-heap JVMs" in {
      heaps should not be empty
      heaps.foreach { heapMiB =>
        val result = ForkedJvm.run("support.SyncProductionBudgetWorker", heapMiB,
          Seq(scenarios), 30.minutes,
          Seq(s"-Dlithos.productionBudget.report=${ProductionBudgetMetrics.reportPath}"))
        withClue(s"fixed ${heapMiB}MiB heap exited ${result.exitCode} after " +
          s"${result.elapsedMillis}ms:\n${result.output.takeRight(12000)}") {
          result.exitCode shouldEqual 0
          result.output should include("PRODUCTION_BUDGET_COMPLETED")
        }
        ProductionBudgetMetrics.record("sync-fixed-heap", s"heap-${heapMiB}MiB",
          "heapMiB" -> heapMiB,
          "scenarios" -> scenarios,
          "childElapsedMillis" -> result.elapsedMillis,
          "exitCode" -> result.exitCode)
      }
    }
}
