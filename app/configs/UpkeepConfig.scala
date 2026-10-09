package configs

import play.api.{ConfigLoader, Configuration}

import scala.util.{Failure, Success, Try}

/**
 * How this client looks for the boxes its upkeep jobs maintain, and which jobs it runs.
 *
 * Jobs are read generically from `jobs.<name>`, so registering a new job needs no config code: the
 * registry decides what a name means, and config only says whether it runs, which boxes it is told
 * about, and whatever keys of its own the job's factory reads from its block. A name that is not in
 * the registry is caught by validation, not here.
 *
 * @param scanIntervalMs gap between discovery passes. Discovery is background work and must never
 *                       be on the path a block is built on.
 * @param maxBoxesPerJob  box ids one job may hold between passes beyond its configured ones. A job
 *                        that finds more keeps the first this many in its own priority order, so
 *                        one busy protocol cannot grow the actor's memory or the build's node read
 *                        without bound. Validation also holds each configured list to this number
 *                        and to [[UpkeepConfig.MaxConfiguredBoxes]].
 * @param retryAfterScans discovery passes a box whose build was refused sits out before it is
 *                        offered again. A refusal may be the box's own doing or the moment's — a
 *                        node that could not be read — and the source cannot tell which, so it
 *                        retries after this many passes rather than never; a box refused again
 *                        sits out as many again.
 * @param jobs            each configured job by name. Absent is off.
 * @param mode            `candidate` offers what is built to the block; `observe` answers every
 *                        request empty at once and, in a task of its own, builds, sizes and puts
 *                        each successor through the node's transaction check, logging the verdict.
 *                        For an operator with no block yet, who has no other way to see upkeep do
 *                        anything real. Observe keeps no memory: a box candidate mode would set
 *                        aside is rebuilt and logged at every height; a height that arrives while
 *                        the previous task is still running is skipped, not queued. It runs only
 *                        while the stratum builds candidates with `blockTransactions = true`, with
 *                        this source enabled, `maxTxs` above 0, and at least one job enabled.
 * @param verifyWithNode  in candidate mode, put each admitted successor through the node's
 *                        transaction check before offering it, and leave out of that height any
 *                        the node refuses; this is not remembered, and the next height tries the
 *                        box again. A package the node rejects loses every inserted transaction
 *                        with it, so one bad successor would otherwise cost the block the work of
 *                        every source.
 */
case class UpkeepConfig(scanIntervalMs: Int, maxBoxesPerJob: Int, retryAfterScans: Int,
                        jobs: Map[String, UpkeepConfig.Job],
                        mode: String,
                        verifyWithNode: Boolean) {
  def jobEnabled(name: String): Boolean = jobs.get(name).exists(_.enabled)

  def observing: Boolean = mode == UpkeepConfig.Observe
}

object UpkeepConfig {

  /**
   * What validation needs of a registered job: its name, and a check of its own keys that answers
   * (key relative to the job's block, what is wrong). Passed in by the caller so that config
   * validation does not depend on the jobs themselves.
   */
  final case class JobCheck(name: String, check: Configuration => Seq[(String, String)])

  /** Box ids one job may list in config: each is one read on every scan, so the list is bounded. */
  final val MaxConfiguredBoxes = 256

  final val Path = "stratum.candidate.sources.upkeep"

  /** Offer what is built to the block: the default, and the only mode that does anything on chain. */
  final val Candidate = "candidate"

  /** Build and check everything as for a block, log it, and offer nothing. */
  final val Observe = "observe"

  final val Modes: Seq[String] = Seq(Candidate, Observe)

  /**
   * One job's block under `jobs.<name>`: the keys every job may carry, read here once for all of
   * them, and the block itself for the keys only that job's factory knows.
   *
   * @param enabled whether the job runs. Off unless config says otherwise.
   * @param boxIds  boxes to maintain by id, read from the UTXO set on every node, indexed or not;
   *                not a fallback, and never cut at `maxBoxesPerJob`. On a plain node they are the
   *                only boxes a job sees. These are the boxes as they stand: advancing a box gives it
   *                a new id, which a plain node cannot be asked to follow, so the list goes stale
   *                with each beat and has to be refreshed.
   * @param block   the whole block, for the job's own keys
   */
  final case class Job(enabled: Boolean = false, boxIds: Seq[String] = Seq.empty,
                       block: Configuration = Configuration.empty)

  object Job {
    def apply(block: Configuration): Job = Job(
      enabled = block.getOptional("enabled")(ConfigLoader.booleanLoader).getOrElse(false),
      boxIds = block.getOptional("boxIds")(ConfigLoader.seqStringLoader).getOrElse(Seq.empty),
      block = block)
  }

  /**
   * Mirrors the keys of the `stratum.candidate.sources.upkeep` block in `application.conf`; keep them
   * in step. `jobs` is empty here while the shipped block lists the heartbeat, off: a job block is
   * the job's own, read only when present.
   */
  val Default: UpkeepConfig = UpkeepConfig(
    scanIntervalMs = 60000,
    maxBoxesPerJob = 256,
    retryAfterScans = 10,
    jobs = Map.empty,
    mode = Candidate,
    verifyWithNode = true)

  def apply(config: Configuration): UpkeepConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"$Path.$key")(ConfigLoader.intLoader).getOrElse(fallback)

    val jobs = config.getOptional(s"$Path.jobs")(ConfigLoader.configurationLoader)
      .map { block =>
        block.subKeys.map { name =>
          name -> Job(block.getOptional(name)(ConfigLoader.configurationLoader).getOrElse(Configuration.empty))
        }.toMap
      }
      .getOrElse(Map.empty[String, Job])

    UpkeepConfig(
      scanIntervalMs = int("scanIntervalMs", Default.scanIntervalMs),
      maxBoxesPerJob = int("maxBoxesPerJob", Default.maxBoxesPerJob),
      retryAfterScans = int("retryAfterScans", Default.retryAfterScans),
      jobs = jobs,
      mode = config.getOptional(s"$Path.mode")(ConfigLoader.stringLoader).getOrElse(Default.mode),
      verifyWithNode = config.getOptional(s"$Path.verifyWithNode")(ConfigLoader.booleanLoader)
        .getOrElse(Default.verifyWithNode))
  }

  def validate(v: ConfigValidator, config: Configuration, jobChecks: Seq[UpkeepConfig.JobCheck]): Unit = {
    v.range(s"$Path.scanIntervalMs", v.int(s"$Path.scanIntervalMs"), 1000, 3600000, "ms between upkeep discovery passes")
    val maxBoxesPerJob = v.range(s"$Path.maxBoxesPerJob", v.int(s"$Path.maxBoxesPerJob"), 1, 4096,
      "box ids one upkeep job may hold between passes").getOrElse(Default.maxBoxesPerJob)
    v.string(s"$Path.mode").foreach { mode =>
      if (!Modes.contains(mode)) v.problem(s"$Path.mode", s"must be one of ${Modes.mkString(", ")}")
    }
    v.bool(s"$Path.verifyWithNode")
    v.range(s"$Path.retryAfterScans", v.int(s"$Path.retryAfterScans"), 1, 100000,
      "discovery passes a refused upkeep box sits out before it is offered again")
    // Jobs are read generically, so this is the one place a misspelt or unknown job name is caught:
    // enabled, it would otherwise be maintenance the operator expects and never gets. The keys every
    // job may carry are checked here for every job; a job's own keys are its factory's to check.
    Try(config.getOptional(s"$Path.jobs")(ConfigLoader.configurationLoader)) match {
      case Failure(_) =>
        v.problem(s"$Path.jobs", "must be a configuration block, one entry per job")
      case Success(block) => block.foreach { jobs =>
        val known = jobChecks
        jobs.subKeys.toSeq.sorted.foreach { name =>
          val path = s"$Path.jobs.$name"
          Try(config.getOptional(path)(ConfigLoader.configurationLoader)) match {
            case Failure(_) | Success(None) =>
              v.problem(path, "must be a configuration block: enabled, and optionally boxIds")
            case Success(Some(jobBlock)) =>
              val key = s"$path.enabled"
              if (v.bool(key).contains(true) && !known.exists(_.name == name))
                v.problem(key, s""""$name" is not an upkeep job this client knows. Known: """ +
                  (if (known.isEmpty) "none" else known.map(_.name).mkString(", ")))
              boxIds(v, config, s"$path.boxIds", maxBoxesPerJob)
              known.find(_.name == name).foreach { factory =>
                Try(factory.check(jobBlock)) match {
                  case Success(problems) => problems.foreach { case (k, message) => v.problem(s"$path.$k", message) }
                  case Failure(ex) => v.problem(path, s"could not be checked: ${ex.getMessage}")
                }
              }
          }
        }
      }
    }
  }

  /**
   * A job's configured box list, read back by id on every scan: a malformed id would fail its read
   * on every pass, and the list is never cut at `maxBoxesPerJob`, so more ids than that would grow
   * the job past the bound the operator set.
   */
  private def boxIds(v: ConfigValidator, config: Configuration, key: String, maxBoxes: Int): Unit =
    Try(config.getOptional(key)(ConfigLoader.seqStringLoader)) match {
      case Failure(_) => v.problem(key, "must be a list of box ids")
      case Success(ids) => ids.foreach { list =>
        list.filterNot(_.matches("[0-9a-fA-F]{64}")).foreach(id =>
          v.problem(key, s""""$id" is not a box id: expected 64 hex characters"""))
        if (list.map(_.toLowerCase).distinct.size != list.size)
          v.problem(key, "lists the same box id more than once")
        if (list.size > maxBoxes)
          v.problem(key, s"lists ${list.size} boxes; at most $maxBoxes ($Path.maxBoxesPerJob)")
        if (list.size > MaxConfiguredBoxes)
          v.problem(key, s"lists ${list.size} boxes; at most $MaxConfiguredBoxes may be configured, since each is " +
            "one read on every scan")
      }
    }
}
