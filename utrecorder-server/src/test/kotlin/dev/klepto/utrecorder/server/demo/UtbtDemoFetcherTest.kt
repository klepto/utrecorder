package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class UtbtDemoFetcherTest :
    FunSpec({
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = tempdir().toPath())
        val config = ServerConfig(ConfigReader.EMPTY, directories)

        fun id(minute: Int): String = "00000000-0000-4000-8000-0000000000%02d".format(minute)

        fun cap(minute: Int): String =
            """{"id":"${id(minute)}","map":"CTF-BT-A","alias":"a","cap_time_seconds":1,"added":"2026-09-26 12:%02d:00"}"""
                .format(minute)

        fun engine(
            body: String,
            requests: MutableList<String> = CopyOnWriteArrayList(),
            status: HttpStatusCode = HttpStatusCode.OK,
        ): MockEngine =
            MockEngine { request ->
                requests += request.url.toString()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }

        test("requests one page of verified caps, newest first") {
            val requests = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine("""{"data":[${cap(0)}]}""", requests))

            fetcher.fetch().map { it.capId } shouldContainExactly listOf(id(0))
            requests shouldContainExactly listOf("https://gateway.utbt.net/caps?limit=100&order=desc&verified=true")
        }

        test("bounds the page by the times given, in epoch seconds with microseconds") {
            val requests = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine("{}", requests))

            fetcher.fetch(Instant.parse("2026-09-26T12:01:00.000042Z"), Instant.parse("2026-09-26T12:00:00Z")).shouldBeEmpty()
            requests shouldContainExactly
                listOf(
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true" +
                        "&before=1790424060.000042&after=1790424000.000000",
                )
        }

        test("maps every field of a cap") {
            val body =
                """
                {"data":[{
                    "id":"0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d",
                    "map":"CTF-BT-Maverick",
                    "alias":"klepto",
                    "user":"424992398657978370",
                    "cap_time_seconds":12.34,
                    "added":"2026-09-26 12:19:59.891708",
                    "verified":true
                }]}
                """.trimIndent()
            val fetcher = UtbtDemoFetcher(config, engine(body))

            fetcher.fetch().single() shouldBe
                Demo(
                    capId = "0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d",
                    map = "CTF-BT-Maverick",
                    player = "klepto",
                    userId = "424992398657978370",
                    capTime = 12340.milliseconds,
                    timestamp = Instant.parse("2026-09-26T12:19:59.891708Z"),
                    demoUrl = "http://api.utbt.net/demos/0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d",
                )
        }

        test("skips a cap whose id is not a UUID") {
            val invalid = """{"id":"../etc","map":"CTF-BT-A","alias":"a","cap_time_seconds":1,"added":"2026-09-26 12:01:00"}"""
            val fetcher = UtbtDemoFetcher(config, engine("""{"data":[$invalid,${cap(0)}]}"""))

            fetcher.fetch().map { it.capId } shouldContainExactly listOf(id(0))
        }

        test("throws when UTBT answers with a failure") {
            val fetcher = UtbtDemoFetcher(config, engine("", status = HttpStatusCode.BadGateway))

            shouldThrow<IOException> { fetcher.fetch() }
        }
    })
