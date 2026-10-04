package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import dev.klepto.utrecorder.server.upload.MediaMetadata
import dev.klepto.utrecorder.server.upload.MediaUploader
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class VideoEndpointTest :
    FunSpec({
        val root = tempdir().toPath()
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-27T08:00:00Z")
            }

        val cache = AtomicReference<CacheService>()

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
                ): String = "https://archive.org/download/ut99-bt-$id/$name"
            }

        suspend fun http(
            statuses: Map<String, RecordingStatus>,
            mp4s: List<String> = emptyList(),
            thumbnails: List<String> = emptyList(),
            archive: Boolean = true,
            trustForwardedHeaders: Boolean = false,
            block: suspend ApplicationTestBuilder.(RecordingRepository) -> Unit,
        ) {
            val directory = createTempDirectory(root, "http")
            val recordings = directory.resolve("recordings").createDirectories()
            mp4s.forEach { recordings.resolve("$it.mp4").writeText("0123456789") }
            val thumbnailDirectory = directory.resolve("thumbnails").createDirectories()
            thumbnails.forEach { thumbnailDirectory.resolve("$it.jpg").writeText("jpg") }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories = DirectoriesConfig(ConfigReader.EMPTY, home = directory.resolve("home")),
                    recordings = recordings,
                    thumbnails = thumbnailDirectory,
                    trustForwardedHeaders = trustForwardedHeaders,
                )
            SqliteDocumentStore.open(directory.resolve("server.db")) { store ->
                val repository = RecordingRepository(store, DemoRepository(store), clock)
                repository +=
                    statuses.keys.map {
                        Demo(
                            capId = it,
                            map = "CTF-BT-Maverick",
                            player = "klepto",
                            capTime = 10.seconds,
                            timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                            demoUrl = "http://api.utbt.net/demos/$it",
                        )
                    }
                statuses.forEach { (capId, status) -> repository.finish(capId, status) }
                cache.set(CacheService(repository, uploader.takeIf { archive }, config, clock))
                val endpoint = VideoEndpoint(repository, cache.get(), config)
                testApplication {
                    application { HttpService(listOf(endpoint), config).module(this) }
                    block(repository)
                }
            }
        }

        test("serves a local mp4 with range support, and counts a view only for a request from its start") {
            http(mapOf("local" to Recorded), listOf("local")) { repository ->
                val response = client.get("/mp4/local") { header(HttpHeaders.Range, "bytes=2-4") }
                client.get("/mp4/local")
                client.get("/mp4/local") { header(HttpHeaders.Range, "bytes=0-") }
                cache.get().flushViews()

                response.status shouldBe HttpStatusCode.PartialContent
                response.bodyAsText() shouldBe "234"
                repository["local"]?.views shouldBe 2
            }
        }

        test("answers HEAD of the mp4 and the view page with the headers of GET and no body, and counts no view for it") {
            http(mapOf("local" to Recorded), listOf("local")) { repository ->
                val mp4 = client.head("/mp4/local")
                val view = client.head("/view/local")
                cache.get().flushViews()

                mp4.status shouldBe HttpStatusCode.OK
                mp4.contentType() shouldBe ContentType.Video.MP4
                mp4.headers[HttpHeaders.ContentLength] shouldBe "10"
                mp4.bodyAsText() shouldBe ""
                view.status shouldBe HttpStatusCode.OK
                repository["local"]?.views shouldBe 0
            }
        }

        test("serves a local mp4 without the Archive keys") {
            http(mapOf("local" to Uploaded), listOf("local"), archive = false) {
                client.get("/mp4/local").bodyAsText() shouldBe "0123456789"
            }
        }

        test("redirects an archived recording without a local mp4 to its file in its item, and counts a view") {
            http(mapOf("evicted" to Archived)) { repository ->
                repository.update("evicted") { it.copy(itemId = "1234-3e92a055-ctf-bt-maverick") }
                val response = createClient { followRedirects = false }.get("/mp4/evicted")
                cache.get().flushViews()

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe
                    "https://archive.org/download/ut99-bt-1234-3e92a055-ctf-bt-maverick/evicted.mp4"
                repository["evicted"]?.views shouldBe 1
            }
        }

        test("answers 404 for the mp4 and the view page of an archived recording without an item id") {
            http(mapOf("evicted" to Archived)) {
                client.get("/mp4/evicted").status shouldBe HttpStatusCode.NotFound
                client.get("/view/evicted").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers 404 for an archived recording without a local mp4 when nothing is archived") {
            http(mapOf("evicted" to Archived), archive = false) {
                client.get("/mp4/evicted").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers 404 for an unknown, pending or failed recording") {
            http(mapOf("pending" to Pending, "failed" to Failed), listOf("failed")) {
                client.get("/mp4/unknown").status shouldBe HttpStatusCode.NotFound
                client.get("/mp4/pending").status shouldBe HttpStatusCode.NotFound
                client.get("/mp4/failed").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers 404 for a recorded recording whose mp4 is missing") {
            http(mapOf("missing" to Recorded)) {
                client.get("/mp4/missing").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("serves an outdated recording from disk while its mp4 exists") {
            http(mapOf("outdated" to Outdated), listOf("outdated")) {
                client.get("/mp4/outdated").bodyAsText() shouldBe "0123456789"
            }
        }

        test("answers 404 without redirecting for an outdated recording whose mp4 is gone") {
            http(mapOf("outdated" to Outdated)) {
                val response = createClient { followRedirects = false }.get("/mp4/outdated")

                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers a view page with OpenGraph video tags at the forwarded origin, and counts no view") {
            http(mapOf("local" to Recorded), listOf("local"), trustForwardedHeaders = true) { repository ->
                val response =
                    client.get("/view/local") {
                        header("X-Forwarded-Proto", "https")
                        header("X-Forwarded-Host", "recorder.example.com")
                    }
                val body = response.bodyAsText()
                cache.get().flushViews()

                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Text.Html
                body shouldContain "<meta property=\"og:site_name\" content=\"CTF-BT-Maverick - 10.000 by klepto\" />"
                body shouldNotContain "<title>"
                body shouldNotContain "og:title"
                body shouldContain "<meta property=\"og:url\" content=\"https://recorder.example.com/view/local\" />"
                body shouldContain "<meta property=\"og:video\" content=\"https://recorder.example.com/mp4/local\" />"
                body shouldContain "<meta property=\"og:video:type\" content=\"video/mp4\" />"
                body shouldContain "<meta property=\"og:video:width\" content=\"1920\" />"
                body shouldContain "<meta property=\"og:video:height\" content=\"1080\" />"
                body shouldContain "<meta property=\"og:image\" content=\"https://recorder.example.com/thumbnail/local\" />"
                body shouldContain "<meta property=\"og:image:type\" content=\"image/jpeg\" />"
                body shouldContain "<meta property=\"og:image:width\" content=\"1920\" />"
                body shouldContain "<meta property=\"og:image:height\" content=\"1080\" />"
                body shouldContain "<video src=\"https://recorder.example.com/mp4/local\""
                repository["local"]?.views shouldBe 0
            }
        }

        test("ignores the forwarded headers unless they are trusted") {
            http(mapOf("local" to Recorded), listOf("local")) {
                val body =
                    client
                        .get("/view/local") {
                            header("X-Forwarded-Proto", "https")
                            header("X-Forwarded-Host", "attacker.example.com")
                        }.bodyAsText()

                body shouldContain "<meta property=\"og:url\" content=\"http://localhost/view/local\" />"
                body shouldContain "<meta property=\"og:video\" content=\"http://localhost/mp4/local\" />"
            }
        }

        test("serves the thumbnail of a served recording as a JPEG, also after its mp4 is evicted, and counts no view") {
            http(mapOf("local" to Recorded, "evicted" to Archived), listOf("local"), listOf("local", "evicted")) { repository ->
                val response = client.get("/thumbnail/local")
                cache.get().flushViews()

                response.status shouldBe HttpStatusCode.OK
                response.contentType() shouldBe ContentType.Image.JPEG
                response.bodyAsText() shouldBe "jpg"
                client.get("/thumbnail/evicted").status shouldBe HttpStatusCode.OK
                repository["local"]?.views shouldBe 0
            }
        }

        test("answers 404 for the thumbnail of an unknown, unserved or unthumbnailed recording") {
            http(mapOf("pending" to Pending, "missing" to Recorded), thumbnails = listOf("pending")) {
                client.get("/thumbnail/unknown").status shouldBe HttpStatusCode.NotFound
                client.get("/thumbnail/pending").status shouldBe HttpStatusCode.NotFound
                client.get("/thumbnail/missing").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("redirects the old thumbnail path permanently to the new one for any cap id, and counts no view") {
            http(mapOf("local" to Recorded), listOf("local"), listOf("local")) { repository ->
                val client = createClient { followRedirects = false }
                val served = client.get("/thumb/local")
                val unknown = client.get("/thumb/unknown")
                cache.get().flushViews()

                served.status shouldBe HttpStatusCode.MovedPermanently
                served.headers[HttpHeaders.Location] shouldBe "http://localhost/thumbnail/local"
                unknown.status shouldBe HttpStatusCode.MovedPermanently
                unknown.headers[HttpHeaders.Location] shouldBe "http://localhost/thumbnail/unknown"
                repository["local"]?.views shouldBe 0
            }
        }

        test("answers HEAD of both thumbnail routes with the headers of GET and no body") {
            http(mapOf("local" to Recorded), listOf("local"), listOf("local")) {
                val client = createClient { followRedirects = false }
                val thumbnail = client.head("/thumbnail/local")
                val old = client.head("/thumb/local")

                thumbnail.status shouldBe HttpStatusCode.OK
                thumbnail.contentType() shouldBe ContentType.Image.JPEG
                thumbnail.bodyAsText() shouldBe ""
                old.status shouldBe HttpStatusCode.MovedPermanently
                old.headers[HttpHeaders.Location] shouldBe "http://localhost/thumbnail/local"
            }
        }

        test("answers a view page for an archived recording without a local mp4") {
            http(mapOf("evicted" to Archived)) { repository ->
                repository.update("evicted") { it.copy(itemId = "1234-3e92a055-ctf-bt-maverick") }
                client.get("/view/evicted").status shouldBe HttpStatusCode.OK
            }
        }

        test("answers 404 for the view page wherever the mp4 answers 404") {
            http(mapOf("pending" to Pending, "missing" to Recorded, "evicted" to Archived), archive = false) {
                client.get("/view/unknown").status shouldBe HttpStatusCode.NotFound
                client.get("/view/pending").status shouldBe HttpStatusCode.NotFound
                client.get("/view/missing").status shouldBe HttpStatusCode.NotFound
                client.get("/view/evicted").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
