package dev.klepto.utrecorder.server.archive

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.server.ArchiveConfig
import dev.klepto.utrecorder.server.Demo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class InternetArchiveTest :
    FunSpec({
        val demo =
            Demo(
                id = 1,
                sourceId = "7f0e2a52",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83.seconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
                recordingId = 1,
            )
        val config =
            ArchiveConfig(
                ConfigReader.EMPTY,
                accessKey = "access",
                secretKey = "secret",
                collection = "test_collection",
                prefix = "utrecorder",
            )

        test("builds the identifier from the prefix and the source id") {
            val archive = InternetArchive(config, MockEngine { respond("") })
            archive.identifier(demo.sourceId) shouldBe "utrecorder-7f0e2a52"
        }

        test("builds the download url") {
            val archive = InternetArchive(config, MockEngine { respond("") })
            archive.downloadUrl("utrecorder-7f0e2a52", "demo.mp4") shouldBe
                "https://archive.org/download/utrecorder-7f0e2a52/demo.mp4"
        }

        test("uploads with the S3-like PUT and every archive header") {
            val file = createTempFile("archive-upload", ".mp4")
            try {
                file.writeText("mp4")
                val engine = MockEngine { respond("") }
                val archive = InternetArchive(config, engine)

                archive.upload("utrecorder-7f0e2a52", file, demo)

                val request = engine.requestHistory.single()
                request.method shouldBe HttpMethod.Put
                request.url.toString() shouldBe "https://s3.us.archive.org/utrecorder-7f0e2a52/${file.fileName}"
                request.headers[HttpHeaders.Authorization] shouldBe "LOW access:secret"
                request.headers["x-archive-auto-make-bucket"] shouldBe "1"
                request.headers["x-archive-meta-mediatype"] shouldBe "movies"
                request.headers["x-archive-meta01-collection"] shouldBe "test_collection"
                request.headers["x-archive-meta-title"] shouldBe "CTF-BT-Maverick — klepto"
                request.headers["x-archive-meta-description"] shouldBe
                    "Map: CTF-BT-Maverick. Player: klepto. Cap time: ${demo.capTime}."
                request.headers["x-archive-queue-derive"] shouldBe "0"
            } finally {
                file.deleteIfExists()
            }
        }

        test("answers true when the metadata lists the file with the matching md5") {
            val engine =
                MockEngine {
                    respond(
                        """{"files":[{"name":"demo.mp4","md5":"d41d8cd98f00b204e9800998ecf8427e"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val archive = InternetArchive(config, engine)

            archive.isArchived("utrecorder-7f0e2a52", "demo.mp4", "d41d8cd98f00b204e9800998ecf8427e") shouldBe true
        }

        test("answers false when the metadata does not list a matching file") {
            val engine =
                MockEngine {
                    respond(
                        """{"files":[{"name":"demo.mp4","md5":"other"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val archive = InternetArchive(config, engine)

            archive.isArchived("utrecorder-7f0e2a52", "demo.mp4", "d41d8cd98f00b204e9800998ecf8427e") shouldBe false
        }

        test("answers false when the item does not exist yet") {
            val engine =
                MockEngine {
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val archive = InternetArchive(config, engine)

            archive.isArchived("utrecorder-7f0e2a52", "demo.mp4", "d41d8cd98f00b204e9800998ecf8427e") shouldBe false
        }
    })
