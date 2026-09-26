package dev.klepto.utrecorder.server.source

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.DemoStore
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import dev.klepto.utrecorder.server.RecordingState
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.recording.RecordingQueue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class DemoPollerTest :
    FunSpec({
        val demoBytes = requireNotNull(DemoPollerTest::class.java.getResource("/demo.dem")).readBytes()

        fun cap(number: Int): Cap =
            Cap(
                id = "cap-$number",
                map = "CTF-BT-Listed",
                alias = "player-$number",
                capTime = 83.seconds,
                added = Instant.parse("2026-09-01T12:00:00Z").plusSeconds(number.toLong()),
            )

        fun knownDemo(number: Int): Demo =
            Demo(
                id = 0,
                sourceId = "cap-$number",
                map = "CTF-BT-II-CoZyliciouS",
                player = "player-$number",
                capTime = 83.seconds,
                duration = 78.seconds,
                addedAt = Instant.parse("2026-09-01T12:00:00Z").plusSeconds(number.toLong()),
                recordingId = 0,
            )

        /**
         * Serves caps numbered [newest] down to 1, newest first, in pages of [pageSize]. Demos default to [demoBytes].
         */
        class FakeSource(
            val newest: Int,
            val pageSize: Int = 3,
            val now: () -> Long = { 0 },
            val demos: Map<String, () -> ByteArray?> = emptyMap(),
        ) : DemoSource {
            val offsets = mutableListOf<Int>()
            val downloads = mutableListOf<Pair<String, Long>>()

            override suspend fun caps(offset: Int): List<Cap> {
                offsets += offset
                return (newest downTo 1).drop(offset).take(pageSize).map(::cap)
            }

            override suspend fun demo(id: String): ByteArray? {
                downloads += id to now()
                return demos[id]?.let { return it() } ?: demoBytes
            }
        }

        class Harness(
            val store: DemoStore,
            val queue: RecordingQueue,
            val config: ServerConfig,
        ) {
            suspend fun stored(): List<Pair<Demo, Recording>> {
                val contents = store.load()
                return contents.demos.map { demo -> demo to contents.recordings.single { it.id == demo.recordingId } }
            }

            suspend fun queued(): List<String> {
                val ids = mutableListOf<String>()
                while (!queue.isEmpty) ids += queue.take(RecordingLane.SHORT).first.sourceId
                return ids
            }
        }

        fun withPoller(
            source: FakeSource,
            known: List<Demo> = emptyList(),
            longThreshold: kotlin.time.Duration = 5.minutes,
            uploaderIsEmpty: () -> Boolean = { true },
            block: suspend TestScope.(DemoPoller, Harness) -> Unit,
        ) = runTest {
            val directory = createTempDirectory("utrecorder-poller-test")
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    database = directory["server.db"],
                    cache = directory["cache"],
                    longThreshold = longThreshold,
                    backfillCooldown = 5.minutes,
                )
            val store = DemoStore.open(config.database, config.cache)
            try {
                known.forEach { store.insert(it, Recording(lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED)) }
                val demos = store.load().demos
                val queue = RecordingQueue()
                block(DemoPoller(source, store, queue, uploaderIsEmpty, config, demos), Harness(store, queue, config))
            } finally {
                store.close()
                directory.deleteRecursively()
            }
        }

        test("live poll pages until a known cap and handles the unseen caps oldest first") {
            val source = FakeSource(newest = 8)
            withPoller(source, known = listOf(knownDemo(1), knownDemo(2))) { poller, harness ->
                poller.poll()

                source.offsets shouldBe listOf(0, 3, 6)
                source.downloads.map { it.first } shouldBe listOf("cap-3", "cap-4", "cap-5", "cap-6", "cap-7", "cap-8")
                harness.queued() shouldBe listOf("cap-3", "cap-4", "cap-5", "cap-6", "cap-7", "cap-8")
            }
        }

        test("live poll stores the demo's map and duration with the cap's player, cap time and added time") {
            withPoller(FakeSource(newest = 2), known = listOf(knownDemo(1))) { poller, harness ->
                poller.poll()

                val (demo, recording) = harness.stored().last()
                demo.sourceId shouldBe "cap-2"
                demo.map shouldBe "CTF-BT-II-CoZyliciouS"
                demo.duration shouldBe 78362.milliseconds
                demo.player shouldBe "player-2"
                demo.capTime shouldBe 83.seconds
                demo.addedAt shouldBe Instant.parse("2026-09-01T12:00:02Z")
                recording.state shouldBe RecordingState.PENDING
                recording.lane shouldBe RecordingLane.SHORT
            }
        }

        test("a demo at the long threshold goes to the long lane") {
            withPoller(FakeSource(newest = 2), known = listOf(knownDemo(1)), longThreshold = 78362.milliseconds) { poller, harness ->
                poller.poll()

                harness
                    .stored()
                    .last()
                    .second.lane shouldBe RecordingLane.LONG
                harness.queue
                    .take(RecordingLane.LONG)
                    .first.sourceId shouldBe "cap-2"
            }
        }

        test("on an empty database the live poll takes only the newest cap") {
            val source = FakeSource(newest = 8)
            withPoller(source) { poller, harness ->
                poller.poll()

                source.offsets shouldBe listOf(0)
                harness.stored().map { it.first.sourceId } shouldBe listOf("cap-8")
                harness.queued() shouldBe listOf("cap-8")
            }
        }

        test("a demo the source does not serve is stored failed and not enqueued") {
            val source = FakeSource(newest = 2, demos = mapOf("cap-2" to { null }))
            withPoller(source, known = listOf(knownDemo(1))) { poller, harness ->
                poller.poll()

                val (demo, recording) = harness.stored().last()
                demo.map.shouldBeNull()
                demo.duration.shouldBeNull()
                recording.state shouldBe RecordingState.FAILED
                recording.error.shouldNotBeNull()
                harness.queued().shouldBeEmpty()
            }
        }

        test("a demo that does not parse is stored failed and not enqueued") {
            val source = FakeSource(newest = 2, demos = mapOf("cap-2" to { byteArrayOf(1, 2, 3) }))
            withPoller(source, known = listOf(knownDemo(1))) { poller, harness ->
                poller.poll()

                val (demo, recording) = harness.stored().last()
                demo.map.shouldBeNull()
                recording.state shouldBe RecordingState.FAILED
                harness.queued().shouldBeEmpty()
            }
        }

        test("a failed download writes nothing and the next pass retries it") {
            var failing = true
            val source =
                FakeSource(newest = 3, demos = mapOf("cap-2" to { if (failing) throw IOException("503") else null }))
            withPoller(source, known = listOf(knownDemo(1))) { poller, harness ->
                poller.poll()

                harness.stored().map { it.first.sourceId } shouldBe listOf("cap-1")

                failing = false
                poller.poll()

                harness.stored().map { it.first.sourceId } shouldBe listOf("cap-1", "cap-2", "cap-3")
            }
        }

        test("backfill walks older caps newest first from the stored count, skipping known ones, until an empty page") {
            val source = FakeSource(newest = 6)
            withPoller(source, known = listOf(knownDemo(6), knownDemo(3))) { poller, harness ->
                backgroundScope.launch { while (true) harness.queue.take(RecordingLane.SHORT) }
                poller.backfill()

                source.offsets shouldBe listOf(2, 5, 6)
                source.downloads.map { it.first } shouldBe listOf("cap-4", "cap-2", "cap-1")
                harness.stored().map { it.first.sourceId } shouldBe listOf("cap-6", "cap-3", "cap-4", "cap-2", "cap-1")
            }
        }

        test("backfill waits the cooldown after each demo") {
            lateinit var scope: TestScope
            val source = FakeSource(newest = 3, now = { scope.testScheduler.currentTime })
            withPoller(source, known = listOf(knownDemo(3))) { poller, harness ->
                scope = this
                backgroundScope.launch { while (true) harness.queue.take(RecordingLane.SHORT) }
                poller.backfill()

                source.downloads.map { it.second } shouldBe listOf(0L, 5.minutes.inWholeMilliseconds)
            }
        }

        test("backfill takes a demo only when the queue and the uploader are both empty") {
            var uploaderIsEmpty = false
            val source = FakeSource(newest = 2)
            withPoller(source, known = listOf(knownDemo(2)), uploaderIsEmpty = { uploaderIsEmpty }) { poller, harness ->
                harness.queue.enqueue(knownDemo(9) to Recording(lane = RecordingLane.SHORT))
                val backfill = launch { poller.backfill() }

                advanceTimeBy(1.hours)
                source.downloads.shouldBeEmpty()

                uploaderIsEmpty = true
                advanceTimeBy(1.hours)
                source.downloads.shouldBeEmpty()

                harness.queue.take(RecordingLane.SHORT)
                backfill.join()
                source.downloads.map { it.first } shouldBe listOf("cap-1")
            }
        }
    })
