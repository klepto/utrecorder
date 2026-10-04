package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WebEndpointTest :
    FunSpec({
        val root = tempdir().toPath()

        suspend fun http(block: suspend ApplicationTestBuilder.() -> Unit) {
            val config = ServerConfig(ConfigReader.EMPTY, directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home")))
            testApplication {
                application { HttpService(listOf(WebEndpoint()), config).module(this) }
                block()
            }
        }

        test("answers index.html without caching for every path the site routes") {
            http {
                listOf("/", "/recordings", "/about", "/recordings/unknown").forEach { path ->
                    val response = client.get(path)

                    response.status shouldBe HttpStatusCode.OK
                    response.contentType()?.withoutParameters() shouldBe ContentType.Text.Html
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                    response.bodyAsText() shouldContain "<div id=\"root\">"
                }
            }
        }

        test("answers an asset with an immutable cache header") {
            http {
                val asset = Regex("/assets/[^\"]+\\.js").find(client.get("/").bodyAsText())!!.value
                val response = client.get(asset)

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
            }
        }

        test("does not serve the index of the embedded artifacts") {
            http {
                client.get("/index").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("answers a JSON 404 for an unknown path under /api/") {
            http {
                val response = client.get("/api/unknown")

                response.status shouldBe HttpStatusCode.NotFound
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content shouldBe "Not found"
            }
        }
    })
