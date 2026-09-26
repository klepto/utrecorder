package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingLane
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class UtbtDemoFetcherTest :
    FunSpec({
        val demoBytes = requireNotNull(UtbtDemoFetcherTest::class.java.getResource("/demo.dem")).readBytes()
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        fun capJson(number: Int): String =
            """{"id":"cap-$number","map":"CTF-BT-Listed","alias":"player-$number","cap_time_seconds":83.25,""" +
                """"added":"2026-09-01 12:00:0$number.5","has_ghost":true}"""

        fun knownDemo(number: Int): Demo =
            Demo(
                id = "cap-$number",
                map = "CTF-BT-II-CoZyliciouS",
                player = "player-$number",
                capTime = 83.seconds,
                duration = 78.seconds,
                addedAt = Instant.parse("2026-09-01T12:00:00Z").plusSeconds(number.toLong()),
            )

        /**
         * UTBT serving caps numbered [newest] down to 1, newest first, in pages of three. Demos default to [demoBytes].
         */
        class FakeUtbt(
            val newest: Int,
            val now: () -> Long = { 0 },
            val capsStatus: HttpStatusCode = HttpStatusCode.OK,
            val demos: Map<String, () -> ByteArray?> = emptyMap(),
        ) {
            val offsets = mutableListOf<Int>()
            val downloads = mutableListOf<Pair<String, Long>>()

            val engine =
                MockEngine { request ->
                    when (request.url.host) {
                        "gateway.test" -> caps(request)
                        else -> demo(request.url.segments.last())
                    }
                }

            fun MockRequestHandleScope.caps(request: HttpRequestData): HttpResponseData {
                val offset = request.url.parameters["offset"]!!.toInt()
                offsets += offset
                val page = (newest downTo 1).drop(offset).take(3).joinToString(",") { capJson(it) }
                return respond("""{"data":[$page],"success":true}""", capsStatus, json)
            }

            fun MockRequestHandleScope.demo(id: String): HttpResponseData {
                downloads += id to now()
                val bytes = demos.getOrElse(id) { { demoBytes } }()
                return bytes?.let { respond(it) } ?: respond("", HttpStatusCode.NotFound)
            }
        }

        class Harness(
            val demos: DocumentStore.Collection<Demo>,
            val recordings: DocumentStore.Collection<Recording>,
            val queue: RecordingQueue,
        ) {
            suspend fun stored(): List<Pair<Demo, Recording>> = demos.all().map { demo -> demo to recordings[demo.id]!! }

            suspend fun queued(): List<String> {
                val ids = mutableListOf<String>()
                while (!queue.isEmpty) ids += queue.take(RecordingLane.SHORT).first.id
                return ids
            }
        }

        fun withFetcher(
            utbt: FakeUtbt,
            known: List<Demo> = emptyList(),
            longThreshold: kotlin.time.Duration = 5.minutes,
            uploaderIsEmpty: () -> Boolean = { true },
            block: suspend TestScope.(UtbtDemoFetcher, Harness) -> Unit,
        ) = runTest {
            val directory = createTempDirectory("utrecorder-fetcher-test")
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    database = directory["server.db"],
                    cache = directory["cache"],
                    longThreshold = longThreshold,
                    backfillCooldown = 5.minutes,
                    sourceUrl = "https://gateway.test",
                    demoUrl = "http://demos.test/demos",
                )
            val store = DocumentStore.open(config.database)
            try {
                val demos = store.collection("demos", Demo.serializer(), Demo::id)
                val recordings = store.collection("recordings", Recording.serializer(), Recording::demoId)
                known.forEach {
                    recordings.put(Recording(demoId = it.id, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED))
                    demos.put(it)
                }
                val queue = RecordingQueue()
                block(
                    UtbtDemoFetcher(demos, recordings, queue, uploaderIsEmpty, config, known, utbt.engine),
                    Harness(demos, recordings, queue),
                )
            } finally {
                store.close()
                directory.deleteRecursively()
            }
        }

        test("requests pages of verified caps newest first at the offset") {
            val utbt = FakeUtbt(newest = 1)
            withFetcher(utbt) { fetcher, _ ->
                fetcher.poll()

                utbt.engine.requestHistory
                    .first()
                    .url
                    .toString() shouldBe "https://gateway.test/caps?limit=100&offset=0&order=desc&verified=true"
                utbt.engine.requestHistory
                    .last()
                    .url
                    .toString() shouldBe "http://demos.test/demos/cap-1"
            }
        }

        test("live poll pages until a known cap and handles the unseen caps oldest first") {
            val utbt = FakeUtbt(newest = 8)
            withFetcher(utbt, known = listOf(knownDemo(1), knownDemo(2))) { fetcher, harness ->
                fetcher.poll()

                utbt.offsets shouldBe listOf(0, 3, 6)
                utbt.downloads.map { it.first } shouldBe listOf("cap-3", "cap-4", "cap-5", "cap-6", "cap-7", "cap-8")
                harness.queued() shouldBe listOf("cap-3", "cap-4", "cap-5", "cap-6", "cap-7", "cap-8")
            }
        }

        test("live poll stores the demo's map and duration with the cap's player, cap time and added time") {
            withFetcher(FakeUtbt(newest = 2), known = listOf(knownDemo(1))) { fetcher, harness ->
                fetcher.poll()

                val (demo, recording) = harness.stored().last()
                demo.id shouldBe "cap-2"
                demo.map shouldBe "CTF-BT-II-CoZyliciouS"
                demo.duration shouldBe 78362098694.nanoseconds
                demo.player shouldBe "player-2"
                demo.capTime shouldBe 83250.milliseconds
                demo.addedAt shouldBe Instant.parse("2026-09-01T12:00:02.5Z")
                recording.state shouldBe RecordingState.PENDING
                recording.lane shouldBe RecordingLane.SHORT
            }
        }

        test("a demo at the long threshold goes to the long lane") {
            withFetcher(FakeUtbt(newest = 2), known = listOf(knownDemo(1)), longThreshold = 78362.milliseconds) { fetcher, harness ->
                fetcher.poll()

                harness
                    .stored()
                    .last()
                    .second.lane shouldBe RecordingLane.LONG
                harness.queue
                    .take(RecordingLane.LONG)
                    .first.id shouldBe "cap-2"
            }
        }

        test("on an empty database the live poll takes only the newest cap") {
            val utbt = FakeUtbt(newest = 8)
            withFetcher(utbt) { fetcher, harness ->
                fetcher.poll()

                utbt.offsets shouldBe listOf(0)
                harness.stored().map { it.first.id } shouldBe listOf("cap-8")
                harness.queued() shouldBe listOf("cap-8")
            }
        }

        test("a failed page read fails the poll") {
            withFetcher(FakeUtbt(newest = 2, capsStatus = HttpStatusCode.BadGateway)) { fetcher, _ ->
                shouldThrow<IOException> { fetcher.poll() }
            }
        }

        test("a demo UTBT does not serve is stored failed and not enqueued") {
            val utbt = FakeUtbt(newest = 2, demos = mapOf("cap-2" to { null }))
            withFetcher(utbt, known = listOf(knownDemo(1))) { fetcher, harness ->
                fetcher.poll()

                val (demo, recording) = harness.stored().last()
                demo.map.shouldBeNull()
                demo.duration.shouldBeNull()
                recording.state shouldBe RecordingState.FAILED
                recording.error.shouldNotBeNull()
                harness.queued().shouldBeEmpty()
            }
        }

        test("a demo that does not parse is stored failed and not enqueued") {
            val utbt = FakeUtbt(newest = 2, demos = mapOf("cap-2" to { byteArrayOf(1, 2, 3) }))
            withFetcher(utbt, known = listOf(knownDemo(1))) { fetcher, harness ->
                fetcher.poll()

                val (demo, recording) = harness.stored().last()
                demo.map.shouldBeNull()
                recording.state shouldBe RecordingState.FAILED
                harness.queued().shouldBeEmpty()
            }
        }

        test("a failed download writes nothing and the next pass retries it") {
            var failing = true
            val utbt = FakeUtbt(newest = 3, demos = mapOf("cap-2" to { if (failing) throw IOException("503") else null }))
            withFetcher(utbt, known = listOf(knownDemo(1))) { fetcher, harness ->
                fetcher.poll()

                harness.stored().map { it.first.id } shouldBe listOf("cap-1")

                failing = false
                fetcher.poll()

                harness.stored().map { it.first.id } shouldBe listOf("cap-1", "cap-2", "cap-3")
            }
        }

        test("backfill walks older caps newest first from the stored count, skipping known ones, until an empty page") {
            val utbt = FakeUtbt(newest = 6)
            withFetcher(utbt, known = listOf(knownDemo(6), knownDemo(3))) { fetcher, harness ->
                backgroundScope.launch { while (true) harness.queue.take(RecordingLane.SHORT) }
                fetcher.backfill()

                utbt.offsets shouldBe listOf(2, 5, 6)
                utbt.downloads.map { it.first } shouldBe listOf("cap-4", "cap-2", "cap-1")
                harness.stored().map { it.first.id } shouldBe listOf("cap-6", "cap-3", "cap-4", "cap-2", "cap-1")
            }
        }

        test("backfill waits the cooldown after each demo") {
            lateinit var scope: TestScope
            val utbt = FakeUtbt(newest = 3, now = { scope.testScheduler.currentTime })
            withFetcher(utbt, known = listOf(knownDemo(3))) { fetcher, harness ->
                scope = this
                backgroundScope.launch { while (true) harness.queue.take(RecordingLane.SHORT) }
                fetcher.backfill()

                utbt.downloads.map { it.second } shouldBe listOf(0L, 5.minutes.inWholeMilliseconds)
            }
        }

        test("backfill takes a demo only when the queue and the uploader are both empty") {
            var uploaderIsEmpty = false
            val utbt = FakeUtbt(newest = 2)
            withFetcher(utbt, known = listOf(knownDemo(2)), uploaderIsEmpty = { uploaderIsEmpty }) { fetcher, harness ->
                harness.queue.enqueue(knownDemo(9) to Recording(demoId = "cap-9", lane = RecordingLane.SHORT))
                val backfill = launch { fetcher.backfill() }

                advanceTimeBy(1.hours)
                utbt.downloads.shouldBeEmpty()

                uploaderIsEmpty = true
                advanceTimeBy(1.hours)
                utbt.downloads.shouldBeEmpty()

                harness.queue.take(RecordingLane.SHORT)
                backfill.join()
                utbt.downloads.map { it.first } shouldBe listOf("cap-1")
            }
        }
    })
