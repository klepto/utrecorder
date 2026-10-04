package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class HttpServiceTest :
    FunSpec({
        val root = tempdir().toPath()

        suspend fun http(block: suspend ApplicationTestBuilder.() -> Unit) {
            val directory = createTempDirectory(root, "http")
            val mp4 = directory.resolve("video.mp4")
            mp4.writeText("0123456789")
            val text = "0123456789".repeat(100)
            val endpoint =
                object : Endpoint {
                    override fun install(route: Route) {
                        route.get("/html") { call.respondText("<p>$text</p>", ContentType.Text.Html) }
                        route.get("/json") { call.respond(listOf(text)) }
                        route.get("/mp4") { call.respondFile(mp4.toFile()) }
                    }
                }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories = DirectoriesConfig(ConfigReader.EMPTY, home = directory.resolve("home")),
                )
            testApplication {
                application { HttpService(listOf(endpoint), config).module(this) }
                block()
            }
        }

        test("answers HEAD with the headers of GET and no body") {
            http {
                val response = client.head("/mp4")

                response.status shouldBe HttpStatusCode.OK
                response.contentType() shouldBe ContentType.Video.MP4
                response.headers[HttpHeaders.ContentLength] shouldBe "10"
                response.bodyAsText() shouldBe ""
            }
        }

        test("compresses HTML and JSON, but not an mp4") {
            http {
                client.get("/html") { header(HttpHeaders.AcceptEncoding, "gzip") }.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
                client.get("/json") { header(HttpHeaders.AcceptEncoding, "gzip") }.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
                client.get("/mp4") { header(HttpHeaders.AcceptEncoding, "gzip") }.headers[HttpHeaders.ContentEncoding] shouldBe null
            }
        }
    })
