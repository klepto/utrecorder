package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.parametersOf
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

class PagingTest :
    FunSpec({
        val items = listOf("a", "b", "c", "d", "e", "f", "g")

        fun Paging.page(): List<String>? = page(items) { it }

        test("skips offset items from the start and keeps at most limit") {
            Paging(offset = 2, limit = 3).page() shouldBe listOf("c", "d", "e")
        }

        test("answers every item by default") {
            Paging().page() shouldBe listOf("a", "b", "c", "d", "e", "f", "g")
        }

        test("starts right after the after item") {
            Paging(limit = 2, after = "b").page() shouldBe listOf("c", "d")
            Paging(offset = 1, limit = 2, after = "b").page() shouldBe listOf("d", "e")
        }

        test("takes the page from the end nearest the before item") {
            Paging(limit = 2, before = "f").page() shouldBe listOf("d", "e")
            Paging(offset = 1, limit = 2, before = "f").page() shouldBe listOf("c", "d")
            Paging(before = "c").page() shouldBe listOf("a", "b")
        }

        test("bounds the window from both sides and takes the page from its start") {
            Paging(after = "b", before = "f").page() shouldBe listOf("c", "d", "e")
            Paging(offset = 1, limit = 1, after = "b", before = "f").page() shouldBe listOf("d")
        }

        test("answers an empty page when the after item does not precede the before item") {
            Paging(after = "e", before = "c").page() shouldBe emptyList()
            Paging(after = "c", before = "c").page() shouldBe emptyList()
            Paging(after = "c", before = "d").page() shouldBe emptyList()
        }

        test("answers an empty page for an offset past the window") {
            Paging(offset = 7).page() shouldBe emptyList()
            Paging(offset = 2, after = "e").page() shouldBe emptyList()
            Paging(offset = 2, before = "c").page() shouldBe emptyList()
        }

        test("answers an empty page for limit 0") {
            Paging(limit = 0).page() shouldBe emptyList()
            Paging(limit = 0, before = "c").page() shouldBe emptyList()
        }

        test("answers an empty page for an empty list") {
            Paging().page(emptyList<String>()) { it } shouldBe emptyList()
        }

        test("answers null for a cursor that names no item") {
            Paging(after = "x").page().shouldBeNull()
            Paging(before = "x").page().shouldBeNull()
            Paging(after = "").page().shouldBeNull()
            Paging(after = "b", before = "x").page().shouldBeNull()
            Paging(before = "a").page(emptyList<String>()) { it }.shouldBeNull()
        }

        test("reads offset 0, limit 100 and no cursors from no parameters") {
            val paging = Paging.of(Parameters.Empty)!!

            paging.offset shouldBe 0
            paging.limit shouldBe 100
            paging.before.shouldBeNull()
            paging.after.shouldBeNull()
        }

        test("reads every parameter as given") {
            val parameters =
                parametersOf(
                    "offset" to listOf("3"),
                    "limit" to listOf("7"),
                    "before" to listOf("x"),
                    "after" to listOf(""),
                )
            val paging = Paging.of(parameters)!!

            paging.offset shouldBe 3
            paging.limit shouldBe 7
            paging.before shouldBe "x"
            paging.after shouldBe ""
        }

        test("lowers a limit above 100 to 100") {
            Paging.of(parametersOf("limit", "101"))!!.limit shouldBe 100
            Paging.of(parametersOf("limit", "100"))!!.limit shouldBe 100
        }

        test("reads null for an offset or limit that is not a non-negative integer") {
            Paging.of(parametersOf("offset", "-1")).shouldBeNull()
            Paging.of(parametersOf("offset", "x")).shouldBeNull()
            Paging.of(parametersOf("limit", "")).shouldBeNull()
            Paging.of(parametersOf("limit", "1.5")).shouldBeNull()
        }

        test("reads the first value of a repeated parameter") {
            Paging.of(parametersOf("limit" to listOf("5", "7")))!!.limit shouldBe 5
        }

        val root = tempdir().toPath()

        suspend fun http(block: suspend ApplicationTestBuilder.() -> Unit) {
            val endpoint =
                object : Endpoint {
                    override fun install(route: Route) {
                        route.get("/items") { respondPage(items) { it } }
                    }
                }
            val config = ServerConfig(ConfigReader.EMPTY, directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home")))
            testApplication {
                application { HttpService(listOf(endpoint), config).module(this) }
                block()
            }
        }

        test("answers the page as a bare JSON array") {
            http {
                val response = client.get("/items?after=b&limit=2")

                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                response.bodyAsText() shouldBe """["c","d"]"""
            }
        }

        test("answers 400 for an offset or limit that is not a non-negative integer") {
            http {
                val response = client.get("/items?limit=-1")

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe """{"error":"Limit and offset must be non-negative integers"}"""
            }
        }

        test("answers 400 for a cursor that names no item") {
            http {
                val response = client.get("/items?before=x")

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe """{"error":"Before and after must name an item of the list"}"""
            }
        }
    })
