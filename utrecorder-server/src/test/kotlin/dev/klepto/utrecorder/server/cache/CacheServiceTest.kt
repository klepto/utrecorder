package dev.klepto.utrecorder.server.cache

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.DiskSpace
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.DocumentCollection
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.DocumentTransaction
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recording
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import dev.klepto.utrecorder.server.upload.MediaMetadata
import dev.klepto.utrecorder.server.upload.MediaUploader
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CacheServiceTest :
    FunSpec({
        val root = tempdir().toPath()
        val now = AtomicReference(Instant.parse("2026-09-27T08:00:00Z"))
        val clock =
            object : Clock {
                override fun now(): Instant = now.get()
            }

        val requests = AtomicInteger()
        val puts = AtomicInteger()
        val path = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            path.set(exchange.requestURI.path)
            val content = "archived".toByteArray()
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.start()
        afterSpec { server.stop(0) }

        beforeTest {
            now.set(Instant.parse("2026-09-27T08:00:00Z"))
            requests.set(0)
            puts.set(0)
            path.set(null)
        }

        fun counting(store: DocumentStore): DocumentStore =
            object : DocumentStore {
                override fun <T : Any> collection(
                    name: String,
                    serializer: KSerializer<T>,
                    key: (T) -> String,
                ): DocumentCollection<T> {
                    val collection = store.collection(name, serializer, key)
                    return object : DocumentCollection<T> {
                        override suspend fun <R> transaction(block: suspend (DocumentTransaction<T>) -> R): R =
                            collection.transaction { stored ->
                                block(
                                    object : DocumentTransaction<T> by stored {
                                        override suspend fun put(document: T) {
                                            puts.incrementAndGet()
                                            stored.put(document)
                                        }
                                    },
                                )
                            }
                    }
                }
            }

        fun demo(capId: String): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        val uploader =
            object : MediaUploader {
                override suspend fun upload(
                    id: String,
                    file: Path,
                    metadata: MediaMetadata,
                ) {}

                override suspend fun isUploaded(
                    id: String,
                    md5: String,
                ): Boolean = true

                override suspend fun describe(
                    id: String,
                    metadata: MediaMetadata,
                ) {}

                override suspend fun delete(
                    id: String,
                    name: String,
                ) {}

                override suspend fun size(
                    id: String,
                    name: String,
                ): Long? = null

                override fun downloadUrl(
                    id: String,
                    name: String,
                ): String = "http://${server.address.hostString}:${server.address.port}/$id/$name"
            }

        suspend fun logged(block: suspend () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(CacheService::class.java.name) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            try {
                block()
                return appender.list.toList()
            } finally {
                logger.detachAppender(appender)
            }
        }

        suspend fun cache(
            statuses: Map<String, RecordingStatus>,
            mp4s: List<String>,
            diskSpace: DiskSpace = DiskSpace { 1_000 },
            minFreeBytes: Long = 100,
            targetFreeBytes: Long = 200,
            backlogTtl: Duration = Duration.ZERO,
            block: suspend (CacheService, RecordingRepository, Path) -> Unit,
        ) {
            val directory = createTempDirectory(root, "cache")
            val recordings = directory.resolve("recordings").createDirectories()
            mp4s.forEach { recordings.resolve("$it.mp4").writeText(it) }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories = DirectoriesConfig(ConfigReader.EMPTY, home = directory.resolve("home")),
                    recordings = recordings,
                    cacheTtl = 14.days,
                    cacheBacklogTtl = backlogTtl,
                    cacheMinFreeBytes = minFreeBytes,
                    cacheTargetFreeBytes = targetFreeBytes,
                )
            SqliteDocumentStore.open(directory.resolve("server.db")) { store ->
                val repository = RecordingRepository(counting(store), DemoRepository(store), clock)
                repository += statuses.keys.map { demo(it) }
                // MD5 of "archived".
                statuses.forEach { (capId, status) ->
                    repository.update(capId) {
                        it.copy(status = status, md5 = "0d111f56964985f562aaaaf9d2980787", itemId = "1234-3e92a055-ctf-bt-maverick")
                    }
                }
                block(CacheService(repository, uploader, config, clock, diskSpace), repository, recordings)
            }
        }

        test("deletes the mp4 of an archived recording not viewed within the cache TTL, and keeps the recording") {
            cache(mapOf("expired" to Archived), listOf("expired")) { service, repository, recordings ->
                now.set(now.get() + 15.days)
                service.evict()

                recordings.resolve("expired.mp4").shouldNotExist()
                repository["expired"]?.status shouldBe Archived
            }
        }

        test("keeps the mp4 of an archived recording viewed within the cache TTL") {
            cache(mapOf("viewed" to Archived), listOf("viewed")) { service, _, recordings ->
                now.set(now.get() + 13.days)
                service.view("viewed")
                now.set(now.get() + 13.days)
                service.evict()

                recordings.resolve("viewed.mp4").shouldExist()
            }
        }

        test("keeps the mp4 of an archived recording without a recording time until the cache TTL passes from its archiving") {
            cache(mapOf("fresh" to Archived), listOf("fresh")) { service, _, recordings ->
                now.set(now.get() + 13.days)
                service.evict()

                recordings.resolve("fresh.mp4").shouldExist()
            }
        }

        test("deletes the mp4 of an archived recording once the cache TTL passes from its recording, however lately it was archived") {
            cache(mapOf("early" to Archived), listOf("early")) { service, repository, recordings ->
                repository.update("early") { it.copy(recordedAt = Instant.parse("2026-09-17T08:00:00Z")) }
                now.set(now.get() + 5.days)
                service.evict()

                recordings.resolve("early.mp4").shouldNotExist()
                repository["early"]?.status shouldBe Archived
            }
        }

        test("keeps the mp4 of an archived recording recorded within the cache TTL, though its last view is older") {
            cache(mapOf("viewed" to Archived), listOf("viewed")) { service, repository, recordings ->
                service.view("viewed")
                repository.update("viewed") { it.copy(recordedAt = Instant.parse("2026-10-07T08:00:00Z")) }
                now.set(now.get() + 20.days)
                service.evict()

                recordings.resolve("viewed.mp4").shouldExist()
            }
        }

        test("deletes the mp4 of an outdated recording once the cache TTL passes from when it became outdated") {
            cache(mapOf("outdated" to Outdated), listOf("outdated")) { service, repository, recordings ->
                now.set(now.get() + 15.days)
                service.evict()

                recordings.resolve("outdated.mp4").shouldNotExist()
                repository["outdated"]?.status shouldBe Outdated
            }
        }

        test("keeps the mp4 of an outdated recording within the cache TTL") {
            cache(mapOf("outdated" to Outdated), listOf("outdated")) { service, _, recordings ->
                now.set(now.get() + 13.days)
                service.evict()

                recordings.resolve("outdated.mp4").shouldExist()
            }
        }

        test("does not extend the cache TTL of an outdated recording by viewing it") {
            cache(mapOf("outdated" to Outdated), listOf("outdated")) { service, _, recordings ->
                now.set(now.get() + 13.days)
                service.view("outdated")
                now.set(now.get() + 2.days)
                service.evict()

                recordings.resolve("outdated.mp4").shouldNotExist()
            }
        }

        test("keeps the mp4 of a recorded or uploaded recording however old") {
            cache(mapOf("recorded" to Recorded, "uploaded" to Uploaded), listOf("recorded", "uploaded")) { service, _, recordings ->
                now.set(now.get() + 365.days)
                service.evict()

                recordings.resolve("recorded.mp4").shouldExist()
                recordings.resolve("uploaded.mp4").shouldExist()
            }
        }

        test("deletes an mp4 that has no recording") {
            cache(mapOf("known" to Recorded), listOf("known", "orphan")) { service, _, recordings ->
                service.evict()

                recordings.resolve("orphan.mp4").shouldNotExist()
                recordings.resolve("known.mp4").shouldExist()
            }
        }

        test("ignores an archived recording whose mp4 is missing") {
            cache(mapOf("missing" to Archived), emptyList()) { service, _, recordings ->
                now.set(now.get() + 8.days)
                service.evict()

                recordings.resolve("missing.mp4").shouldNotExist()
            }
        }

        // Every demo of these tests is capped at 2026-09-26T12:00:00Z, so a recording made after 2026-09-27T12:00:00Z
        // is a backlog recording.
        test("deletes the mp4 of an unviewed archived backlog recording on the next pass, though it was recorded minutes ago") {
            cache(mapOf("backlog" to Archived), listOf("backlog")) { service, repository, recordings ->
                now.set(Instant.parse("2026-10-01T08:00:00Z"))
                repository.update("backlog") { it.copy(recordedAt = now.get() - 5.minutes) }
                service.evict()

                recordings.resolve("backlog.mp4").shouldNotExist()
                repository["backlog"]?.status shouldBe Archived
            }
        }

        test("keeps the mp4 of an unviewed archived backlog recording until the backlog cache TTL passes from its recording") {
            cache(mapOf("backlog" to Archived), listOf("backlog"), backlogTtl = 12.hours) { service, repository, recordings ->
                now.set(Instant.parse("2026-10-01T08:00:00Z"))
                repository.update("backlog") { it.copy(recordedAt = now.get()) }
                service.evict()
                recordings.resolve("backlog.mp4").shouldExist()

                now.set(now.get() + 12.hours)
                service.evict()
                recordings.resolve("backlog.mp4").shouldExist()

                now.set(now.get() + 1.seconds)
                service.evict()
                recordings.resolve("backlog.mp4").shouldNotExist()
            }
        }

        test("keeps the mp4 of a viewed archived backlog recording for the cache TTL from its last view") {
            cache(mapOf("viewed" to Archived), listOf("viewed")) { service, repository, recordings ->
                val lastView = Instant.parse("2026-10-01T08:00:00Z")
                now.set(lastView)
                repository.update("viewed") { it.copy(recordedAt = lastView - 5.minutes, views = 1, lastView = lastView) }
                service.evict()
                recordings.resolve("viewed.mp4").shouldExist()

                now.set(lastView + 14.days)
                service.evict()
                recordings.resolve("viewed.mp4").shouldExist()

                now.set(now.get() + 1.seconds)
                service.evict()
                recordings.resolve("viewed.mp4").shouldNotExist()
            }
        }

        test("keeps the mp4 of an archived backlog recording for the cache TTL once a view is counted, before it is flushed") {
            cache(mapOf("viewed" to Archived), listOf("viewed")) { service, repository, recordings ->
                now.set(Instant.parse("2026-10-01T08:00:00Z"))
                repository.update("viewed") { it.copy(recordedAt = now.get() - 5.minutes) }
                service.view("viewed")
                service.evict()
                recordings.resolve("viewed.mp4").shouldExist()
                repository["viewed"]?.views shouldBe 0

                now.set(now.get() + 13.days)
                service.evict()
                recordings.resolve("viewed.mp4").shouldExist()

                now.set(now.get() + 2.days)
                service.evict()
                recordings.resolve("viewed.mp4").shouldNotExist()
            }
        }

        test("keeps the mp4 of an unviewed archived recent recording for the cache TTL, though its demo is no longer recent") {
            cache(mapOf("recent" to Archived, "edge" to Archived), listOf("recent", "edge")) { service, repository, recordings ->
                repository.update("recent") { it.copy(recordedAt = Instant.parse("2026-09-27T07:00:00Z")) }
                repository.update("edge") { it.copy(recordedAt = Instant.parse("2026-09-27T12:00:00Z")) }
                now.set(Instant.parse("2026-10-01T08:00:00Z"))
                service.evict()

                recordings.resolve("recent.mp4").shouldExist()
                recordings.resolve("edge.mp4").shouldExist()

                now.set(Instant.parse("2026-10-11T12:00:01Z"))
                service.evict()

                recordings.resolve("recent.mp4").shouldNotExist()
                recordings.resolve("edge.mp4").shouldNotExist()
            }
        }

        test("keeps the mp4 of an unviewed recorded, uploaded or recording backlog recording, and of an outdated one within its TTL") {
            val statuses = mapOf("recorded" to Recorded, "uploaded" to Uploaded, "recording" to Recording, "outdated" to Outdated)
            cache(statuses, statuses.keys.toList()) { service, repository, recordings ->
                now.set(Instant.parse("2026-10-01T08:00:00Z"))
                statuses.keys.forEach { capId -> repository.update(capId) { it.copy(recordedAt = now.get() - 5.minutes) } }
                service.evict()

                statuses.keys.forEach { recordings.resolve("$it.mp4").shouldExist() }
            }
        }

        test("downloads the mp4 of an evicted backlog recording again once it is viewed, and keeps it for the cache TTL") {
            cache(mapOf("backlog" to Archived), listOf("backlog")) { service, repository, recordings ->
                now.set(Instant.parse("2026-10-01T08:00:00Z"))
                repository.update("backlog") { it.copy(recordedAt = now.get() - 5.minutes) }
                coroutineScope {
                    val job = launch { service.start() }
                    eventually(5.seconds) { recordings.resolve("backlog.mp4").shouldNotExist() }
                    service.view("backlog")
                    eventually(5.seconds) { recordings.resolve("backlog.mp4").readText() shouldBe "archived" }
                    service.evict()
                    job.cancelAndJoin()
                }

                recordings.resolve("backlog.mp4").shouldExist()
                requests.get() shouldBe 1
            }
        }

        test("deletes on the first pass after start only the mp4 files of unviewed archived backlog recordings found at boot") {
            val statuses =
                mapOf(
                    "backlog1" to Archived,
                    "backlog2" to Archived,
                    "viewed" to Archived,
                    "recent" to Archived,
                    "recorded" to Recorded,
                )
            cache(statuses, statuses.keys.toList()) { service, repository, recordings ->
                val boot = Instant.parse("2026-09-30T08:00:00Z")
                repository.update("backlog1") { it.copy(recordedAt = Instant.parse("2026-09-30T07:00:00Z")) }
                repository.update("backlog2") { it.copy(recordedAt = Instant.parse("2026-09-30T07:55:00Z")) }
                repository.update("viewed") {
                    it.copy(
                        recordedAt = Instant.parse("2026-09-30T07:00:00Z"),
                        views = 2,
                        lastView = Instant.parse("2026-09-30T07:30:00Z"),
                    )
                }
                repository.update("recent") { it.copy(recordedAt = Instant.parse("2026-09-27T07:00:00Z")) }
                repository.update("recorded") { it.copy(recordedAt = Instant.parse("2026-09-30T07:00:00Z")) }
                now.set(boot)
                coroutineScope {
                    val job = launch { service.start() }
                    eventually(5.seconds) { service.usage.shouldNotBeNull() }
                    job.cancelAndJoin()
                }

                recordings.resolve("backlog1.mp4").shouldNotExist()
                recordings.resolve("backlog2.mp4").shouldNotExist()
                recordings.resolve("viewed.mp4").shouldExist()
                recordings.resolve("recent.mp4").shouldExist()
                recordings.resolve("recorded.mp4").shouldExist()
                service.usage shouldBe CacheUsage(20, 3, boot, 1_000)
            }
        }

        test("deletes archived, then outdated mp4 files, oldest first within outdated, until usable plus deleted bytes reach the target") {
            val statuses = mapOf("arch1" to Archived, "out1" to Outdated, "out2" to Outdated, "recd1" to Recorded)
            val reads = CopyOnWriteArrayList<Path>()
            val diskSpace =
                DiskSpace { path ->
                    reads.add(path)
                    2
                }
            cache(statuses, statuses.keys.toList(), diskSpace, minFreeBytes = 10, targetFreeBytes = 10) { service, repository, recordings ->
                now.set(now.get() + 1.days)
                repository.update("out2") { it.copy(status = Recorded) }
                repository.update("out2") { it.copy(status = Outdated) }
                service.evict()

                recordings.resolve("arch1.mp4").shouldNotExist()
                recordings.resolve("out1.mp4").shouldNotExist()
                recordings.resolve("out2.mp4").shouldExist()
                recordings.resolve("recd1.mp4").shouldExist()
                repository["out2"]?.status shouldBe Outdated
                repository["recd1"]?.status shouldBe Recorded
                reads shouldBe listOf(recordings)
                service.usage?.usableBytes shouldBe 11
            }
        }

        test("keeps every mp4 that has not expired while usable space is at the minimum, however large the cache is") {
            val statuses = mapOf("expired" to Archived, "fresh" to Archived, "recd1" to Recorded)
            cache(
                statuses,
                statuses.keys.toList(),
                DiskSpace { 10 },
                minFreeBytes = 10,
                targetFreeBytes = 1_000,
            ) { service, repository, recordings ->
                now.set(now.get() + 15.days)
                repository.update("fresh") { it.copy(recordedAt = Instant.parse("2026-10-11T08:00:00Z"), views = 1) }
                service.evict()

                recordings.resolve("expired.mp4").shouldNotExist()
                recordings.resolve("fresh.mp4").shouldExist()
                recordings.resolve("recd1.mp4").shouldExist()
                service.usage?.usableBytes shouldBe 10
            }
        }

        test("never trims a recorded, uploaded or recording mp4, and warns once when the target stays out of reach") {
            val statuses = mapOf("recd1" to Recorded, "upld1" to Uploaded, "recg1" to Recording)
            cache(statuses, statuses.keys.toList(), DiskSpace { 0 }, minFreeBytes = 10, targetFreeBytes = 20) { service, _, recordings ->
                val events = logged { service.evict() }

                recordings.resolve("recd1.mp4").shouldExist()
                recordings.resolve("upld1.mp4").shouldExist()
                recordings.resolve("recg1.mp4").shouldExist()
                events.count { it.level == Level.WARN } shouldBe 1
                service.usage?.usableBytes shouldBe 0
            }
        }

        test("deletes nothing for size, keeps the eviction loop running and reports no usable space when the read fails") {
            val statuses = mapOf("arch1" to Archived)
            val diskSpace = DiskSpace { throw IOException("unreadable") }
            cache(statuses, statuses.keys.toList(), diskSpace, minFreeBytes = 10, targetFreeBytes = 20) { service, _, recordings ->
                coroutineScope {
                    val job = launch { service.start() }
                    eventually(5.seconds) { service.usage shouldBe CacheUsage(5, 1, Instant.parse("2026-09-27T08:00:00Z"), null) }

                    job.isActive shouldBe true
                    job.cancelAndJoin()
                }

                recordings.resolve("arch1.mp4").shouldExist()
            }
        }

        test("keeps the status of a recording when it is viewed") {
            cache(mapOf("archived" to Archived), emptyList()) { service, repository, _ ->
                service.view("archived")

                repository["archived"]?.status shouldBe Archived
            }
        }

        test("counts the views of a recording in memory until they are flushed") {
            cache(mapOf("viewed" to Recorded), listOf("viewed")) { service, repository, _ ->
                service.view("viewed")
                now.set(now.get() + 1.days)
                service.view("viewed")
                service.view("viewed", counted = false)

                repository["viewed"]?.views shouldBe 0
                service.flushViews()
                repository["viewed"]?.views shouldBe 2
                repository["viewed"]?.lastView shouldBe Instant.parse("2026-09-28T08:00:00Z")
            }
        }

        test("flushes the counted views when stopped") {
            cache(mapOf("viewed" to Recorded), listOf("viewed")) { service, repository, _ ->
                coroutineScope {
                    val job = launch(start = UNDISPATCHED) { service.start() }
                    service.view("viewed")
                    job.cancelAndJoin()
                }

                repository["viewed"]?.views shouldBe 1
            }
        }

        test("returns null for the view of an unknown recording") {
            cache(emptyMap(), emptyList()) { service, _, _ ->
                service.view("unknown").shouldBeNull()
            }
        }

        test("downloads the missing mp4 of a viewed archived recording once, checked against its checksum") {
            cache(mapOf("evicted" to Archived), emptyList()) { service, _, recordings ->
                coroutineScope {
                    val job = launch { service.start() }
                    service.view("evicted")
                    service.view("evicted")
                    eventually(5.seconds) { recordings.resolve("evicted.mp4").readText() shouldBe "archived" }
                    job.cancelAndJoin()
                }

                requests.get() shouldBe 1
                path.get() shouldBe "/1234-3e92a055-ctf-bt-maverick/evicted.mp4"
            }
        }

        test("does not download the mp4 of a viewed archived recording without an item id") {
            cache(mapOf("evicted" to Archived), emptyList()) { service, repository, recordings ->
                repository.update("evicted") { it.copy(itemId = null) }
                coroutineScope {
                    val job = launch { service.start() }
                    service.view("evicted")
                    delay(100.milliseconds)
                    job.cancelAndJoin()
                }

                recordings.resolve("evicted.mp4").shouldNotExist()
                requests.get() shouldBe 0
                service.downloadUrl(repository["evicted"]!!).shouldBeNull()
            }
        }

        test("does not download the mp4 of a viewed recording that is not archived") {
            cache(mapOf("uploaded" to Uploaded), emptyList()) { service, _, recordings ->
                coroutineScope {
                    val job = launch { service.start() }
                    service.view("uploaded")
                    delay(100.milliseconds)
                    job.cancelAndJoin()
                }

                recordings.resolve("uploaded.mp4").shouldNotExist()
                requests.get() shouldBe 0
            }
        }

        test("keeps no mp4 of a download whose checksum does not match") {
            cache(mapOf("corrupt" to Archived), emptyList()) { service, repository, recordings ->
                repository.update("corrupt") { it.copy(md5 = "00000000000000000000000000000000") }
                coroutineScope {
                    val job = launch { service.start() }
                    service.view("corrupt")
                    eventually(5.seconds) { requests.get() shouldBe 1 }
                    delay(200.milliseconds)
                    job.cancelAndJoin()
                }

                recordings.resolve("corrupt.mp4").shouldNotExist()
            }
        }

        test("sizes every recording without a size whose mp4 is cached, and changes no status") {
            val statuses = mapOf("recorded" to Recorded, "archive" to Archived, "missing" to Archived)
            cache(statuses, listOf("recorded", "archive")) { service, repository, _ ->
                val before = repository.all().associate { it.demo.capId to it.lastUpdate }
                service.evict()

                repository.all().associate { it.demo.capId to it.size } shouldBe mapOf("recorded" to 8L, "archive" to 7L, "missing" to null)
                repository.all().associate { it.demo.capId to it.status } shouldBe
                    mapOf("recorded" to Recorded, "archive" to Archived, "missing" to Archived)
                repository.all().associate { it.demo.capId to it.lastUpdate } shouldBe before
            }
        }

        test("writes nothing to the store when no recording needs a size") {
            cache(mapOf("sized" to Archived, "missing" to Recorded), listOf("sized")) { service, repository, _ ->
                repository.update("sized") { it.copy(size = 999) }
                puts.set(0)
                service.evict()

                puts.get() shouldBe 0
                repository["sized"]?.size shouldBe 999
            }
        }

        test("keeps the size of a recording whose mp4 is evicted") {
            cache(mapOf("expired" to Archived), listOf("expired")) { service, repository, recordings ->
                now.set(now.get() + 15.days)
                service.evict()
                service.evict()

                recordings.resolve("expired.mp4").shouldNotExist()
                repository["expired"]?.size shouldBe 7
            }
        }

        test("reports no usage before the first pass, then the mp4 files left after the last pass") {
            cache(mapOf("kept" to Recorded, "expired" to Archived), listOf("kept", "expired")) { service, _, recordings ->
                recordings.resolve("kept.jpg").writeText("thumbnail")
                service.usage.shouldBeNull()
                now.set(now.get() + 15.days)
                service.evict()

                service.usage shouldBe CacheUsage(4, 1, Instant.parse("2026-10-12T08:00:00Z"), 1_000)
            }
        }
    })
