package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.ConfigReader
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class UtbtDemoFetcherTest :
    FunSpec({
        val config = UtbtConfig(ConfigReader.EMPTY, cooldown = 200.milliseconds)

        fun id(minute: Int): String = "00000000-0000-4000-8000-0000000000%02d".format(minute)

        fun cap(minute: Int): String =
            """{"id":"${id(minute)}","map":"CTF-BT-A","alias":"a","cap_time_seconds":1,"added":"2026-09-26 12:%02d:00"}"""
                .format(minute)

        fun engine(
            minutes: List<Int>,
            requests: MutableList<String> = CopyOnWriteArrayList(),
            reads: MutableList<TimeSource.Monotonic.ValueTimeMark> = CopyOnWriteArrayList(),
            inclusive: Boolean = false,
            failFirst: Boolean = false,
        ): MockEngine =
            MockEngine { request ->
                requests += request.url.toString()
                reads += TimeSource.Monotonic.markNow()
                val before = request.url.parameters["before"]?.toDouble() ?: Double.MAX_VALUE
                val after = request.url.parameters["after"]?.toDouble() ?: 0.0
                val caps =
                    minutes
                        .filter {
                            val epoch = 1790424000.0 + it * 60
                            if (inclusive) epoch in after..before else epoch > after && epoch < before
                        }.sortedDescending()
                val failed = failFirst && requests.size == 1
                respond(
                    if (failed) "" else caps.joinToString(",", """{"data":[""", "]}") { cap(it) },
                    if (failed) HttpStatusCode.BadGateway else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        suspend fun collecting(
            fetcher: UtbtDemoFetcher,
            emitted: MutableList<String> = CopyOnWriteArrayList(),
            block: suspend () -> Unit,
        ) {
            coroutineScope {
                val job = launch { fetcher.fetch().collect { emitted += it.capId } }
                block()
                job.cancelAndJoin()
            }
        }

        test("requests one page of verified caps, newest first") {
            val requests = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine(listOf(0), requests))

            fetcher.fetch().first()

            requests shouldContainExactly listOf("https://gateway.utbt.net/caps?limit=100&order=desc&verified=true")
        }

        test("maps every field of a cap") {
            val body =
                """
                {"data":[{
                    "id":"0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d",
                    "map":"CTF-BT-Maverick",
                    "alias":"klepto",
                    "cap_time_seconds":12.34,
                    "added":"2026-09-26 12:19:59.891708",
                    "verified":true
                }]}
                """.trimIndent()
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val fetcher = UtbtDemoFetcher(config, engine)

            fetcher.fetch().first() shouldBe
                Demo(
                    capId = "0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d",
                    map = "CTF-BT-Maverick",
                    player = "klepto",
                    capTime = 12340.milliseconds,
                    timestamp = Instant.parse("2026-09-26T12:19:59.891708Z"),
                    demoUrl = "http://api.utbt.net/demos/0b6f4a9e-3c1d-4f7a-9e2b-5d8c1a2b3c4d",
                )
        }

        test("skips a cap whose id is not a UUID") {
            val invalid = """{"id":"../etc","map":"CTF-BT-A","alias":"a","cap_time_seconds":1,"added":"2026-09-26 12:01:00"}"""
            val body = """{"data":[$invalid,${cap(0)}]}"""
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val fetcher = UtbtDemoFetcher(config, engine)

            fetcher.fetch().first().capId shouldBe id(0)
        }

        test("emits the caps added after the newest demo seen ahead of the backlog") {
            val minutes = CopyOnWriteArrayList(listOf(3, 2, 1))
            val requests = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine(minutes, requests))

            val emitted =
                fetcher
                    .fetch()
                    .onEach {
                        if (it.capId != id(3)) return@onEach
                        minutes += 4
                        delay(300.milliseconds)
                    }.take(4)
                    .toList()

            emitted.map { it.capId } shouldContainExactly listOf(id(3), id(4), id(2), id(1))
            requests shouldContainExactly
                listOf(
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true",
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true&after=1790424180.000000",
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true&before=1790424240.000000&after=1790424180.000000",
                )
        }

        test("ends the backlog at an empty page, then reads only the caps added after the newest, once per cooldown") {
            val requests = CopyOnWriteArrayList<String>()
            val reads = CopyOnWriteArrayList<TimeSource.Monotonic.ValueTimeMark>()
            val fetcher = UtbtDemoFetcher(config, engine(listOf(0), requests, reads))

            collecting(fetcher) { eventually(5.seconds) { requests.size shouldBeGreaterThanOrEqual 4 } }

            requests.take(4) shouldContainExactly
                listOf(
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true",
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true&before=1790424000.000000",
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true&after=1790424000.000000",
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true&after=1790424000.000000",
                )
            reads[3] - reads[2] shouldBeGreaterThanOrEqualTo 200.milliseconds
        }

        test("reads the newest caps when the backlog ends before any demo is read") {
            val minutes = CopyOnWriteArrayList<Int>()
            val requests = CopyOnWriteArrayList<String>()
            val emitted = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine(minutes, requests))

            collecting(fetcher, emitted) {
                eventually(5.seconds) { requests.size shouldBeGreaterThanOrEqual 1 }
                minutes += 0
                eventually(5.seconds) { emitted shouldContainExactly listOf(id(0)) }
            }
        }

        test("emits each demo once when a bound also matches the cap added at it") {
            val requests = CopyOnWriteArrayList<String>()
            val emitted = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine(listOf(1, 0), requests, inclusive = true))

            collecting(fetcher, emitted) { delay(700.milliseconds) }

            emitted shouldContainExactly listOf(id(1), id(0))
            requests.size shouldBeLessThan 10
        }

        test("reads the backlog again after the cooldown when a read fails") {
            val requests = CopyOnWriteArrayList<String>()
            val fetcher = UtbtDemoFetcher(config, engine(listOf(0), requests, failFirst = true))

            fetcher.fetch().first().capId shouldBe id(0)
            requests shouldContainExactly
                listOf(
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true",
                    "https://gateway.utbt.net/caps?limit=100&order=desc&verified=true",
                )
        }
    })
