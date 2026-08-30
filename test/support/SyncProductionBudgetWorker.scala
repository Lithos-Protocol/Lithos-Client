package support

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import lfsm.states.{DeferredDictionary, PlasmaDictionary, Rollup}
import node.MutationConversions._
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import state.messages.MempoolMessages.{MempoolChain, MempoolSnapshot, MempoolTransform}
import state.messages.SyncMessages._
import state.messages.{BlockTx, InputSpendingProof, TxInput, TxOutput}
import state.persistence.StateSnapshotActor.{DictionaryLoaded, MaterializeDictionary}
import state.persistence._
import state.synchronization._
import storage.{KeyValueMutation, KeyValueReadView, KeyValueStore, LevelDbKeyValueStore,
  StorageBackend, StoreError, WriteDurability}

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/** Main-class target for fixed-heap production-budget child JVMs. */
object SyncProductionBudgetWorker {
  @volatile private var retained: Any = _

  def main(arguments: Array[String]): Unit = {
    val selected = arguments.headOption.getOrElse("all").split(',').map(_.trim).filter(_.nonEmpty).toSet
    def enabled(name: String): Boolean = selected("all") || selected(name)

    if (enabled("mempool")) mempoolProfiles()
    if (enabled("dictionary")) dictionaryProfiles()
    if (enabled("journal")) journalProfiles()
    if (enabled("snapshot")) snapshotProfiles()
    if (enabled("crash")) snapshotCrashProfiles()
    if (enabled("fanout")) fanoutProfiles()
    // Deliberately excluded from "all": this allocates several maximum-NISP-shaped AVL trees at
    // once. Operational qualification opts into it explicitly under a fixed heap.
    if (selected("fanout-large")) largeFanoutProfiles()
    println("PRODUCTION_BUDGET_COMPLETED")
  }

  private def mempoolProfiles(): Unit = {
    val warmRoot = SyncFixtures.id(1999998)
    val warmTx = SyncFixtures.rollupTransform(1999999, 100, warmRoot,
      SyncFixtures.id(1999999)).tx
    (1 to 4).foreach { _ =>
      retained = MempoolView.buildGraph(Vector.fill(100)(warmTx), Set(warmRoot))
    }
    retained = null
    ProductionBudgetMetrics.stabilizeHeap()
    val profiles = Seq(
      (499, 0), (500, 0), (501, 0), (10000, 0),
      (1000, 4096), (128, 65536))
    profiles.foreach { case (count, payloadBytes) =>
      val before = ProductionBudgetMetrics.stabilizeHeap()
      val measured = ProductionBudgetMetrics.timed {
        val ids = Vector.tabulate(count + 1)(index => SyncFixtures.id(2000000 + index))
        val transactions = Vector.tabulate(count) { index =>
          def payload(character: Char): String = if (payloadBytes == 0) "" else
            f"$index%08x" + (character.toString * math.max(0, payloadBytes - 8))
          val proofPayload = payload('p')
          val registerPayload = payload('r')
          val id = SyncFixtures.id(2100000 + index)
          BlockTx(id,
            Seq(TxInput(ids(index), Some(InputSpendingProof(proofPayload, Map.empty)))), Seq.empty,
            Seq(TxOutput(ids(index + 1), 1000000L, "", Seq(registerPayload), Seq.empty,
              id, 100, 0)))
        }
        val graph = MempoolView.buildGraph(transactions, Set(ids.head))
        require(graph.chains(ids.head).transforms.size == count)
        retained = graph
        graph
      }
      val settled = ProductionBudgetMetrics.stabilizeHeap()
      ProductionBudgetMetrics.record("sync-mempool", s"count-$count-payload-$payloadBytes",
        "transactionCount" -> count,
        "payloadBytesPerTransaction" -> payloadBytes,
        "logicalPayloadBytes" -> (count.toLong * payloadBytes.toLong * 2L),
        "elapsedNanos" -> measured.elapsedNanos,
        "transactionsPerSecond" -> (count.toDouble * 1000000000d / measured.elapsedNanos),
        "heapBeforeBytes" -> before.used,
        "heapRetainedBytes" -> settled.used,
        "observedRetainedDeltaBytes" -> math.max(0L, settled.used - before.used),
        "gcCollections" -> (measured.gcAfter.collections - measured.gcBefore.collections),
        "gcMilliseconds" -> (measured.gcAfter.milliseconds - measured.gcBefore.milliseconds))
      retained = null
      ProductionBudgetMetrics.stabilizeHeap()
    }
  }

  private def dictionaryProfiles(): Unit = {
    val maxNispBytes = lfsm.LFSMHelpers.NISP_MAX - 1
    val warmDictionary = PlasmaDictionary.empty()
    warmDictionary.insert(SyncFixtures.plasmaEntries(1, 32).head)
    retained = warmDictionary
    retained = null
    ProductionBudgetMetrics.stabilizeHeap()
    val profiles = Seq(
      (0, 32, "empty"),
      (100, 32, "typical-100"),
      (10000, 32, "typical-10000"),
      (100, maxNispBytes, "max-nisp-100"),
      (500, maxNispBytes, "max-nisp-500"))
    profiles.foreach { case (count, valueBytes, name) =>
      val before = ProductionBudgetMetrics.stabilizeHeap()
      val measured = ProductionBudgetMetrics.timed {
        val dictionary = PlasmaDictionary.empty()
        if (count > 0) dictionary.insert(SyncFixtures.plasmaEntries(count, valueBytes): _*)
        retained = dictionary
        dictionary
      }
      val settled = ProductionBudgetMetrics.stabilizeHeap()
      val observed = math.max(0L, settled.used - before.used)
      ProductionBudgetMetrics.record("sync-dictionary", name,
        "entryCount" -> count,
        "valueBytes" -> valueBytes,
        "estimatedHeapBytes" -> measured.value.estimatedHeapBytes,
        "observedRetainedDeltaBytes" -> observed,
        "estimateToObservedRatio" -> (if (observed == 0L) 0d
          else measured.value.estimatedHeapBytes.toDouble / observed.toDouble),
        "elapsedNanos" -> measured.elapsedNanos,
        "digestBytes" -> measured.value.digest.length,
        "gcCollections" -> (measured.gcAfter.collections - measured.gcBefore.collections),
        "gcMilliseconds" -> (measured.gcAfter.milliseconds - measured.gcBefore.milliseconds))
      retained = null
      ProductionBudgetMetrics.stabilizeHeap()
    }
  }

  private def journalProfiles(): Unit = {
    val warmBase = ReducerFixtures.emptyState(98, SyncFixtures.id(98))
    val warmCursor = SyncCursor(99, SyncFixtures.id(99), warmBase.cursor.blockId)
    val warmState = warmBase.copy(cursor = warmCursor)
    retained = TransformJournal(warmBase.cursor).append(TransformJournalEntry(warmCursor,
      CommittedSyncMetadata.from(warmState), Vector.empty))
    retained = null
    ProductionBudgetMetrics.stabilizeHeap()
    Seq((0, 720), (64, 720), (720, 90), (720, 720)).foreach {
      case (rollupCount, blockCount) =>
        val base = stateWithRollups(99, rollupCount)
        val before = ProductionBudgetMetrics.stabilizeHeap()
        val measured = ProductionBudgetMetrics.timed {
          var journal = TransformJournal(base.cursor)
          var parent = base.cursor.blockId
          (1 to blockCount).foreach { offset =>
            val height = base.cursor.height + offset
            val cursor = SyncCursor(height, idFromLong(3000000L + height), parent)
            // Mirrors empty canonical blocks: dictionary objects/maps are retained by the live state,
            // while each journal entry owns a fresh metadata rollup map and cursor.
            val state = base.copy(cursor = cursor, version = height.toLong)
            val metadata = CommittedSyncMetadata.from(state)
            journal = journal.append(TransformJournalEntry(cursor, metadata, Vector.empty))
            parent = cursor.blockId
          }
          retained = journal
          journal
        }
        val settled = ProductionBudgetMetrics.stabilizeHeap()
        val observed = math.max(0L, settled.used - before.used)
        ProductionBudgetMetrics.record("sync-journal",
          s"rollups-$rollupCount-blocks-$blockCount",
          "rollupCount" -> rollupCount,
          "blockCount" -> blockCount,
          "accountedBytes" -> measured.value.accountedBytes,
          "payloadEstimatedBytes" -> measured.value.estimatedBytes,
          "observedRetainedDeltaBytes" -> observed,
          "accountingToObservedRatio" -> (if (observed == 0L) 0d
            else measured.value.accountedBytes.toDouble / observed.toDouble),
          "appendElapsedNanos" -> measured.elapsedNanos,
          "gcCollections" -> (measured.gcAfter.collections - measured.gcBefore.collections),
          "gcMilliseconds" -> (measured.gcAfter.milliseconds - measured.gcBefore.milliseconds))
        retained = null
        ProductionBudgetMetrics.stabilizeHeap()
    }
  }

  private def snapshotProfiles(): Unit = {
    Seq(100, 2051, 10000).foreach { entries =>
      val directory = Files.createTempDirectory("lithos-sync-budget-")
      try {
        val dictionary = PlasmaDictionary.empty()
        dictionary.insert(SyncFixtures.plasmaEntries(entries, 32): _*)
        val state = ReducerFixtures.emptyState(100, SyncFixtures.id(100), 1L).copy(
          minerTree = ReducerFixtures.emptyState().minerTree.copy(dictionary = dictionary,
            numMiners = entries, syncHeight = 100, savedHeight = 100))
        val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
          dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
        val checkpoint = SnapshotCheckpoint(CommittedSyncMetadata.from(state), Vector(state.cursor),
          Map(DictionaryId.Miner -> source))
        val keyValues = LevelDbKeyValueStore.openOrThrow(directory)
          .asInstanceOf[LevelDbKeyValueStore]
        val snapshots = new LevelDbStateSnapshotStore(keyValues, 2,
          StateSnapshotIdentity(ReducerFixtures.protocol()))

        val save = ProductionBudgetMetrics.timed {
          snapshots.save(checkpoint, 64 * 1024 * 1024) match {
            case Right(_) => ()
            case Left(error) => throw new IllegalStateException(error.message)
          }
        }
        val diskBytes = directoryBytes(directory)
        val load = ProductionBudgetMetrics.timed {
          snapshots.loadDictionary(Hex.toHexString(dictionary.digest)) match {
            case Right(value) => value
            case Left(error) => throw new IllegalStateException(error.message)
          }
        }
        require(java.util.Arrays.equals(load.value.digest, dictionary.digest))
        ProductionBudgetMetrics.record("sync-snapshot",
          (if (entries == 100) "cold-" else "warm-") + s"entries-$entries",
          "entryCount" -> entries,
          "estimatedDictionaryHeapBytes" -> dictionary.estimatedHeapBytes,
          "databaseBytes" -> diskBytes,
          "saveElapsedNanos" -> save.elapsedNanos,
          "loadElapsedNanos" -> load.elapsedNanos,
          "saveDatabaseMiBPerSecond" -> (diskBytes.toDouble * 1000000000d /
            save.elapsedNanos / (1024d * 1024d)),
          "loadDatabaseMiBPerSecond" -> (diskBytes.toDouble * 1000000000d /
            load.elapsedNanos / (1024d * 1024d)),
          "saveGcCollections" -> (save.gcAfter.collections - save.gcBefore.collections),
          "loadGcCollections" -> (load.gcAfter.collections - load.gcBefore.collections))
        snapshots.close() match {
          case Right(_) => ()
          case Left(error) => throw new IllegalStateException(error.message)
        }
      } finally deleteTree(directory)
      ProductionBudgetMetrics.stabilizeHeap()
    }
  }

  /** Abrupt termination is a durability semantic, so one fixed-heap profile is sufficient. */
  private def snapshotCrashProfiles(): Unit = {
    val heapMiB = Runtime.getRuntime.maxMemory() / (1024L * 1024L)
    if (heapMiB > 1200L) {
      ProductionBudgetMetrics.record("sync-snapshot-crash", "higher-heap-skipped",
        "heapMiB" -> heapMiB,
        "reason" -> "process crash phases run once in the 1 GiB profile")
      return
    }

    val entries = 2051
    val baselineDirectory = Files.createTempDirectory("lithos-sync-crash-baseline-")
    val pointerWrite = try {
      val underlying = LevelDbKeyValueStore.openOrThrow(baselineDirectory)
      val counted = new PointerCountingStore(underlying)
      val snapshots = new LevelDbStateSnapshotStore(counted, 2, SnapshotCrashFixtures.identity)
      snapshots.save(SnapshotCrashFixtures.checkpoint(entries), 64 * 1024 * 1024) match {
        case Right(_) => ()
        case Left(error) => throw new IllegalStateException(error.message)
      }
      val pointer = counted.pointerWrite.getOrElse(
        throw new IllegalStateException("snapshot pointer was never physically written"))
      snapshots.close()
      pointer
    } finally deleteTree(baselineDirectory)

    Seq("before", "after").foreach { phase =>
      (1 to pointerWrite).foreach { writeNumber =>
        val directory = Files.createTempDirectory("lithos-sync-crash-phase-")
        try {
          val crash = ForkedJvm.run("support.SyncSnapshotCrashWorker", 512,
            Seq(directory.toString, phase, writeNumber.toString, entries.toString), 2.minutes)
          require(crash.exitCode == SyncSnapshotCrashWorker.HaltExitCode,
            s"crash phase $phase/$writeNumber exited ${crash.exitCode}: ${crash.output}")

          val keyValues = LevelDbKeyValueStore.openOrThrow(directory)
          val snapshots = new LevelDbStateSnapshotStore(keyValues, 2,
            SnapshotCrashFixtures.identity)
          val generationsBeforeRetry = snapshots.generationKeys() match {
            case Right(keys) => keys
            case Left(error) => throw new IllegalStateException(error.message)
          }
          val expectedCommitted = phase == "after" && writeNumber == pointerWrite
          require(generationsBeforeRetry.nonEmpty == expectedCommitted,
            s"phase $phase/$writeNumber committed=${generationsBeforeRetry.nonEmpty}, " +
              s"expected $expectedCommitted")

          val recovery = ProductionBudgetMetrics.timed {
            snapshots.save(SnapshotCrashFixtures.checkpoint(entries), 64 * 1024 * 1024) match {
              case Right(_) => ()
              case Left(error) => throw new IllegalStateException(error.message)
            }
            val key = snapshots.generationKeys().toOption.get.head
            val state = snapshots.load(key).toOption.get
            snapshots.loadDictionary(state.state.minerTree.metadata.dictionaryDigest).toOption.get
          }
          snapshots.close()
          ProductionBudgetMetrics.record("sync-snapshot-crash",
            s"$phase-write-$writeNumber-of-$pointerWrite",
            "phase" -> phase,
            "writeNumber" -> writeNumber,
            "pointerWriteNumber" -> pointerWrite,
            "generationCommittedBeforeRetry" -> expectedCommitted,
            "recoveryElapsedNanos" -> recovery.elapsedNanos,
            "crashExitCode" -> crash.exitCode)
        } finally deleteTree(directory)
      }
    }
  }

  private def fanoutProfiles(): Unit = Seq(32, 128, 720).foreach { roots =>
    val system = ActorSystem(s"sync-fanout-budget-$roots")
    try {
      val snapshots = TestProbe()(system)
      val owner = TestProbe()(system)
      val consumers = Vector.fill(if (roots >= 720) 8 else 32)(TestProbe()(system))
      val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
      val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
        .copy(localMinerHash = wallet.contract.hashedPropBytes)
      val rollups = Vector.tabulate(roots) { index =>
        val dictionary = PlasmaDictionary.empty()
        dictionary.insert(uniqueEntry(index))
        SyncFixtures.emptyRollup(idFromLong(4000000L + index),
          idFromLong(4100000L + index), 99).copy(dictionary = dictionary)
      }
      val byDigest = rollups.map(tree => tree.metadata.dictionaryDigest -> tree.dictionary).toMap
      val state = ReducerFixtures.emptyState(99, SyncFixtures.id(99)).copy(
        rollups = rollups.map(tree => tree.blockId -> tree).toMap,
        routes = rollups.map(tree => tree.utxoId -> tree.blockId).toMap,
        rollupOrigins = rollups.zipWithIndex.map { case (tree, index) =>
          tree.blockId -> idFromLong(4200000L + index)
        }.toMap)
      val config = Configuration(ConfigFactory.parseString(
        s"""sync.startHeight = 100
           |sync.snapshots.enabled = false
           |sync.materializedDictionaryCacheEntries = ${roots + 8}
           |""".stripMargin))
      val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref)))
      owner.send(handler, RestoreCommittedState(state, Vector(state.cursor)))
      owner.expectMsgType[BlockCommitted](30.seconds)
      val sharedEnd = nodeContext.getClient.execute { ctx =>
        NodeBox(SyncFixtures.id(4300000), SyncFixtures.id(4300001), 1000000L, 0, 100,
          wallet.contract.ergoTreeHex).toInputUTXO(ctx)
      }
      def revision(number: Long, offset: Long): MempoolSnapshot = {
        val chains = rollups.zipWithIndex.map { case (tree, index) =>
          val tx = SyncFixtures.rollupTransform(index + 1, 100, tree.utxoId,
            idFromLong(offset + index)).tx
          tree.utxoId -> MempoolChain(Seq(MempoolTransform(tx)))
        }.toMap
        MempoolSnapshot(number, chains, rollups.map(_.utxoId -> sharedEnd).toMap, Set.empty)
      }
      owner.send(handler, revision(1L, 4400000L))
      owner.send(handler, MarkReady)
      owner.expectMsgType[Ready](30.seconds)

      val before = ProductionBudgetMetrics.stabilizeHeap()
      val started = System.nanoTime()
      consumers.foreach(_.send(handler, GetSynced))
      val loads = Vector.fill(roots)(snapshots.expectMsgType[MaterializeDictionary](60.seconds))
      val loadDispatchElapsed = System.nanoTime() - started
      snapshots.expectNoMessage(250.millis)
      val pending = ProductionBudgetMetrics.stabilizeHeap()
      owner.send(handler, revision(2L, 4500000L))
      val completionStarted = System.nanoTime()
      loads.foreach(load => snapshots.reply(DictionaryLoaded(load.source.expectedDigest,
        load.requestToken, byDigest(load.source.expectedDigest))))
      val views = consumers.map(_.expectMsgType[FullSync](5.minutes))
      val completionElapsed = System.nanoTime() - completionStarted
      val elapsed = System.nanoTime() - started
      val completed = ProductionBudgetMetrics.stabilizeHeap()
      require(views.forall(view => view.rollups.size == roots && view.projections.size == roots))
      snapshots.expectNoMessage(250.millis)
      owner.send(handler, GetCommittedState)
      require(owner.expectMsgType[CommittedState](30.seconds).state.quarantined.isEmpty)

      ProductionBudgetMetrics.record("sync-fanout", s"roots-$roots-consumers-${consumers.size}",
        "rootCount" -> roots,
        "consumerCount" -> consumers.size,
        "physicalLoadCount" -> loads.size,
        "elapsedNanos" -> elapsed,
        "loadDispatchElapsedNanos" -> loadDispatchElapsed,
        "completionElapsedNanos" -> completionElapsed,
        "physicalLoadsPerSecond" -> (loads.size.toDouble * 1000000000d /
          loadDispatchElapsed.toDouble),
        "heapBeforeBytes" -> before.used,
        "heapPendingBytes" -> pending.used,
        "heapCompletedBytes" -> completed.used,
        "pendingDeltaBytes" -> math.max(0L, pending.used - before.used),
        "completedDeltaBytes" -> math.max(0L, completed.used - before.used))
      system.stop(handler)
    } finally {
      ActorSystemShutdown.shutdown(system)
      ProductionBudgetMetrics.stabilizeHeap()
    }
  }

  /**
   * Exercises the aggregate heap path that the ordinary fanout profiles intentionally do not:
   * checkpoint state begins digest-only, then one full-view barrier owns every reconstructed
   * dictionary while the actor also builds and retains every mempool projection.
   */
  private def largeFanoutProfiles(): Unit = Seq(8).foreach { roots =>
    val entriesPerRoot = 500
    val valueBytes = lfsm.LFSMHelpers.NISP_MAX - 1
    val system = ActorSystem(s"sync-large-fanout-budget-$roots")
    try {
      val snapshots = TestProbe()(system)
      val owner = TestProbe()(system)
      val consumer = TestProbe()(system)
      val repeatConsumer = TestProbe()(system)
      val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
      val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
        .copy(localMinerHash = wallet.contract.hashedPropBytes)

      var template = largeDictionary(entriesPerRoot, valueBytes)
      val expectedDigest = template.digest
      val expectedDigestHex = Hex.toHexString(expectedDigest)
      val dictionaryFlags = template.flags
      val dictionaryParameters = template.parameters
      val estimatedBytesPerDictionary = template.estimatedHeapBytes
      template = null

      val rollups = Vector.tabulate(roots) { index =>
        val deferred = DeferredDictionary(expectedDigest, dictionaryFlags, dictionaryParameters)
        SyncFixtures.emptyRollup(idFromLong(4600000L + index),
          idFromLong(4700000L + index), 99).copy(dictionary = deferred)
      }
      val state = ReducerFixtures.emptyState(99, SyncFixtures.id(99)).copy(
        rollups = rollups.map(tree => tree.blockId -> tree).toMap,
        routes = rollups.map(tree => tree.utxoId -> tree.blockId).toMap,
        rollupOrigins = rollups.zipWithIndex.map { case (tree, index) =>
          tree.blockId -> idFromLong(4800000L + index)
        }.toMap)
      val config = Configuration(ConfigFactory.parseString(
        s"""sync.startHeight = 100
           |sync.snapshots.enabled = false
           |sync.materializedDictionaryCacheEntries = 64
           |""".stripMargin))
      val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref)))
      owner.send(handler, RestoreCommittedState(state, Vector(state.cursor)))
      owner.expectMsgType[BlockCommitted](30.seconds)

      val sharedEnd = nodeContext.getClient.execute { ctx =>
        NodeBox(SyncFixtures.id(4900000), SyncFixtures.id(4900001), 1000000L, 0, 100,
          wallet.contract.ergoTreeHex).toInputUTXO(ctx)
      }
      val chains = rollups.zipWithIndex.map { case (tree, index) =>
        val tx = SyncFixtures.rollupTransform(index + 1, 100, tree.utxoId,
          idFromLong(5000000L + index)).tx
        tree.utxoId -> MempoolChain(Seq(MempoolTransform(tx)))
      }.toMap
      owner.send(handler, MempoolSnapshot(1L, chains,
        rollups.map(_.utxoId -> sharedEnd).toMap, Set.empty))
      owner.send(handler, MarkReady)
      owner.expectMsgType[Ready](30.seconds)

      val before = ProductionBudgetMetrics.stabilizeHeap()
      val started = System.nanoTime()
      consumer.send(handler, GetSynced)
      val loads = Vector.fill(roots)(
        snapshots.expectMsgType[MaterializeDictionary](2.minutes))
      require(loads.forall(_.source.expectedDigest == expectedDigestHex))
      require(loads.map(_.requestToken).distinct.size == roots)
      val pending = ProductionBudgetMetrics.stabilizeHeap()

      val sampler = new PeakHeapSampler
      sampler.start()
      val materializationGcBefore = ProductionBudgetMetrics.gc
      val materializationStarted = System.nanoTime()
      var peakHeapBytes = 0L
      try {
        loads.foreach { load =>
          val dictionary = largeDictionary(entriesPerRoot, valueBytes)
          require(Hex.toHexString(dictionary.digest) == load.source.expectedDigest)
          snapshots.reply(DictionaryLoaded(load.source.expectedDigest,
            load.requestToken, dictionary))
        }
        val view = consumer.expectMsgType[FullSync](10.minutes)
        require(view.rollups.size == roots && view.projections.size == roots)
      } finally peakHeapBytes = sampler.stop()
      val materializationElapsed = System.nanoTime() - materializationStarted
      val materializationGcAfter = ProductionBudgetMetrics.gc
      val completed = ProductionBudgetMetrics.stabilizeHeap()

      // The projection cache already holds the completed revision. Measure whether the dictionary
      // cache nevertheless forces the same full-view request back through physical reconstruction.
      repeatConsumer.send(handler, GetSynced)
      val repeatLoads = snapshots.receiveWhile(2.minutes, 250.millis, roots) {
        case load: MaterializeDictionary => load
      }.toVector
      repeatLoads.foreach { load =>
        val dictionary = largeDictionary(entriesPerRoot, valueBytes)
        require(Hex.toHexString(dictionary.digest) == load.source.expectedDigest)
        snapshots.reply(DictionaryLoaded(load.source.expectedDigest,
          load.requestToken, dictionary))
      }
      val repeated = repeatConsumer.expectMsgType[FullSync](10.minutes)
      require(repeated.rollups.size == roots && repeated.projections.size == roots)
      snapshots.expectNoMessage(250.millis)
      owner.send(handler, GetCommittedState)
      require(owner.expectMsgType[CommittedState](30.seconds).state.quarantined.isEmpty)

      val elapsed = System.nanoTime() - started
      ProductionBudgetMetrics.record("sync-fanout-large",
        s"roots-$roots-entries-$entriesPerRoot-value-$valueBytes",
        "rootCount" -> roots,
        "entryCountPerRoot" -> entriesPerRoot,
        "valueBytes" -> valueBytes,
        "estimatedBytesPerDictionary" -> estimatedBytesPerDictionary,
        "aggregateEstimatedDictionaryBytes" ->
          (estimatedBytesPerDictionary * roots.toLong),
        "physicalLoadCount" -> loads.size,
        "repeatPhysicalLoadCount" -> repeatLoads.size,
        "elapsedNanos" -> elapsed,
        "materializationAndProjectionElapsedNanos" -> materializationElapsed,
        "materializationGcCollections" ->
          (materializationGcAfter.collections - materializationGcBefore.collections),
        "materializationGcMilliseconds" ->
          (materializationGcAfter.milliseconds - materializationGcBefore.milliseconds),
        "heapBeforeBytes" -> before.used,
        "heapPendingBytes" -> pending.used,
        "heapCompletedBytes" -> completed.used,
        "peakHeapBytes" -> peakHeapBytes,
        "pendingDeltaBytes" -> math.max(0L, pending.used - before.used),
        "completedDeltaBytes" -> math.max(0L, completed.used - before.used),
        "peakDeltaBytes" -> math.max(0L, peakHeapBytes - before.used))
      system.stop(handler)
    } finally {
      ActorSystemShutdown.shutdown(system)
      retained = null
      ProductionBudgetMetrics.stabilizeHeap()
    }
  }

  private def largeDictionary(entries: Int, valueBytes: Int): PlasmaDictionary = {
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(SyncFixtures.plasmaEntries(entries, valueBytes): _*)
    dictionary
  }

  private def stateWithRollups(height: Int, count: Int): CommittedSyncState = {
    val rollups = Vector.tabulate(count) { index =>
      SyncFixtures.emptyRollup(idFromLong(5000000L + index), idFromLong(5100000L + index), height)
    }
    ReducerFixtures.emptyState(height, idFromLong(height)).copy(
      rollups = rollups.map(tree => tree.blockId -> tree).toMap,
      routes = rollups.map(tree => tree.utxoId -> tree.blockId).toMap,
      rollupOrigins = rollups.zipWithIndex.map { case (tree, index) =>
        tree.blockId -> idFromLong(5200000L + index)
      }.toMap)
  }

  private def uniqueEntry(index: Int): (Array[Byte], Array[Byte]) = {
    val key = Array.fill[Byte](32)(0)
    val keyNumber = index + 1
    key(28) = (keyNumber >>> 24).toByte
    key(29) = (keyNumber >>> 16).toByte
    key(30) = (keyNumber >>> 8).toByte
    key(31) = keyNumber.toByte
    key -> Array.tabulate[Byte](32)(offset => (index + offset).toByte)
  }

  private def idFromLong(value: Long): String = f"$value%064x"

  /** Samples process heap during short-lived peaks that a settled before/after delta cannot see. */
  private final class PeakHeapSampler {
    @volatile private var running = false
    @volatile private var observedPeak = 0L
    private val thread = new Thread(new Runnable {
      override def run(): Unit = while (running) {
        observedPeak = math.max(observedPeak, ProductionBudgetMetrics.heap.used)
        try Thread.sleep(5L)
        catch { case _: InterruptedException => () }
      }
    }, "sync-production-budget-heap-sampler")
    thread.setDaemon(true)

    def start(): Unit = {
      observedPeak = ProductionBudgetMetrics.heap.used
      running = true
      thread.start()
    }

    def stop(): Long = {
      observedPeak = math.max(observedPeak, ProductionBudgetMetrics.heap.used)
      running = false
      thread.interrupt()
      thread.join(2000L)
      math.max(observedPeak, ProductionBudgetMetrics.heap.used)
    }
  }

  private def directoryBytes(path: Path): Long = {
    val files = Files.walk(path)
    try files.iterator().asScala.filter(Files.isRegularFile(_)).map(Files.size).sum
    finally files.close()
  }

  private def deleteTree(path: Path): Unit = if (Files.exists(path)) {
    val paths = Files.walk(path)
    try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally paths.close()
  }

  private final class PointerCountingStore(underlying: KeyValueStore) extends KeyValueStore {
    private var writes = 0
    private var pointerAt = Option.empty[Int]
    def pointerWrite: Option[Int] = pointerAt
    override def path: Path = underlying.path
    override def backend: StorageBackend = underlying.backend
    override def get(key: Array[Byte]) = underlying.get(key)
    override def scanPrefix(prefix: Array[Byte]) = underlying.scanPrefix(prefix)
    override def scanKeys(prefix: Array[Byte]) = underlying.scanKeys(prefix)
    override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]) =
      underlying.readSnapshot(f)
    override def close() = underlying.close()
    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability) = {
      writes += 1
      if (mutations.exists {
        case KeyValueMutation.Put(key, _) =>
          new String(key, java.nio.charset.StandardCharsets.UTF_8) == "sync/snapshot/current"
        case _ => false
      }) pointerAt = Some(writes)
      underlying.write(mutations, durability)
    }
  }

  private object ActorSystemShutdown {
    def shutdown(system: ActorSystem): Unit = {
      system.terminate()
      scala.concurrent.Await.result(system.whenTerminated, 30.seconds)
    }
  }
}
