package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class ArchiveMediaUploaderTest :
    FunSpec({
        val config = ServerConfig(ConfigReader.EMPTY, archiveAccessKey = "access", archiveSecretKey = "secret")
        val id = "0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d"
        val metadata = MediaMetadata("A & B", "Line <1>\nLine 2", listOf("Speedrunning", "Bunny Track"))

        fun engine(
            body: String = "",
            status: HttpStatusCode = HttpStatusCode.OK,
        ): MockEngine =
            MockEngine {
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }

        suspend fun withMp4(block: suspend (Path) -> Unit) {
            val file = createTempFile("archive-upload", ".mp4")
            try {
                file.writeText("mp4")
                block(file)
            } finally {
                file.deleteIfExists()
            }
        }

        test("uploads with one PUT carrying the placement and metadata headers") {
            withMp4 { file ->
                val engine = engine()
                ArchiveMediaUploader(config, engine).upload(id, file, metadata)

                val request = engine.requestHistory.single()
                request.method shouldBe HttpMethod.Put
                request.url.toString() shouldBe "https://s3.us.archive.org/ut99-bt-$id/${file.fileName}"
                request.headers.names().filter { it.startsWith("x-archive-") } shouldContainExactlyInAnyOrder
                    listOf(
                        "x-archive-auto-make-bucket",
                        "x-archive-meta01-collection",
                        "x-archive-meta-mediatype",
                        "x-archive-meta-title",
                        "x-archive-meta-description",
                        "x-archive-meta01-subject",
                        "x-archive-meta02-subject",
                        "x-archive-queue-derive",
                    )
                request.headers[HttpHeaders.Authorization] shouldBe "LOW access:secret"
                request.headers["x-archive-auto-make-bucket"] shouldBe "1"
                request.headers["x-archive-meta01-collection"] shouldBe "test_collection"
                request.headers["x-archive-meta-mediatype"] shouldBe "movies"
                request.headers["x-archive-meta-title"] shouldBe "uri(A%20%26%20B)"
                request.headers["x-archive-meta-description"] shouldBe "uri(Line%20%26lt%3B1%26gt%3B%3Cbr%20%2F%3ELine%202)"
                request.headers["x-archive-meta01-subject"] shouldBe "uri(Speedrunning)"
                request.headers["x-archive-meta02-subject"] shouldBe "uri(Bunny%20Track)"
                request.headers["x-archive-queue-derive"] shouldBe "0"
            }
        }

        test("throws when the PUT fails") {
            withMp4 { file ->
                val uploader = ArchiveMediaUploader(config, engine(status = HttpStatusCode.ServiceUnavailable))

                shouldThrow<ServerResponseException> { uploader.upload(id, file, metadata) }
            }
        }

        test("finds a file with the matching md5 in the item metadata") {
            val body = """{"files":[{"name":"$id.mp4","md5":"d41d8cd98f00b204e9800998ecf8427e","size":"3"}]}"""
            val engine = engine(body)
            val uploader = ArchiveMediaUploader(config, engine)

            uploader.isUploaded(id, "d41d8cd98f00b204e9800998ecf8427e") shouldBe true
            val request = engine.requestHistory.single()
            request.url.toString() shouldBe "https://archive.org/metadata/ut99-bt-$id"
        }

        test("finds no file when every md5 differs") {
            val uploader = ArchiveMediaUploader(config, engine("""{"files":[{"name":"$id.mp4","md5":"other"}]}"""))

            uploader.isUploaded(id, "d41d8cd98f00b204e9800998ecf8427e") shouldBe false
        }

        test("finds no file in an item that does not exist") {
            val uploader = ArchiveMediaUploader(config, engine("{}"))

            uploader.isUploaded(id, "d41d8cd98f00b204e9800998ecf8427e") shouldBe false
        }

        test("builds the download URL") {
            ArchiveMediaUploader(config, engine()).downloadUrl(id, "$id.mp4") shouldBe
                "https://archive.org/download/ut99-bt-$id/$id.mp4"
        }

        test("rejects a configuration without keys") {
            shouldThrow<IllegalArgumentException> {
                ArchiveMediaUploader(ServerConfig(ConfigReader.EMPTY), engine())
            }
        }
    })
