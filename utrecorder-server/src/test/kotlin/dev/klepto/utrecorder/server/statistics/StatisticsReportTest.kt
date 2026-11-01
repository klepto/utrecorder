package dev.klepto.utrecorder.server.statistics

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.DiskSpace
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.cache.CacheUsage
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StatisticsReportTest :
    FunSpec({
        val now = Instant.parse("2026-10-05T12:00:00Z")
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }

        fun demo(
            capId: String,
            timestamp: Instant,
            capTime: Duration = 10.seconds,
            userId: String? = capId,
            map: String = "CTF-BT-Maverick",
        ): Demo =
            Demo(
                capId = capId,
                map = map,
                player = "klepto",
                userId = userId,
                capTime = capTime,
                timestamp = timestamp,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        class Stored(
            val config: ServerConfig,
            val recordings: RecordingRepository,
            val jobs: RecordingJobs,
            val cache: CacheService,
            val report: StatisticsReport,
        ) {
            /**
             * Replaces the recording of [capId] with the result of [transform], keeping the [Recording.lastUpdate] it
             * sets. The second update changes no status, so the repository keeps that time.
             */
            suspend fun set(
                capId: String,
                transform: (Recording) -> Recording,
            ) {
                recordings.update(capId, transform)
                recordings.update(capId, transform)
            }
        }

        suspend fun stored(
            demos: List<Demo> = emptyList(),
            recorded: List<Demo> = emptyList(),
            diskSpace: DiskSpace = DiskSpace { 1_500 },
            block: suspend Stored.() -> Unit,
        ) {
            val root = createTempDirectory("utrecorder-statistics-report-test")
            SqliteDocumentStore.open(root.resolve("server.db")) { store ->
                val config =
                    ServerConfig(
                        ConfigReader.EMPTY,
                        DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home")),
                        dailyUploads = 7,
                        cacheMinFreeBytes = 1_000,
                        cacheTargetFreeBytes = 2_000,
                        recordingRecentAge = 1.days,
                        recordingRetryInterval = 12.hours,
                    )
                val repository = DemoRepository(store)
                val recordings = RecordingRepository(store, repository, clock)
                repository += demos
                recordings += recorded
                val queue = RecordingQueue(repository, recordings, config, clock)
                val jobs = RecordingJobs()
                val cache = CacheService(recordings, null, config, clock, diskSpace)
                val report = StatisticsReport(repository, recordings, queue, jobs, cache, config, clock)
                coroutineScope {
                    val running = launch { queue.start() }
                    queue.get(0)
                    Stored(config, recordings, jobs, cache, report).block()
                    running.cancelAndJoin()
                }
            }
        }

        test("reports zeros for every one of the 30 UTC days ending today, no pace and no cache usage without data") {
            stored {
                val statistics = report.read()

                statistics.generatedAt shouldBe now
                statistics.queue shouldBe QueueStatistics(0, 0, 0, Duration.ZERO, 0, 0, 0)
                statistics.recorder.pace shouldBe null
                statistics.recorder.lastDay shouldBe DayOutput(0, Duration.ZERO)
                statistics.recorder.days.size shouldBe 30
                statistics.recorder.days.first() shouldBe RecorderDay(LocalDate.parse("2026-09-06"), 0, Duration.ZERO, Duration.ZERO)
                statistics.recorder.days.last() shouldBe RecorderDay(LocalDate.parse("2026-10-05"), 0, Duration.ZERO, Duration.ZERO)
                statistics.storage shouldBe StorageStatistics(null, null, null, 0, 0, 0, 0, 7)
                statistics.personalBests.days.size shouldBe 30
                statistics.personalBests.days.first() shouldBe PersonalBestDay(LocalDate.parse("2026-09-06"), 0, 0)
                statistics.personalBests.days.last() shouldBe PersonalBestDay(LocalDate.parse("2026-10-05"), 0, 0)
                statistics.personalBests.maps shouldBe emptyList()
            }
        }

        test("counts personal bests by player and map, by timestamp then cap id, per UTC day of their timestamp") {
            stored(
                demos =
                    listOf(
                        demo("a-1", Instant.parse("2026-08-01T10:00:00Z"), 30.seconds, "a", "CTF-M1"),
                        demo("a-2", Instant.parse("2026-10-01T10:00:00Z"), 31.seconds, "a", "CTF-M1"),
                        demo("a-3", Instant.parse("2026-10-02T10:00:00Z"), 30.seconds, "a", "CTF-M1"),
                        demo("a-4", Instant.parse("2026-10-05T00:00:00Z"), 29.seconds, "a", "CTF-M1"),
                        demo("b-1", Instant.parse("2026-10-04T23:59:59Z"), 40.seconds, "b", "CTF-M1"),
                        demo("c-1", Instant.parse("2026-10-03T08:00:00Z"), 45.seconds, "c", "CTF-M2"),
                        demo("c-2", Instant.parse("2026-10-04T11:00:00Z"), 50.seconds, "c", "CTF-M2"),
                        demo("d-2", Instant.parse("2026-10-03T08:00:00Z"), 20.seconds, "d", "CTF-M3"),
                        demo("d-1", Instant.parse("2026-10-03T08:00:00Z"), 21.seconds, "d", "CTF-M3"),
                        demo("anonymous", Instant.parse("2026-10-05T01:00:00Z"), 5.seconds, null, "CTF-M4"),
                    ),
            ) {

                val statistics = report.read().personalBests

                statistics.lastDay shouldBe 2
                statistics.days.filter { it.personalBests > 0 } shouldBe
                    listOf(
                        PersonalBestDay(LocalDate.parse("2026-10-03"), 3, 2),
                        PersonalBestDay(LocalDate.parse("2026-10-04"), 1, 1),
                        PersonalBestDay(LocalDate.parse("2026-10-05"), 1, 1),
                    )
                statistics.players shouldBe 4
                statistics.demos shouldBe 10
                statistics.maps shouldBe listOf(MapCount("CTF-M1", 2), MapCount("CTF-M3", 2), MapCount("CTF-M2", 1))
            }
        }

        test("reports the queue, the recorder output by recordedAt and the stored mp4 files") {
            stored(
                recorded =
                    listOf(
                        demo("recent", now - 1.hours, 10.seconds),
                        demo("backlog", now - 3.days, 20.seconds),
                        demo("failed", now - 3.days),
                        demo("unresolvable", now - 3.days),
                        demo("recorded", now - 3.days, 60.seconds),
                        demo("archived", now - 3.days, 30.seconds),
                        demo("archived-old", now - 20.days, 40.seconds),
                        demo("outdated", now - 3.days, 20.seconds),
                        demo("uploaded", now - 3.days, 10.seconds),
                    ),
            ) {
                recordings.finish("failed", Failed)
                recordings.finish("unresolvable", Unresolvable)
                set("recorded") {
                    it.copy(status = Recorded, recordedAt = now - 1.hours, processingTime = 150.seconds, size = 100, views = 3)
                }
                set("archived") {
                    it.copy(
                        status = Archived,
                        recordedAt = now - 2.days,
                        processingTime = 60.seconds,
                        size = 200,
                        views = 4,
                        lastUpdate = now - 2.hours,
                    )
                }
                set("archived-old") {
                    it.copy(status = Archived, recordedAt = now - 10.days, processingTime = 400.seconds, lastUpdate = now - 30.hours)
                }
                set("outdated") {
                    it.copy(status = Outdated, recordedAt = Instant.parse("2026-10-05T00:00:00Z"), views = 1)
                }
                set("uploaded") {
                    it.copy(
                        status = Uploaded,
                        recordedAt = Instant.parse("2026-10-04T23:59:59Z"),
                        processingTime = 20.seconds,
                        size = 50,
                        lastUpdate = now - 1.hours,
                    )
                }
                config.recordings.createDirectories()
                config.mp4("recorded").writeBytes(ByteArray(1_234))
                cache.evict()
                val gate = CompletableDeferred<Unit>()
                coroutineScope {
                    launch { jobs.track(Recording(demo("recent", now - 1.hours))) { gate.await() } }
                    jobs.jobs.first { it.isNotEmpty() }

                    val statistics = report.read()

                    statistics.queue shouldBe QueueStatistics(2, 1, 1, 30.seconds, 1, 1, 1)
                    statistics.recorder.pace shouldBe 2.3
                    statistics.recorder.lastDay shouldBe DayOutput(3, 90.seconds)
                    statistics.recorder.days.filter { it.recordings > 0 } shouldBe
                        listOf(
                            RecorderDay(LocalDate.parse("2026-09-25"), 1, 40.seconds, 400.seconds),
                            RecorderDay(LocalDate.parse("2026-10-03"), 1, 30.seconds, 60.seconds),
                            RecorderDay(LocalDate.parse("2026-10-04"), 1, 10.seconds, 20.seconds),
                            RecorderDay(LocalDate.parse("2026-10-05"), 2, 80.seconds, 150.seconds),
                        )
                    statistics.recorder.completed shouldBe 4
                    statistics.recorder.views shouldBe 8
                    statistics.storage shouldBe StorageStatistics(CacheUsage(1_234, 1, now, 1_500), 734, 1_734, 2, 200, 1, 2, 7)
                    gate.complete(Unit)
                }
            }
        }

        test("clamps the cache limits at zero when the cache and the usable space are less than the free space limits") {
            stored(diskSpace = DiskSpace { 500 }) {
                cache.evict()

                val storage = report.read().storage

                storage.cache shouldBe CacheUsage(0, 0, now, 500)
                storage.cacheMaxBytes shouldBe 0
                storage.cacheTargetBytes shouldBe 0
            }
        }

        test("reports no cache limits when the last pass could not read the usable space") {
            stored(diskSpace = DiskSpace { throw IOException("unreadable") }) {
                cache.evict()

                val storage = report.read().storage

                storage.cache shouldBe CacheUsage(0, 0, now, null)
                storage.cacheMaxBytes shouldBe null
                storage.cacheTargetBytes shouldBe null
            }
        }
    })
