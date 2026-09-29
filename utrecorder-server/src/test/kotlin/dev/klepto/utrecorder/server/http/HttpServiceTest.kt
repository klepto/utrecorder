package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import dev.klepto.utrecorder.server.upload.MediaMetadata
import dev.klepto.utrecorder.server.upload.MediaUploader
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HttpServiceTest :
    FunSpec({
        val root = tempdir().toPath()
        val now = AtomicReference(Instant.parse("2026-09-27T08:00:00Z"))
        val clock =
            object : Clock {
                override fun now(): Instant = now.get()
            }

        beforeTest { now.set(Instant.parse("2026-09-27T08:00:00Z")) }

        fun demo(
            capId: String,
            timestamp: Instant = Instant.parse("2026-09-26T12:00:00Z"),
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = timestamp,
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

                override fun downloadUrl(
                    id: String,
                    name: String,
                ): String = "https://archive.org/download/ut99-bt-$id/$name"
            }

        suspend fun http(
            statuses: Map<String, RecordingStatus>,
            mp4s: List<String> = emptyList(),
            archive: Boolean = true,
            jobs: RecordingJobs = RecordingJobs(),
            block: suspend ApplicationTestBuilder.(RecordingRepository) -> Unit,
        ) {
            val directory = createTempDirectory(root, "http")
            val recordings = directory.resolve("recordings").createDirectories()
            mp4s.forEach { recordings.resolve("$it.mp4").writeText("0123456789") }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories = DirectoriesConfig(ConfigReader.EMPTY, home = directory.resolve("home")),
                    recordings = recordings,
                )
            SqliteDocumentStore.open(directory.resolve("server.db")) { store ->
                val repository = RecordingRepository(store, config.recording, clock)
                repository += statuses.keys.map { demo(it) }
                statuses.forEach { (capId, status) -> repository.finish(capId, status) }
                val cache = if (archive) CacheService(repository, uploader, config, clock) else null
                val service = HttpService(repository, jobs, cache, config)
                testApplication {
                    application { service.module(this) }
                    block(repository)
                }
            }
        }

        test("answers the index with an empty page") {
            http(emptyMap()) {
                val response = client.get("/")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        test("serves a local mp4 with range support, and counts a view") {
            http(mapOf("local" to Recorded), listOf("local")) { repository ->
                val response = client.get("/demo/local") { header(HttpHeaders.Range, "bytes=2-4") }

                response.status shouldBe HttpStatusCode.PartialContent
                response.bodyAsText() shouldBe "234"
                repository["local"]?.views shouldBe 1
            }
        }

        test("serves a local mp4 without the Archive keys") {
            http(mapOf("local" to Uploaded), listOf("local"), archive = false) {
                client.get("/demo/local").bodyAsText() shouldBe "0123456789"
            }
        }

        test("redirects an archived recording without a local mp4 to its download URL, and counts a view") {
            http(mapOf("evicted" to Archived)) { repository ->
                val response = createClient { followRedirects = false }.get("/demo/evicted")

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "https://archive.org/download/ut99-bt-evicted/evicted.mp4"
                repository["evicted"]?.views shouldBe 1
            }
        }

        test("answers 404 for an archived recording without a local mp4 when nothing is archived") {
            http(mapOf("evicted" to Archived), archive = false) {
                client.get("/demo/evicted").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers 404 for an unknown, pending or failed recording") {
            http(mapOf("pending" to Pending, "failed" to Failed), listOf("failed")) {
                client.get("/demo/unknown").status shouldBe HttpStatusCode.NotFound
                client.get("/demo/pending").status shouldBe HttpStatusCode.NotFound
                client.get("/demo/failed").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers 404 for a recorded recording whose mp4 is missing") {
            http(mapOf("missing" to Recorded)) {
                client.get("/demo/missing").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("lists the running recordings with their attempt and progress") {
            val jobs = RecordingJobs()
            http(emptyMap(), jobs = jobs) {
                jobs.track(demo("running")) { tracker ->
                    tracker.startAttempt(2)
                    tracker.report(Progress(RecorderStage.FetchingDemo, 0.5))
                    val job =
                        Json
                            .parseToJsonElement(client.get("/api/jobs").bodyAsText())
                            .jsonArray
                            .single()
                            .jsonObject

                    job["demo"]!!.jsonObject["capId"]!!.jsonPrimitive.content shouldBe "running"
                    job["attempt"]!!.jsonPrimitive.content shouldBe "2"
                    job["stage"]!!.jsonPrimitive.content shouldBe "FetchingDemo"
                    job["progress"]!!.jsonPrimitive.content shouldBe "0.5"
                }
                client.get("/api/jobs").bodyAsText() shouldBe "[]"
            }
        }

        test("lists the next 10 pending demos, newest first") {
            http(emptyMap()) { repository ->
                repository += (1..12).map { demo("pending-$it", Instant.parse("2026-09-26T00:00:00Z") + it.hours) }
                repository += listOf(demo("recorded", Instant.parse("2026-09-27T00:00:00Z")))
                repository.finish("recorded", Recorded)
                val queue = Json.parseToJsonElement(client.get("/api/queue").bodyAsText()).jsonArray

                queue.map { it.jsonObject["capId"]!!.jsonPrimitive.content } shouldBe (12 downTo 3).map { "pending-$it" }
            }
        }

        test("lists the 10 most recently recorded completed recordings, newest first") {
            http(emptyMap()) { repository ->
                repository += (1..12).map { demo("recording-$it", Instant.parse("2026-09-26T00:00:00Z") - it.hours) }
                repository += listOf(demo("pending", Instant.parse("2026-09-27T00:00:00Z")))
                (1..12).forEach {
                    now.set(Instant.parse("2026-09-27T00:00:00Z") + it.hours)
                    repository.recorded("recording-$it", 1.hours)
                    if (it % 2 == 0) repository.finish("recording-$it", Archived)
                }
                val recordings = Json.parseToJsonElement(client.get("/api/recordings").bodyAsText()).jsonArray

                recordings.map {
                    it.jsonObject["demo"]!!
                        .jsonObject["capId"]!!
                        .jsonPrimitive.content
                } shouldBe
                    (12 downTo 3).map { "recording-$it" }
            }
        }
    })
