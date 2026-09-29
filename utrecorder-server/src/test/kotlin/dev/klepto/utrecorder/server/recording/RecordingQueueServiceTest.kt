package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoFetcher
import dev.klepto.utrecorder.server.demo.UtbtConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordingQueueServiceTest :
    FunSpec({
        val config = UtbtConfig(ConfigReader.EMPTY, cooldown = 100.milliseconds)
        val start = Instant.parse("2026-09-26T12:00:00Z")

        fun demo(minute: Int): Demo =
            Demo(
                capId = "cap-$minute",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = start + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/cap-$minute",
            )

        /**
         * A fetcher over the demos of [minutes], two per page, that records each request as `before..after` in
         * minutes, with `_` for a missing bound. A minute in [failing] fails the page that would hold it.
         */
        fun fetcher(
            minutes: List<Int>,
            requests: MutableList<String>,
            failing: MutableSet<Int> = mutableSetOf(),
        ): DemoFetcher =
            object : DemoFetcher {
                override suspend fun fetch(
                    before: Instant?,
                    after: Instant?,
                ): List<Demo> {
                    requests += "${before?.minuteOf() ?: "_"}..${after?.minuteOf() ?: "_"}"
                    val page =
                        minutes
                            .filter { (before == null || demo(it).timestamp < before) && (after == null || demo(it).timestamp > after) }
                            .sortedDescending()
                            .take(2)
                    if (page.any { it in failing }) throw IOException("UTBT is down")
                    return page.map { demo(it) }
                }

                private fun Instant.minuteOf(): Long = (this - start).inWholeMinutes
            }

        suspend fun syncing(
            fetcher: DemoFetcher,
            block: suspend (RecordingQueue) -> Unit,
        ) {
            DocumentStore.open(createTempDirectory("utrecorder-recording-queue-service-test").resolve("server.db")) { store ->
                val queue = RecordingQueue(store)
                coroutineScope {
                    val job = launch { RecordingQueueService(fetcher, queue, config).start() }
                    block(queue)
                    job.cancelAndJoin()
                }
            }
        }

        suspend fun RecordingQueue.capIds(minutes: IntRange): List<String> = minutes.filter { "cap-$it" in this }.map { "cap-$it" }

        test("stores the newest page, then walks the backlog one page per cooldown until a page holds nothing new") {
            val requests = CopyOnWriteArrayList<String>()

            syncing(fetcher(listOf(0, 1, 2, 3, 4), requests)) { queue ->
                eventually(5.seconds) { queue.capIds(0..4) shouldContainExactly listOf("cap-0", "cap-1", "cap-2", "cap-3", "cap-4") }
                delay(300.milliseconds)
            }

            requests.take(6) shouldContainExactly listOf("_.._", "3.._", "_..4", "1.._", "_..4", "0.._")
            requests.drop(6).toSet() shouldBe setOf("_..4")
        }

        test("stores every demo added after the newest, across pages") {
            val minutes = CopyOnWriteArrayList(listOf(0))
            val requests = CopyOnWriteArrayList<String>()

            syncing(fetcher(minutes, requests)) { queue ->
                eventually(5.seconds) { queue.capIds(0..0) shouldContainExactly listOf("cap-0") }
                minutes += listOf(1, 2, 3)
                eventually(5.seconds) { queue.capIds(0..3) shouldContainExactly listOf("cap-0", "cap-1", "cap-2", "cap-3") }
            }

            requests.windowed(3) shouldContain listOf("_..0", "2..0", "1..0")
        }

        test("stores nothing of a head walk whose later page fails, and reads it all again at the next cooldown") {
            val minutes = CopyOnWriteArrayList(listOf(0))
            val failing = mutableSetOf(1)
            val requests = CopyOnWriteArrayList<String>()

            syncing(fetcher(minutes, requests, failing)) { queue ->
                eventually(5.seconds) { queue.capIds(0..0) shouldContainExactly listOf("cap-0") }
                minutes += listOf(1, 2, 3)
                eventually(5.seconds) { requests.count { it == "2..0" } shouldBe 1 }
                delay(20.milliseconds)
                queue.capIds(0..3) shouldContainExactly listOf("cap-0")
                failing.clear()
                eventually(5.seconds) { queue.capIds(0..3) shouldContainExactly listOf("cap-0", "cap-1", "cap-2", "cap-3") }
            }
        }
    })
