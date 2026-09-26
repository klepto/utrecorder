package dev.klepto.utrecorder.server.source

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class UtbtDemoSourceTest :
    FunSpec({
        val config =
            ServerConfig(
                ConfigReader.EMPTY,
                sourceUrl = "https://gateway.test",
                demoUrl = "http://demos.test/demos",
            )
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        test("requests a page of verified caps newest first at the offset") {
            val engine = MockEngine { respond("""{"data":[],"success":true}""", HttpStatusCode.OK, json) }

            UtbtDemoSource(config, engine).caps(200)

            engine.requestHistory
                .single()
                .url
                .toString() shouldBe
                "https://gateway.test/caps?limit=100&offset=200&order=desc&verified=true"
        }

        test("parses the caps of a page and ignores every other field") {
            val body =
                """
                {"data":[
                  {"id":"20e19790-6561-4929-9faa-50fbe1194f1e","map":"CTF-BT-Maverick","alias":"fierd_2",
                   "cap_time_seconds":37.768,"added":"2026-09-26 12:19:59.891708","has_ghost":true,
                   "active_title":{"name":"Grand Champion"},"fps_1pc":344},
                  {"id":"7f0e2a52","map":"CTF-BT-II-CoZyliciouS","alias":"klepto",
                   "cap_time_seconds":83,"added":"2026-09-25 08:00:00","verified_at":"2026-09-25 09:00:00"}
                ],"success":true}
                """.trimIndent()
            val engine = MockEngine { respond(body, HttpStatusCode.OK, json) }

            UtbtDemoSource(config, engine).caps(0) shouldBe
                listOf(
                    Cap(
                        id = "20e19790-6561-4929-9faa-50fbe1194f1e",
                        map = "CTF-BT-Maverick",
                        alias = "fierd_2",
                        capTime = 37768.milliseconds,
                        added = Instant.parse("2026-09-26T12:19:59.891708Z"),
                    ),
                    Cap(
                        id = "7f0e2a52",
                        map = "CTF-BT-II-CoZyliciouS",
                        alias = "klepto",
                        capTime = 83000.milliseconds,
                        added = Instant.parse("2026-09-25T08:00:00Z"),
                    ),
                )
        }

        test("fails a page the source answers with an error status") {
            val engine = MockEngine { respond("", HttpStatusCode.BadGateway) }

            shouldThrow<IOException> { UtbtDemoSource(config, engine).caps(0) }
        }

        test("downloads a demo into memory") {
            val engine = MockEngine { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }

            UtbtDemoSource(config, engine).demo("7f0e2a52")?.toList() shouldBe listOf<Byte>(1, 2, 3)
            engine.requestHistory
                .single()
                .url
                .toString() shouldBe "http://demos.test/demos/7f0e2a52"
        }

        test("answers null for a demo the source does not serve") {
            val engine = MockEngine { respond("", HttpStatusCode.NotFound) }

            UtbtDemoSource(config, engine).demo("7f0e2a52").shouldBeNull()
        }

        test("fails a demo download the source answers with a server error") {
            val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }

            shouldThrow<IOException> { UtbtDemoSource(config, engine).demo("7f0e2a52") }
        }
    })
