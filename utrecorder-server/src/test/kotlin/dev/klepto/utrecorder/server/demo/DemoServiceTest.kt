package dev.klepto.utrecorder.server.demo

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class DemoServiceTest :
    FunSpec({
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = tempdir().toPath())
        val start = Instant.parse("2026-09-26T12:00:00Z")

        fun config(
            pageLimit: Int,
            tailStride: Int,
        ): ServerConfig =
            ServerConfig(
                ConfigReader.EMPTY,
                directories,
                utbtCooldown = 100.milliseconds,
                utbtPageLimit = pageLimit,
                utbtTailStride = tailStride,
                utbtTailInterval = 1.hours,
            )

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
         * A fetcher over the demos of [minutes], newest first, that records the offset of each request. A request at an
         * offset in [failing] fails.
         */
        fun fetcher(
            minutes: List<Int>,
            requests: MutableList<Int>,
            failing: MutableSet<Int> = CopyOnWriteArraySet(),
        ): DemoFetcher =
            object : DemoFetcher {
                override suspend fun fetch(
                    offset: Int,
                    limit: Int,
                ): List<Demo> {
                    requests += offset
                    if (offset in failing) throw IOException("UTBT is down")
                    return minutes
                        .sortedDescending()
                        .drop(offset)
                        .take(limit)
                        .map { demo(it) }
                }
            }

        /**
         * A fetcher whose page at an offset is [page] of the offset and of the count of reads of that offset so far,
         * that records the offset of each request.
         */
        fun pagedFetcher(
            requests: MutableList<Int>,
            page: (offset: Int, read: Int) -> List<Int>,
        ): DemoFetcher {
            val reads = ConcurrentHashMap<Int, AtomicInteger>()
            return object : DemoFetcher {
                override suspend fun fetch(
                    offset: Int,
                    limit: Int,
                ): List<Demo> {
                    requests += offset
                    val read = reads.getOrPut(offset) { AtomicInteger() }.incrementAndGet()
                    return page(offset, read).map { demo(it) }
                }
            }
        }

        suspend fun logged(block: suspend () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(DemoService::class.java.name) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            try {
                block()
                return appender.list.toList()
            } finally {
                logger.detachAppender(appender)
            }
        }

        suspend fun syncing(
            fetcher: DemoFetcher,
            serverConfig: ServerConfig,
            timeSource: TimeSource = TimeSource.Monotonic,
            stored: List<Int> = emptyList(),
            withdrawn: List<Int> = emptyList(),
            block: suspend (DemoRepository) -> Unit,
        ) {
            SqliteDocumentStore.open(createTempDirectory("utrecorder-demo-service-test").resolve("server.db")) { store ->
                val repository = DemoRepository(store)
                repository += stored.map { demo(it) }
                repository.withdrawAllExcept((stored - withdrawn.toSet()).map { "cap-$it" }.toSet())
                coroutineScope {
                    val job = launch { DemoService(fetcher, repository, serverConfig, timeSource).start() }
                    block(repository)
                    job.cancelAndJoin()
                }
            }
        }

        suspend fun DemoRepository.capIds(): List<String> = all().map { it.capId }

        test("reads the newest page at every cooldown and stores the demos it does not hold") {
            val minutes = CopyOnWriteArrayList(listOf(0, 1, 2))
            val requests = CopyOnWriteArrayList<Int>()

            syncing(fetcher(minutes, requests), config(pageLimit = 2, tailStride = 10)) { repository ->
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-2", "cap-1") }
                minutes += 3
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-2", "cap-1", "cap-3") }
            }

            requests.count { it == 0 } shouldBeGreaterThan 1
        }

        test("stores a demo listed late behind the newest within the newest page") {
            val minutes = CopyOnWriteArrayList(listOf(0, 2))
            val requests = CopyOnWriteArrayList<Int>()

            syncing(fetcher(minutes, requests), config(pageLimit = 3, tailStride = 10)) { repository ->
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-2", "cap-0") }
                minutes += 1
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-2", "cap-0", "cap-1") }
            }
        }

        test("walks the tail one page per cooldown from the stride on, and ends it after a short page") {
            val requests = CopyOnWriteArrayList<Int>()

            syncing(fetcher(listOf(0, 1, 2, 3, 4), requests), config(pageLimit = 2, tailStride = 1)) { repository ->
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-4", "cap-3", "cap-2", "cap-1", "cap-0") }
                delay(300.milliseconds)
            }

            requests.take(8) shouldContainExactly listOf(0, 1, 0, 2, 0, 3, 0, 4)
            requests.drop(8).toSet() shouldBe setOf(0)
        }

        test("walks the tail past a page that holds nothing new") {
            val requests = CopyOnWriteArrayList<Int>()

            syncing(
                fetcher(listOf(0, 1, 2, 3, 4, 5), requests),
                config(pageLimit = 2, tailStride = 2),
                stored = listOf(3, 2),
            ) { repository ->
                eventually(5.seconds) {
                    repository.capIds() shouldContainExactly
                        listOf("cap-3", "cap-2", "cap-5", "cap-4", "cap-1", "cap-0")
                }
                delay(300.milliseconds)
            }

            requests.filter { it != 0 } shouldContainExactly listOf(2, 4, 6)
        }

        test("reads a failed tail page again at the same offset and stores nothing of it, while the head stores its page") {
            val failing = CopyOnWriteArraySet(setOf(2))
            val requests = CopyOnWriteArrayList<Int>()

            syncing(fetcher(listOf(0, 1, 2, 3), requests, failing), config(pageLimit = 2, tailStride = 2)) { repository ->
                eventually(5.seconds) { requests.count { it == 2 } shouldBeGreaterThan 1 }
                repository.capIds() shouldContainExactly listOf("cap-3", "cap-2")
                failing.clear()
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-3", "cap-2", "cap-1", "cap-0") }
                delay(300.milliseconds)
            }

            requests.filter { it != 0 }.distinct() shouldContainExactly listOf(2, 4)
            requests.last { it != 0 } shouldBe 4
        }

        test("stores the tail while the head fails") {
            val requests = CopyOnWriteArrayList<Int>()

            syncing(
                fetcher(listOf(0, 1, 2, 3), requests, CopyOnWriteArraySet(setOf(0))),
                config(pageLimit = 2, tailStride = 2),
            ) { repository ->
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-1", "cap-0") }
            }
        }

        test("starts the tail scan again once the tail interval has passed since the previous one started") {
            val minutes = CopyOnWriteArrayList(listOf(1, 2, 3, 4))
            val requests = CopyOnWriteArrayList<Int>()
            val timeSource = TestTimeSource()

            syncing(fetcher(minutes, requests), config(pageLimit = 2, tailStride = 2), timeSource) { repository ->
                eventually(5.seconds) { requests.filter { it != 0 } shouldContainExactly listOf(2, 4) }
                minutes += 0
                delay(300.milliseconds)
                repository.capIds() shouldContainExactly listOf("cap-4", "cap-3", "cap-2", "cap-1")
                timeSource += 1.hours
                eventually(5.seconds) { repository.capIds() shouldContainExactly listOf("cap-4", "cap-3", "cap-2", "cap-1", "cap-0") }
                delay(300.milliseconds)
            }

            requests.filter { it != 0 } shouldContainExactly listOf(2, 4, 2, 4)
        }

        test("withdraws exactly the stored demos that a scan ending on an end page did not see") {
            val requests = CopyOnWriteArrayList<Int>()

            val events =
                logged {
                    syncing(
                        fetcher(listOf(0, 1, 2, 3, 4), requests),
                        config(pageLimit = 2, tailStride = 1),
                        stored = listOf(2, 7, 8),
                    ) { repository ->
                        eventually(5.seconds) { repository.withdrawn() shouldBe setOf("cap-7", "cap-8") }
                    }
                }

            requests.filter { it != 0 } shouldContainExactly listOf(1, 2, 3, 4)
            events.filter { it.level == Level.INFO }.map { it.formattedMessage } shouldContainExactly
                listOf("Tail scan withdrew 2 demos and restored 0")
        }

        test("does not withdraw a cap that only head pages read during the scan listed, the first one included") {
            val requests = CopyOnWriteArrayList<Int>()
            val fetcher =
                pagedFetcher(requests) { offset, read ->
                    when {
                        offset == 0 && read == 1 -> listOf(9, 4)
                        offset == 0 && read == 2 -> listOf(7, 4)
                        else -> listOf(4, 3, 2, 1, 0).drop(offset).take(2)
                    }
                }

            syncing(fetcher, config(pageLimit = 2, tailStride = 1), stored = listOf(7, 8, 9)) { repository ->
                eventually(5.seconds) { repository.withdrawn() shouldBe setOf("cap-8") }
            }
        }

        test("reconciles a scan whose failed tail page succeeds when read again, and withdraws nothing before it ends") {
            val failing = CopyOnWriteArraySet(setOf(3))
            val requests = CopyOnWriteArrayList<Int>()

            syncing(
                fetcher(listOf(0, 1, 2, 3, 4), requests, failing),
                config(pageLimit = 2, tailStride = 1),
                stored = listOf(8),
            ) { repository ->
                eventually(5.seconds) { requests.count { it == 3 } shouldBeGreaterThan 2 }
                repository.withdrawn().shouldBeEmpty()
                failing.clear()
                eventually(5.seconds) { repository.withdrawn() shouldBe setOf("cap-8") }
            }
        }

        test("withdraws nothing and warns when a scan ends on an empty page that is not its first") {
            val requests = CopyOnWriteArrayList<Int>()
            val fetcher =
                pagedFetcher(requests) { offset, _ ->
                    if (offset == 6) emptyList() else listOf(7, 6, 5, 4, 3, 2, 1, 0).drop(offset).take(4)
                }

            val events =
                logged {
                    syncing(fetcher, config(pageLimit = 4, tailStride = 2), stored = listOf(8)) { repository ->
                        eventually(5.seconds) { requests shouldContain 6 }
                        delay(300.milliseconds)
                        repository.withdrawn().shouldBeEmpty()
                    }
                }

            events.filter { it.level == Level.WARN }.map { it.formattedMessage }.single() shouldContain "0 demos at offset 6"
        }

        test("withdraws nothing and warns when a scan ends on a page shorter than the overlap that is not its first") {
            val requests = CopyOnWriteArrayList<Int>()
            val fetcher =
                pagedFetcher(requests) { offset, _ ->
                    listOf(7, 6, 5, 4, 3, 2, 1, 0).drop(offset).take(if (offset == 6) 1 else 4)
                }

            val events =
                logged {
                    syncing(fetcher, config(pageLimit = 4, tailStride = 2), stored = listOf(8)) { repository ->
                        eventually(5.seconds) { requests shouldContain 6 }
                        delay(300.milliseconds)
                        repository.withdrawn().shouldBeEmpty()
                    }
                }

            events.filter { it.level == Level.WARN }.map { it.formattedMessage }.single() shouldContain "1 demos at offset 6"
        }

        test("reconciles a scan whose first page is short but not empty") {
            val requests = CopyOnWriteArrayList<Int>()

            syncing(
                fetcher(listOf(0, 1, 2), requests),
                config(pageLimit = 4, tailStride = 2),
                stored = listOf(0, 8),
            ) { repository ->
                eventually(5.seconds) { repository.withdrawn() shouldBe setOf("cap-8") }
            }

            requests.filter { it != 0 }.distinct() shouldContainExactly listOf(2)
        }

        test("restores the withdrawn demos that head and tail pages list while a scan runs") {
            val requests = CopyOnWriteArrayList<Int>()

            syncing(
                fetcher(listOf(0, 1, 2, 3, 4, 5), requests, CopyOnWriteArraySet(setOf(6))),
                config(pageLimit = 2, tailStride = 2),
                stored = listOf(1, 5, 8),
                withdrawn = listOf(1, 5, 8),
            ) { repository ->
                eventually(5.seconds) { repository.withdrawn() shouldBe setOf("cap-8") }
            }
        }

        test("restores a withdrawn demo that the head lists while no scan runs") {
            val minutes = CopyOnWriteArrayList(listOf(0, 1, 2))
            val requests = CopyOnWriteArrayList<Int>()

            syncing(fetcher(minutes, requests), config(pageLimit = 4, tailStride = 2), stored = listOf(8)) { repository ->
                eventually(5.seconds) { repository.withdrawn() shouldBe setOf("cap-8") }
                minutes += 8
                eventually(5.seconds) { repository.withdrawn().shouldBeEmpty() }
            }

            requests.filter { it != 0 }.distinct() shouldContainExactly listOf(2)
        }
    })
