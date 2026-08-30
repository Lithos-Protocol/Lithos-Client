package support

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** Measurement support used only by the opt-in production-budget suites. */
object ProductionBudgetMetrics {
  final case class GcSnapshot(collections: Long, milliseconds: Long)
  final case class HeapSnapshot(used: Long, committed: Long, maximum: Long)
  final case class Timed[A](value: A, elapsedNanos: Long,
                            heapBefore: HeapSnapshot, heapAfter: HeapSnapshot,
                            gcBefore: GcSnapshot, gcAfter: GcSnapshot)

  private val reportLock = new Object

  def heap: HeapSnapshot = {
    val usage = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    HeapSnapshot(usage.getUsed, usage.getCommitted, usage.getMax)
  }

  def gc: GcSnapshot = {
    val beans = ManagementFactory.getGarbageCollectorMXBeans.asScala
    GcSnapshot(
      beans.iterator.map(bean => math.max(0L, bean.getCollectionCount)).sum,
      beans.iterator.map(bean => math.max(0L, bean.getCollectionTime)).sum)
  }

  def timed[A](operation: => A): Timed[A] = {
    val heapBefore = heap
    val gcBefore = gc
    val started = System.nanoTime()
    val value = operation
    val elapsed = System.nanoTime() - started
    Timed(value, elapsed, heapBefore, heap, gcBefore, gc)
  }

  /** Best-effort stabilization for a forked measurement JVM, never used as a correctness oracle. */
  def stabilizeHeap(rounds: Int = 4): HeapSnapshot = {
    var previous = Long.MaxValue
    var current = heap
    var remaining = rounds
    while (remaining > 0 && current.used < previous) {
      previous = current.used
      System.gc()
      System.runFinalization()
      Thread.sleep(50L)
      current = heap
      remaining -= 1
    }
    current
  }

  def record(suite: String, scenario: String, metrics: (String, Any)*): Unit = {
    val base = Vector[(String, Any)](
      "timestamp" -> Instant.now().toString,
      "suite" -> suite,
      "scenario" -> scenario,
      "javaVersion" -> System.getProperty("java.version"),
      "vmName" -> System.getProperty("java.vm.name"),
      "availableProcessors" -> Runtime.getRuntime.availableProcessors(),
      "gcCollectors" -> ManagementFactory.getGarbageCollectorMXBeans.asScala
        .map(_.getName).mkString(","),
      "maxHeapBytes" -> Runtime.getRuntime.maxMemory()) ++ metrics
    val line = base.map { case (key, value) =>
      "\"" + escape(key) + "\":" + json(value)
    }.mkString("{", ",", "}")
    println(s"[production-budget] $line")
    reportLock.synchronized {
      val path = reportPath
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.write(path, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
  }

  def reportPath: Path = Paths.get(System.getProperty(
    "lithos.productionBudget.report", "target/production-budget/measurements.jsonl"))
    .toAbsolutePath.normalize()

  private def json(value: Any): String = value match {
    case null => "null"
    case value: Boolean => value.toString
    case value: Byte => value.toString
    case value: Short => value.toString
    case value: Int => value.toString
    case value: Long => value.toString
    case value: Float if value.isNaN || value.isInfinite => "\"" + value + "\""
    case value: Double if value.isNaN || value.isInfinite => "\"" + value + "\""
    case value: Float => value.toString
    case value: Double => value.toString
    case value => "\"" + escape(value.toString) + "\""
  }

  private def escape(value: String): String = value.flatMap {
    case '\\' => "\\\\"
    case '"' => "\\\""
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case character => character.toString
  }
}

object ForkedJvm {
  final case class Result(exitCode: Int, output: String, elapsedMillis: Long)

  def run(mainClass: String,
          maximumHeapMiB: Int,
          arguments: Seq[String],
          timeout: FiniteDuration,
          extraJvmArguments: Seq[String] = Seq.empty): Result = {
    val javaExecutable = Paths.get(System.getProperty("java.home"), "bin",
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java")
    val output = Files.createTempFile("lithos-production-budget-child-", ".log")
    val command = Vector(javaExecutable.toString, s"-Xms${maximumHeapMiB}m",
      s"-Xmx${maximumHeapMiB}m") ++ extraJvmArguments ++
      Vector("-cp", System.getProperty("java.class.path"), mainClass) ++ arguments
    val started = System.nanoTime()
    val process = new ProcessBuilder(command: _*)
      .redirectErrorStream(true)
      .redirectOutput(output.toFile)
      .start()
    val completed = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
    if (!completed) {
      process.destroyForcibly()
      process.waitFor(10L, TimeUnit.SECONDS)
    }
    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    val text = Try(new String(Files.readAllBytes(output), StandardCharsets.UTF_8))
      .getOrElse("<could not read child output>")
    Files.deleteIfExists(output)
    Result(if (completed) process.exitValue() else -1, text, elapsed)
  }
}
