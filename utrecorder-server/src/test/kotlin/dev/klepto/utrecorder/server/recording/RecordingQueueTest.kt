package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class RecordingQueueTest :
    FunSpec({
        val home = tempdir().toPath()

        val now = Instant.parse("2026-09-27T12:00:00Z")
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }

        fun demo(
            capId: String,
            age: Duration,
            medal: Int? = null,
            capTime: Duration = 10.seconds,
            userId: String? = capId,
            map: String = "CTF-BT-Maverick",
            teammates: List<String> = emptyList(),
            teamRunId: String? = null,
        ): Demo =
            Demo(
                capId = capId,
                map = map,
                player = "klepto",
                userId = userId,
                capTime = capTime,
                timestamp = now - age,
                demoUrl = "http://api.utbt.net/demos/$capId",
                medal = medal,
                teammates = teammates,
                teamRunId = teamRunId,
            )

        suspend fun <R> stored(
            retryInterval: Duration = 12.hours,
            dailyUploads: Int = 200,
            backlogBuffer: Int = 1,
            source: Clock = clock,
            block: suspend (DemoRepository, RecordingRepository, RecordingQueue) -> R,
        ): R =
            SqliteDocumentStore.open(createTempDirectory("utrecorder-recording-queue-test").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, clock = source)
                val config =
                    ServerConfig(
                        ConfigReader.EMPTY,
                        DirectoriesConfig(ConfigReader.EMPTY, home = home),
                        dailyUploads = dailyUploads,
                        recordingRetryInterval = retryInterval,
                        recordingRecentAge = 1.days,
                        recordingBacklogBuffer = backlogBuffer,
                        archiveDelay = 1.days,
                    )
                block(demos, repository, RecordingQueue(demos, repository, config, source))
            }

        suspend fun <R> running(
            queue: RecordingQueue,
            block: suspend () -> R,
        ): R =
            coroutineScope {
                val job = launch { queue.start() }
                block().also { job.cancelAndJoin() }
            }

        suspend fun <R> queue(
            dailyUploads: Int = 200,
            backlogBuffer: Int = 1,
            source: Clock = clock,
            block: suspend (RecordingRepository, RecordingQueue) -> R,
        ): R =
            stored(dailyUploads = dailyUploads, backlogBuffer = backlogBuffer, source = source) { _, repository, queue ->
                running(queue) { block(repository, queue) }
            }

        suspend fun startedAt(
            repository: RecordingRepository,
            capId: String,
            start: Instant,
        ) {
            repository.update(capId) { it.copy(status = Archived, recordedAt = start + 1.minutes, processingTime = 1.minutes) }
        }

        suspend fun held(queue: RecordingQueue) {
            coroutineScope {
                val first = async { queue.first() }
                delay(50.milliseconds)
                first.isActive shouldBe true
                first.cancelAndJoin()
            }
        }

        suspend fun capIds(queue: RecordingQueue): List<String> = queue.get(10).map { it.demo.capId }

        suspend fun next(queue: RecordingQueue): Pair<String, Instant?>? = queue.next()?.let { it.recording.demo.capId to it.startsAt }

        suspend fun order(vararg demos: Demo): List<String> =
            queue { repository, queue ->
                repository += demos.toList()
                capIds(queue)
            }

        test("puts a recent demo, one up to the recent age old, ahead of an older one with a higher medal and a shorter cap time") {
            order(
                demo("backlog", 25.hours, medal = 6, capTime = 1.seconds),
                demo("recent", 1.days, medal = 0, capTime = 60.seconds),
            ) shouldBe listOf("recent", "backlog")
        }

        test("counts a demo from the future as recent") {
            order(demo("backlog", 2.days), demo("future", (-1).hours)) shouldBe listOf("future", "backlog")
        }

        test("puts a higher medal ahead of a shorter cap time and a newer demo") {
            order(
                demo("silver", 1.hours, medal = 3, capTime = 1.seconds),
                demo("world-record", 2.hours, medal = 6, capTime = 60.seconds),
            ) shouldBe listOf("world-record", "silver")
        }

        test("ranks a missing medal and a medal outside 0 to 6 as casual") {
            order(
                demo("unknown", 1.hours, medal = 7, capTime = 30.seconds),
                demo("negative", 1.hours, medal = -1, capTime = 25.seconds),
                demo("missing", 1.hours, medal = null, capTime = 20.seconds),
                demo("casual", 1.hours, medal = 0, capTime = 10.seconds),
                demo("certified", 1.hours, medal = 1, capTime = 40.seconds),
            ) shouldBe listOf("certified", "casual", "missing", "negative", "unknown")
        }

        test("puts a shorter cap time ahead of a newer demo") {
            order(
                demo("newer", 1.hours, capTime = 20.seconds),
                demo("shorter", 2.hours, capTime = 10.seconds),
            ) shouldBe listOf("shorter", "newer")
        }

        test("puts a newer demo ahead of an older one") {
            order(demo("older", 3.days), demo("newer", 2.days)) shouldBe listOf("newer", "older")
        }

        test("keeps the order the recordings were stored in for ties") {
            order(demo("b", 1.hours), demo("a", 1.hours)) shouldBe listOf("b", "a")
        }

        test("returns at most the maximum size of pending recordings and changes no status") {
            val got =
                queue { repository, queue ->
                    repository += listOf(demo("first", 1.hours), demo("failed", 2.hours), demo("second", 3.hours), demo("third", 4.hours))
                    repository.finish("failed", Failed)
                    queue.get(2).map { it.demo.capId } + capIds(queue) to repository.all().map { it.status }
                }
            got shouldBe (listOf("first", "second", "first", "second", "third") to listOf(Pending, Failed, Pending, Pending))
        }

        test("returns an empty list for a maximum size of 0 and rejects a negative one") {
            queue { repository, queue ->
                repository += listOf(demo("cap", 1.hours))
                queue.get(0) shouldBe emptyList()
                shouldThrow<IllegalArgumentException> { queue.get(-1) }
            }
        }

        test("returns the head of the queue from first and changes no status") {
            val got =
                queue { repository, queue ->
                    repository += listOf(demo("older", 2.hours), demo("newer", 1.hours))
                    listOf(queue.first().demo.capId, queue.first().demo.capId, repository["newer"]?.status)
                }
            got shouldBe listOf("newer", "newer", Pending)
        }

        test("suspends first until a recording is queued") {
            val head =
                queue { repository, queue ->
                    coroutineScope {
                        val first = async { queue.first() }
                        delay(50.milliseconds)
                        first.isActive shouldBe true
                        repository += listOf(demo("cap", 1.hours))
                        first.await().demo.capId
                    }
                }
            head shouldBe "cap"
        }

        test("holds back a backlog head while a recording is recording, and releases it once the recording is recorded") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("buffered", 3.days), demo("backlog", 2.days))
                    repository.claim("buffered")
                    coroutineScope {
                        val first = async { queue.first() }
                        delay(50.milliseconds)
                        first.isActive shouldBe true
                        repository.recorded("buffered", 1.hours, 1_024)
                        first.await().demo.capId
                    }
                }
            head shouldBe "backlog"
        }

        test("holds back a backlog head while a recording recorded the archive delay ago is not uploaded, and releases it once uploaded") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("buffered", 3.days), demo("backlog", 2.days))
                    repository.update("buffered") { it.copy(status = Recorded, recordedAt = now - 1.days) }
                    coroutineScope {
                        val first = async { queue.first() }
                        delay(50.milliseconds)
                        first.isActive shouldBe true
                        repository.finish("buffered", Uploaded)
                        first.await().demo.capId
                    }
                }
            head shouldBe "backlog"
        }

        test("returns a backlog head while every recorded recording is within the archive delay") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("first", 4.days), demo("second", 3.days), demo("backlog", 2.days))
                    repository.update("first") { it.copy(status = Recorded, recordedAt = now - 1.days + 1.seconds) }
                    repository.recorded("second", 1.hours, 1_024)
                    queue.first().demo.capId
                }
            head shouldBe "backlog"
        }

        test("holds back a backlog head while a recorded recording has no recording time") {
            queue { repository, queue ->
                repository += listOf(demo("buffered", 3.days), demo("backlog", 2.days))
                repository.finish("buffered", Recorded)
                held(queue)
            }
        }

        test("returns a recent head while the backlog buffer is full") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("recorded", 3.days), demo("backlog", 2.days), demo("recent", 1.hours))
                    repository.finish("recorded", Recorded)
                    queue.first().demo.capId
                }
            head shouldBe "recent"
        }

        test("holds back a backlog head until one interval after the last recording started") {
            queue { repository, queue ->
                repository += listOf(demo("paced", 3.days), demo("backlog", 2.days))
                startedAt(repository, "paced", now - 7.minutes)
                held(queue)
            }
        }

        test("returns a backlog head once one interval passed since the last recording started, however late it was recorded") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("paced", 3.days), demo("backlog", 2.days))
                    repository.update("paced") { it.copy(status = Archived, recordedAt = now, processingTime = 8.minutes) }
                    queue.first().demo.capId
                }
            head shouldBe "backlog"
        }

        test("holds back a backlog head while the recordings of the last day spend the daily uploads, and returns a recent head at once") {
            val head =
                queue(dailyUploads = 2) { repository, queue ->
                    repository += listOf(demo("first", 4.days), demo("second", 3.days), demo("backlog", 2.days))
                    startedAt(repository, "first", now - 23.hours)
                    startedAt(repository, "second", now - 22.hours)
                    coroutineScope {
                        val first = async { queue.first() }
                        delay(50.milliseconds)
                        first.isActive shouldBe true
                        repository += listOf(demo("recent", 1.hours))
                        first.await().demo.capId
                    }
                }
            head shouldBe "recent"
        }

        test("ignores recordings started more than a day ago in the pace") {
            val head =
                queue(dailyUploads = 2) { repository, queue ->
                    repository += listOf(demo("first", 6.days), demo("second", 5.days), demo("third", 4.days), demo("backlog", 2.days))
                    startedAt(repository, "first", now - 30.hours)
                    startedAt(repository, "second", now - 29.hours)
                    startedAt(repository, "third", now - 28.hours)
                    queue.first().demo.capId
                }
            head shouldBe "backlog"
        }

        test("returns a backlog head once time passing opens the pace") {
            val start = TimeSource.Monotonic.markNow()
            val moving =
                object : Clock {
                    override fun now(): Instant = now + start.elapsedNow()
                }
            val head =
                queue(dailyUploads = 172_800, source = moving) { repository, queue ->
                    repository += listOf(demo("paced", 3.days), demo("backlog", 2.days))
                    repository.recorded("paced", Duration.ZERO, 1_024)
                    coroutineScope {
                        val first = async { queue.first() }
                        delay(100.milliseconds)
                        first.isActive shouldBe true
                        first.await().demo.capId
                    }
                }
            head shouldBe "backlog"
        }

        test("reports now as the start of a recent head while the backlog buffer is full") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("recorded", 3.days), demo("backlog", 2.days), demo("recent", 1.hours))
                    repository.finish("recorded", Recorded)
                    next(queue)
                }
            head shouldBe ("recent" to now)
        }

        test("reports no start for a backlog head while the backlog buffer is full") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("recorded", 3.days), demo("backlog", 2.days))
                    repository.finish("recorded", Recorded)
                    next(queue)
                }
            head shouldBe ("backlog" to null)
        }

        test("reports the next free slot as the start of a backlog head the pace holds back") {
            val head =
                queue { repository, queue ->
                    repository += listOf(demo("paced", 3.days), demo("backlog", 2.days))
                    startedAt(repository, "paced", now - 7.minutes)
                    next(queue)
                }
            head shouldBe ("backlog" to now + 12.seconds)
        }

        test("reports the slot after a running recording from the time it was claimed") {
            var at = now
            val moving =
                object : Clock {
                    override fun now(): Instant = at
                }
            val head =
                queue(backlogBuffer = 2, source = moving) { repository, queue ->
                    repository += listOf(demo("running", 3.days), demo("backlog", 2.days))
                    repository.claim("running")
                    at = now + 1.minutes
                    next(queue)
                }
            head shouldBe ("backlog" to now + 432.seconds)
        }

        test("reports no head for an empty queue") {
            queue { _, queue -> queue.next() } shouldBe null
        }

        test("waits in first and get until start has set recordings pending again and built the queue") {
            val got =
                stored { demos, repository, queue ->
                    repository += listOf(demo("failed", 1.hours))
                    repository.finish("failed", Failed)
                    demos += listOf(demo("cap", 2.hours))
                    coroutineScope {
                        val first = async { queue.first().demo.capId }
                        val capIds = async { capIds(queue) }
                        delay(50.milliseconds)
                        first.isActive shouldBe true
                        capIds.isActive shouldBe true
                        running(queue) { first.await() to capIds.await() }
                    }
                }
            got shouldBe ("failed" to listOf("failed", "cap"))
        }

        test("queues only the fastest demo of each player on each map, and no demo without a user id") {
            val got =
                stored { demos, repository, queue ->
                    demos +=
                        listOf(
                            demo("slower", 7.hours, userId = "1", capTime = 12.seconds),
                            demo("fastest", 6.hours, userId = "1"),
                            demo("tie", 5.hours, userId = "1"),
                            demo("other-player", 4.hours, userId = "2", capTime = 12.seconds),
                            demo("other-map", 3.hours, userId = "1", capTime = 12.seconds, map = "CTF-BT-Other"),
                            demo("no-user", 2.hours, userId = null, capTime = 12.seconds),
                            demo("no-user-again", 1.hours, userId = null, capTime = 12.seconds),
                        )
                    running(queue) { capIds(queue) to repository.all().map { it.demo.capId } }
                }
            got shouldBe (listOf("fastest", "other-map", "other-player") to listOf("fastest", "other-player", "other-map"))
        }

        test("queues the solo best and the best with each team of a player side by side") {
            val got =
                stored { demos, _, queue ->
                    demos +=
                        listOf(
                            demo("solo", 5.hours, userId = "1", capTime = 20.seconds),
                            demo("one-a", 4.hours, userId = "1", capTime = 12.seconds, teammates = listOf("2"), teamRunId = "A"),
                            demo("two-a", 4.hours, userId = "2", capTime = 12.seconds, teammates = listOf("1"), teamRunId = "A"),
                            demo("one-b", 3.hours, userId = "1", capTime = 11.seconds, teammates = listOf("3"), teamRunId = "B"),
                            demo("three-b", 3.hours, userId = "3", capTime = 11.seconds, teammates = listOf("1"), teamRunId = "B"),
                            demo("one-c", 2.hours, userId = "1", capTime = 13.seconds, teammates = listOf("2"), teamRunId = "C"),
                            demo("two-c", 2.hours, userId = "2", capTime = 13.seconds, teammates = listOf("1"), teamRunId = "C"),
                        )
                    running(queue) { capIds(queue).toSet() }
                }
            got shouldBe setOf("solo", "one-a", "two-a", "one-b", "three-b")
        }

        test("removes the pending recording of a team once a faster run with the same teammates arrives") {
            stored { demos, repository, queue ->
                demos +=
                    listOf(
                        demo("one-slower", 2.hours, userId = "1", capTime = 12.seconds, teammates = listOf("2"), teamRunId = "A"),
                        demo("two-slower", 2.hours, userId = "2", capTime = 12.seconds, teammates = listOf("1"), teamRunId = "A"),
                    )
                running(queue) {
                    capIds(queue).toSet() shouldBe setOf("one-slower", "two-slower")
                    demos +=
                        listOf(
                            demo("one-faster", 1.hours, userId = "1", teammates = listOf("2"), teamRunId = "B"),
                            demo("two-faster", 1.hours, userId = "2", teammates = listOf("1"), teamRunId = "B"),
                        )
                    eventually(5.seconds) {
                        repository.all().map { it.demo.capId }.toSet() shouldBe setOf("one-faster", "two-faster")
                    }
                }
            }
        }

        test("compares team runs by the slowest cap time of the members") {
            val got =
                stored { demos, _, queue ->
                    demos +=
                        listOf(
                            demo("one-a", 4.hours, userId = "1", capTime = 90.seconds, teammates = listOf("2"), teamRunId = "A"),
                            demo("two-a", 4.hours, userId = "2", capTime = 95.seconds, teammates = listOf("1"), teamRunId = "A"),
                            demo("one-b", 3.hours, userId = "1", capTime = 92.seconds, teammates = listOf("2"), teamRunId = "B"),
                            demo("two-b", 3.hours, userId = "2", capTime = 93.seconds, teammates = listOf("1"), teamRunId = "B"),
                        )
                    running(queue) { capIds(queue) }
                }
            got shouldBe listOf("one-b", "two-b")
        }

        test("sets the run medal of every member of a team run to the highest medal of the run, and of a solo recording to its own medal") {
            val got =
                stored { demos, _, queue ->
                    demos +=
                        listOf(
                            demo("one", 2.hours, medal = 5, userId = "1", teammates = listOf("2", "3"), teamRunId = "A"),
                            demo("two", 2.hours, medal = 6, userId = "2", teammates = listOf("1", "3"), teamRunId = "A"),
                            demo("three", 2.hours, medal = 7, userId = "3", teammates = listOf("1", "2"), teamRunId = "A"),
                            demo("solo", 1.hours, medal = 3, userId = "4"),
                        )
                    running(queue) { queue.get(10).associate { it.demo.capId to it.runMedal } }
                }
            got shouldBe mapOf("one" to 6, "two" to 6, "three" to 6, "solo" to 3)
        }

        test("ranks a team recording by the medal of its run") {
            val got =
                stored { demos, _, queue ->
                    demos +=
                        listOf(
                            demo("gold", 1.hours, medal = 4, userId = "3", capTime = 5.seconds),
                            demo("one", 2.hours, medal = 1, userId = "1", capTime = 12.seconds, teammates = listOf("2"), teamRunId = "A"),
                            demo("two", 2.hours, medal = 6, userId = "2", capTime = 11.seconds, teammates = listOf("1"), teamRunId = "A"),
                        )
                    running(queue) { capIds(queue) }
                }
            got shouldBe listOf("two", "one", "gold")
        }

        test("queues a team demo only once the demo of every teammate of its run is stored") {
            stored { demos, _, queue ->
                demos +=
                    listOf(
                        demo("one", 3.hours, userId = "1", teammates = listOf("2", "3"), teamRunId = "A"),
                        demo("two", 3.hours, userId = "2", teammates = listOf("1", "3"), teamRunId = "A"),
                        demo("three-other-run", 2.hours, userId = "3", capTime = 5.seconds, teammates = listOf("1", "2"), teamRunId = "B"),
                        demo("four-no-run", 2.hours, userId = "4", teammates = listOf("5")),
                        demo("five-no-run", 2.hours, userId = "5", teammates = listOf("4")),
                    )
                running(queue) {
                    capIds(queue) shouldBe emptyList()
                    demos +=
                        listOf(demo("three", 1.hours, userId = "3", capTime = 12.seconds, teammates = listOf("1", "2"), teamRunId = "A"))
                    eventually(5.seconds) { capIds(queue).toSet() shouldBe setOf("one", "two", "three") }
                }
            }
        }

        test("sets a completed team recording to outdated only once the faster run of the same teammates is complete") {
            stored { demos, repository, queue ->
                repository +=
                    listOf(
                        demo("one-old", 4.hours, userId = "1", capTime = 12.seconds, teammates = listOf("2"), teamRunId = "A"),
                        demo("one-new", 2.hours, userId = "1", capTime = 10.seconds, teammates = listOf("2"), teamRunId = "B"),
                    )
                repository.finish("one-old", Recorded)
                repository.finish("one-new", Recorded)
                demos += listOf(demo("two-old", 4.hours, userId = "2", capTime = 14.seconds, teammates = listOf("1"), teamRunId = "A"))
                running(queue) {
                    capIds(queue) shouldBe listOf("two-old")
                    repository["one-old"]?.status shouldBe Recorded
                    repository["one-new"]?.status shouldBe Recorded
                    demos += listOf(demo("two-new", 2.hours, userId = "2", capTime = 11.seconds, teammates = listOf("1"), teamRunId = "B"))
                    eventually(5.seconds) {
                        repository["one-old"]?.status shouldBe Outdated
                        capIds(queue) shouldBe listOf("two-new")
                    }
                    repository["one-new"]?.status shouldBe Recorded
                }
            }
        }

        test("sets a beaten team recording to outdated only once a faster run of the same teammates is recorded") {
            stored { demos, repository, queue ->
                repository += listOf(demo("old", 4.hours, userId = "1", capTime = 12.seconds, teammates = listOf("2"), teamRunId = "A"))
                repository.finish("old", Recorded)
                demos +=
                    listOf(
                        demo("solo", 3.hours, userId = "1", capTime = 5.seconds),
                        demo("partner", 4.hours, userId = "2", capTime = 14.seconds, teammates = listOf("1"), teamRunId = "A"),
                        demo("new", 2.hours, userId = "1", capTime = 10.seconds, teammates = listOf("2"), teamRunId = "B"),
                        demo("new-partner", 2.hours, userId = "2", capTime = 11.seconds, teammates = listOf("1"), teamRunId = "B"),
                    )
                running(queue) {
                    eventually(5.seconds) { capIds(queue) shouldContain "new" }
                    repository["old"]?.status shouldBe Recorded
                    repository.finish("solo", Recorded)
                    repository["old"]?.status shouldBe Recorded
                    repository.finish("new", Recorded)
                    eventually(5.seconds) { repository["old"]?.status shouldBe Outdated }
                    repository["solo"]?.status shouldBe Recorded
                }
            }
        }

        test("builds the queue again once a demo is added") {
            stored { demos, _, queue ->
                running(queue) {
                    capIds(queue) shouldBe emptyList()
                    demos += listOf(demo("cap", 1.hours))
                    eventually(5.seconds) { capIds(queue) shouldBe listOf("cap") }
                }
            }
        }

        test("removes the pending recording of a demo once a faster demo of its player on its map arrives") {
            stored { demos, repository, queue ->
                demos += listOf(demo("slower", 2.hours, userId = "1", capTime = 12.seconds))
                running(queue) {
                    capIds(queue) shouldBe listOf("slower")
                    demos += listOf(demo("faster", 1.hours, userId = "1"))
                    eventually(5.seconds) { repository.all().map { it.demo.capId } shouldBe listOf("faster") }
                }
            }
        }

        test("sets a beaten recorded, uploaded or archived recording to outdated once the faster one is recorded") {
            stored { demos, repository, queue ->
                repository +=
                    listOf(
                        demo("recorded", 6.hours, userId = "1", capTime = 12.seconds),
                        demo("uploaded", 5.hours, userId = "2", capTime = 12.seconds),
                        demo("archived", 4.hours, userId = "3", capTime = 12.seconds),
                    )
                repository.finish("recorded", Recorded)
                repository.update("uploaded") { it.copy(status = Uploaded, md5 = "uploaded-md5") }
                repository.update("archived") { it.copy(status = Archived, md5 = "archived-md5", views = 2) }
                demos +=
                    listOf(
                        demo("faster-recorded", 3.hours, userId = "1"),
                        demo("faster-uploaded", 2.hours, userId = "2"),
                        demo("faster-archived", 1.hours, userId = "3"),
                    )
                running(queue) {
                    capIds(queue) shouldBe listOf("faster-archived", "faster-uploaded", "faster-recorded")
                    listOf("faster-recorded", "faster-uploaded", "faster-archived").forEach { repository.finish(it, Recorded) }
                    eventually(5.seconds) {
                        repository["recorded"]?.status shouldBe Outdated
                        repository["uploaded"]?.status shouldBe Outdated
                        repository["archived"]?.status shouldBe Outdated
                    }
                }
                repository["uploaded"]?.md5 shouldBe "uploaded-md5"
                repository["archived"]?.md5 shouldBe "archived-md5"
                repository["archived"]?.views shouldBe 2
            }
        }

        test("keeps a recorded recording while the faster demo of its player on its map is only pending or failed") {
            stored { demos, repository, queue ->
                repository +=
                    listOf(
                        demo("beaten-by-pending", 4.hours, userId = "1", capTime = 12.seconds),
                        demo("beaten-by-failed", 3.hours, userId = "2", capTime = 12.seconds),
                    )
                repository.finish("beaten-by-pending", Recorded)
                repository.finish("beaten-by-failed", Recorded)
                demos += listOf(demo("pending", 2.hours, userId = "1"), demo("failed", 1.hours, userId = "2"))
                running(queue) {
                    capIds(queue) shouldBe listOf("failed", "pending")
                    repository.finish("failed", Failed)
                    delay(100.milliseconds)
                    repository["failed"]?.status shouldBe Failed
                    repository["beaten-by-pending"]?.status shouldBe Recorded
                    repository["beaten-by-failed"]?.status shouldBe Recorded
                }
            }
        }

        test("sets a beaten recording to outdated once its capture ends") {
            stored { demos, repository, queue ->
                demos += listOf(demo("slower", 2.hours, userId = "1", capTime = 12.seconds))
                running(queue) {
                    capIds(queue) shouldBe listOf("slower")
                    repository.claim("slower")
                    demos += listOf(demo("faster", 1.hours, userId = "1"))
                    eventually(5.seconds) { capIds(queue) shouldBe listOf("faster") }
                    repository.finish("faster", Recorded)
                    delay(100.milliseconds)
                    repository["slower"]?.status shouldBe RecordingStatus.Recording
                    repository.finish("slower", Recorded)
                    eventually(5.seconds) { repository["slower"]?.status shouldBe Outdated }
                }
            }
        }

        test("raises no status change from a build that changes nothing") {
            val changes =
                stored { demos, repository, queue ->
                    repository += listOf(demo("slower", 2.hours, userId = "1", capTime = 12.seconds))
                    repository.finish("slower", Recorded)
                    demos += listOf(demo("faster", 1.hours, userId = "1"))
                    running(queue) {
                        capIds(queue) shouldBe listOf("faster")
                        repository.finish("faster", Recorded)
                        eventually(5.seconds) { repository["slower"]?.status shouldBe Outdated }
                        delay(100.milliseconds)
                        val count = AtomicInteger()
                        coroutineScope {
                            val job = launch { repository.statusChanges.collect { count.incrementAndGet() } }
                            delay(200.milliseconds)
                            job.cancelAndJoin()
                        }
                        count.get()
                    }
                }
            changes shouldBe 1
        }

        test("queues recording, failed and unresolvable recordings again on start, and keeps the rest") {
            val got =
                stored { _, repository, queue ->
                    repository += listOf(demo("recording", 4.hours))
                    repository.claim("recording")
                    repository += listOf(demo("failed", 3.hours), demo("recorded", 2.hours), demo("unresolvable", 1.hours))
                    repository.finish("failed", Failed, "Capture failed")
                    repository.finish("recorded", Recorded)
                    repository.finish("unresolvable", Unresolvable, "Missing BTPog")
                    running(queue) { capIds(queue) to repository["recorded"]?.status }
                }
            got shouldBe (listOf("unresolvable", "failed", "recording") to Recorded)
        }

        test("queues failed and unresolvable recordings again once the retry interval passes, and leaves outdated and the rest") {
            stored(retryInterval = 300.milliseconds) { _, repository, queue ->
                running(queue) {
                    repository +=
                        listOf(
                            demo("busy", 5.hours),
                            demo("failed", 4.hours),
                            demo("unresolvable", 3.hours),
                            demo("recorded", 2.hours),
                            demo("outdated", 1.hours),
                        )
                    repository.claim("busy")
                    repository.finish("failed", Failed, "Capture failed")
                    repository.finish("unresolvable", Unresolvable, "Missing BTPog")
                    repository.finish("recorded", Recorded)
                    repository.finish("outdated", Outdated, "Capture failed")
                    eventually(5.seconds) { capIds(queue) shouldBe listOf("unresolvable", "failed") }
                    delay(600.milliseconds)
                    repository.all().map { it.demo.capId to it.status } shouldBe
                        listOf(
                            "busy" to RecordingStatus.Recording,
                            "failed" to Pending,
                            "unresolvable" to Pending,
                            "recorded" to Recorded,
                            "outdated" to Outdated,
                        )
                }
            }
        }
    })
