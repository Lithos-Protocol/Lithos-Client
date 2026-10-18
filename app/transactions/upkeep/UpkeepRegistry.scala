package transactions.upkeep

import configs.UpkeepConfig
import play.api.Configuration
import transactions.upkeep.jobs.HeartbeatJob

/**
 * How a registered job is made from its config block. A factory rather than a case class under
 * `configs`, so a new job's settings live in that job's file. `enabled` and `boxIds` are read and
 * validated by the framework; every other key is the factory's to read and, through `check`, to
 * validate.
 *
 * @param name  the key under `stratum.candidate.sources.upkeep.jobs`, and the name of the job made
 * @param make  the job, from its block as config states it
 * @param check problems with the job's own keys, as (key relative to the block, what is wrong), so
 *              a mistake in them stops startup with every other config problem rather than
 *              surfacing as a job that throws on its first build
 */
final case class JobFactory(name: String,
                            make: UpkeepConfig.Job => UpkeepJob,
                            check: Configuration => Seq[(String, String)] = _ => Seq.empty)

/**
 * Every upkeep job this client knows, in the order their work is offered to a block. Config can
 * only turn on a job registered here, so every job that runs is code that shipped with this client,
 * and a job name that is enabled and unknown is refused at startup.
 */
object UpkeepRegistry {

  val all: Seq[JobFactory] = Seq(HeartbeatJob.Factory)

  /** The names [[all]] answers to, in the same order. Validation checks config against this. */
  def names: Seq[String] = all.map(_.name)

  /** What config validation needs of every job, handed to it so `configs` depends on no job. */
  def checks: Seq[UpkeepConfig.JobCheck] = all.map(f => UpkeepConfig.JobCheck(f.name, f.check))

  /**
   * The jobs config turns on, made from their blocks, out of `factories`. Off is the default for
   * every one of them. A factory whose job answers to another name is a registry mistake, and is
   * refused here rather than left to report its candidates and refusals under the wrong name.
   */
  def enabled(config: UpkeepConfig, factories: Seq[JobFactory] = all): Seq[UpkeepJob] =
    factories.flatMap { factory =>
      config.jobs.get(factory.name).filter(_.enabled).map { settings =>
        val job = factory.make(settings)
        require(job.name == factory.name,
          s"upkeep factory ${factory.name} made a job named ${job.name}")
        job
      }
    }
}
