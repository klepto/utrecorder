package dev.klepto.utrecorder.server.cache

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
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
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
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
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val content = "archived".toByteArray()
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.start()
        afterSpec { server.stop(0) }

        beforeTest {
            now.set(Instant.parse("2026-09-27T08:00:00Z"))
            requests.set(0)
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

                override fun downloadUrl(
                    id: String,
                    name: String,
                ): String = "http://${server.address.hostString}:${server.address.port}/$id/$name"
            }

        suspend fun cache(
            statuses: Map<String, RecordingStatus>,
            mp4s: List<String>,
            maxBytes: Long = 107_374_182_400,
            targetBytes: Long = 53_687_091_200,
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
                    cacheMaxBytes = maxBytes,
                    cacheTargetBytes = targetBytes,
                )
            SqliteDocumentStore.open(directory.resolve("server.db")) { store ->
                val repository = RecordingRepository(store, DemoRepository(store), config, clock)
                repository += statuses.keys.map { demo(it) }
                // MD5 of "archived".
                statuses.forEach { (capId, status) ->
                    repository.update(capId) { it.copy(status = status, md5 = "0d111f56964985f562aaaaf9d2980787") }
                }
                block(CacheService(repository, uploader, config, clock), repository, recordings)
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

        test("keeps the mp4 of an archived recording never viewed until the cache TTL passes from its archiving") {
            cache(mapOf("fresh" to Archived), listOf("fresh")) { service, _, recordings ->
                now.set(now.get() + 13.days)
                service.evict()

                recordings.resolve("fresh.mp4").shouldExist()
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

        test("deletes archived, then outdated mp4 files, oldest first within outdated, down to the target size") {
            val statuses = mapOf("arch1" to Archived, "out1" to Outdated, "out2" to Outdated, "recd1" to Recorded)
            cache(statuses, statuses.keys.toList(), maxBytes = 16, targetBytes = 9) { service, repository, recordings ->
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
            }
        }

        test("keeps every mp4 while the mp4 files take at most the maximum size") {
            val statuses = mapOf("arch1" to Archived, "recd1" to Recorded)
            cache(statuses, statuses.keys.toList(), maxBytes = 10, targetBytes = 0) { service, _, recordings ->
                service.evict()

                recordings.resolve("arch1.mp4").shouldExist()
                recordings.resolve("recd1.mp4").shouldExist()
            }
        }

        test("never trims a recorded, uploaded or recording mp4, even above the target size") {
            val statuses = mapOf("recd1" to Recorded, "upld1" to Uploaded, "recg1" to Recording)
            cache(statuses, statuses.keys.toList(), maxBytes = 1, targetBytes = 0) { service, _, recordings ->
                service.evict()

                recordings.resolve("recd1.mp4").shouldExist()
                recordings.resolve("upld1.mp4").shouldExist()
                recordings.resolve("recg1.mp4").shouldExist()
            }
        }

        test("keeps the status of a recording when it is viewed") {
            cache(mapOf("archived" to Archived), emptyList()) { service, repository, _ ->
                service.view("archived")

                repository["archived"]?.status shouldBe Archived
            }
        }

        test("counts a view of a recording") {
            cache(mapOf("viewed" to Recorded), listOf("viewed")) { service, repository, _ ->
                service.view("viewed")
                service.view("viewed")

                repository["viewed"]?.views shouldBe 2
                repository["viewed"]?.lastView shouldBe Instant.parse("2026-09-27T08:00:00Z")
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
    })
