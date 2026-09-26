package dev.klepto.utrecorder.server.cache

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingLane
import dev.klepto.utrecorder.server.recording.RecordingState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class RecordingCacheTest :
    FunSpec({
        val demo =
            Demo(
                id = "7f0e2a52",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83.seconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
            )
        val bytes = "recorded-mp4-bytes".toByteArray()

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val notFound: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }
        var handler = notFound
        var requests = 0

        server.createContext("/") { exchange ->
            requests++
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()

        afterSpec { server.stop(0) }

        beforeTest {
            handler = notFound
            requests = 0
        }

        val baseUrl = "http://${server.address.hostString}:${server.address.port}"

        fun serveBytes(content: ByteArray) {
            handler = { exchange ->
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
        }

        suspend fun <T> withCache(block: suspend (RecordingCache, DocumentStore.Collection<Recording>, Path) -> T): T {
            val directory = createTempDirectory("utrecorder-recording-cache-test")
            try {
                val cache = directory["recordings"].createDirectories()
                val home = directory["home"]
                val config =
                    ServerConfig(
                        ConfigReader.EMPTY,
                        directories = DirectoriesConfig(ConfigReader.EMPTY, home = home),
                        cache = cache,
                    )
                val database = DocumentStore.open(directory["server.db"])
                return try {
                    val recordings = database.collection("recordings", Recording.serializer(), Recording::demoId)
                    val recordingCache = RecordingCache(recordings, { identifier, file -> "$baseUrl/$identifier/$file" }, config)
                    block(recordingCache, recordings, cache)
                } finally {
                    database.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

        test("file answers null for a recording without a local mp4") {
            withCache { cache, _, _ ->
                cache.file(Recording(demoId = demo.id, lane = RecordingLane.SHORT)).shouldBeNull()
            }
        }

        test("file resolves the recording's file name against the cache directory") {
            withCache { cache, _, cacheDir ->
                cache.file(Recording(demoId = demo.id, lane = RecordingLane.SHORT, file = "7f0e2a52.mp4")) shouldBe cacheDir["7f0e2a52.mp4"]
            }
        }

        test("touch sets accessed_at to now and persists it through the recordings") {
            withCache { cache, recordings, _ ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.RECORDED)
                recordings.put(recording)

                val touched = cache.touch(recording)

                touched.accessedAt.shouldNotBeNull()
                recordings
                    .all()
                    .single()
                    .accessedAt shouldBe touched.accessedAt
            }
        }

        test("starting a re-cache downloads the mp4 into the cache directory and sets file and accessed_at") {
            serveBytes(bytes)
            withCache { cache, recordings, cacheDir ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED, archiveId = "item")
                recordings.put(recording)

                cache.startRecache(recording)
                cache.close()
                cache.run()

                val stored = recordings.all().single()
                stored.file shouldBe "7f0e2a52.mp4"
                stored.accessedAt.shouldNotBeNull()
                cacheDir[stored.file!!].readBytes() shouldBe bytes
                requests shouldBe 1
            }
        }

        test("a second start while one is queued does not start another download") {
            serveBytes(bytes)
            withCache { cache, recordings, _ ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED, archiveId = "item")
                recordings.put(recording)

                cache.startRecache(recording)
                cache.startRecache(recording)
                cache.close()
                cache.run()

                requests shouldBe 1
                recordings
                    .all()
                    .single()
                    .file shouldBe "7f0e2a52.mp4"
            }
        }

        test("a failed re-cache leaves no partial mp4 and a later start tries again") {
            var attempt = 0
            handler = { exchange ->
                attempt++
                if (attempt == 1) {
                    exchange.sendResponseHeaders(500, -1)
                } else {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            withCache { cache, recordings, cacheDir ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED, archiveId = "item")
                recordings.put(recording)

                coroutineScope {
                    val job = launch { cache.run() }
                    cache.startRecache(recording)
                    withTimeout(5.seconds) { while (attempt < 1) delay(10) }
                    delay(200)
                    cacheDir["7f0e2a52.mp4"].exists() shouldBe false
                    cache.startRecache(recording)
                    withTimeout(5.seconds) {
                        while (recordings
                                .all()
                                .single()
                                .file == null
                        ) {
                            delay(10)
                        }
                    }
                    cache.close()
                    job.join()
                }

                attempt shouldBe 2
                recordings
                    .all()
                    .single()
                    .file shouldBe "7f0e2a52.mp4"
                cacheDir["7f0e2a52.mp4"].readBytes() shouldBe bytes
            }
        }

        test("a failed re-cache leaves no output file in the cache directory") {
            handler = { exchange -> exchange.sendResponseHeaders(500, -1) }
            withCache { cache, recordings, cacheDir ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED, archiveId = "item")
                recordings.put(recording)

                cache.startRecache(recording)
                cache.close()
                cache.run()

                cacheDir["7f0e2a52.mp4"].shouldNotExist()
                recordings
                    .all()
                    .single()
                    .file
                    .shouldBeNull()
            }
        }
    })
