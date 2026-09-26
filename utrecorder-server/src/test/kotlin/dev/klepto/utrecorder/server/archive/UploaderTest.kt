package dev.klepto.utrecorder.server.archive

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.ArchiveConfig
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.DemoStore
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import dev.klepto.utrecorder.server.RecordingState
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class UploaderTest :
    FunSpec({
        val demo =
            Demo(
                id = 0,
                sourceId = "7f0e2a52",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83.seconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
                recordingId = 0,
            )
        val md5 = "d41d8cd98f00b204e9800998ecf8427e"
        val enabledConfig =
            ArchiveConfig(
                ConfigReader.EMPTY,
                accessKey = "access",
                secretKey = "secret",
                collection = "test_collection",
                prefix = "utrecorder",
            )
        val disabledConfig = ArchiveConfig(ConfigReader.EMPTY)

        fun MockRequestHandleScope.matchingMetadataResponse() =
            respond(
                """{"files":[{"name":"demo.mp4","md5":"$md5"}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )

        fun MockRequestHandleScope.emptyMetadataResponse() =
            respond("""{"files":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

        suspend fun withUploader(
            engine: MockEngine,
            archiveConfig: ArchiveConfig,
            block: suspend (Uploader, DemoStore, Path) -> Unit,
        ) {
            val directory = createTempDirectory("utrecorder-uploader-test")
            try {
                val cache = directory["recordings"].createDirectories()
                val home = directory["home"]
                val config =
                    ServerConfig(
                        ConfigReader { if (it == "home") home.toString() else null },
                        cache = cache,
                        archive = archiveConfig,
                    )
                val store = DemoStore.open(directory["server.db"], cache)
                try {
                    block(Uploader(InternetArchive(archiveConfig, engine), store, config), store, cache)
                } finally {
                    store.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

        test("uploads and archives a recording once the metadata lists its md5") {
            runTest {
                val engine =
                    MockEngine { request ->
                        if (request.method == HttpMethod.Put) respond("") else matchingMetadataResponse()
                    }
                withUploader(engine, enabledConfig) { uploader, store, cache ->
                    cache["demo.mp4"].writeText("mp4")
                    val (storedDemo, recording) =
                        store.insert(
                            demo,
                            Recording(lane = RecordingLane.SHORT, state = RecordingState.RECORDED, file = "demo.mp4", md5 = md5),
                        )

                    uploader.send(storedDemo, recording)
                    uploader.isEmpty shouldBe false
                    uploader.close()
                    uploader.run()

                    val archived = store.load().recordings.single()
                    archived.state shouldBe RecordingState.ARCHIVED
                    archived.archiveId shouldBe "utrecorder-7f0e2a52"
                    archived.collection shouldBe "test_collection"
                    archived.archivedAt.shouldNotBeNull()
                }
            }
        }

        test("retries a failed upload with growing backoff until it succeeds") {
            runTest {
                var putAttempts = 0
                val engine =
                    MockEngine { request ->
                        if (request.method == HttpMethod.Put) {
                            putAttempts++
                            if (putAttempts < 3) respond("SlowDown", HttpStatusCode.ServiceUnavailable) else respond("")
                        } else {
                            matchingMetadataResponse()
                        }
                    }
                withUploader(engine, enabledConfig) { uploader, store, cache ->
                    cache["demo.mp4"].writeText("mp4")
                    val (storedDemo, recording) =
                        store.insert(
                            demo,
                            Recording(lane = RecordingLane.SHORT, state = RecordingState.RECORDED, file = "demo.mp4", md5 = md5),
                        )

                    uploader.send(storedDemo, recording)
                    uploader.close()
                    uploader.run()

                    putAttempts shouldBe 3
                    store
                        .load()
                        .recordings
                        .single()
                        .state shouldBe RecordingState.ARCHIVED
                }
                currentTime shouldBe 6_000_000L
            }
        }

        test("archives only once the metadata lists the matching md5") {
            runTest {
                var getAttempts = 0
                val engine =
                    MockEngine { request ->
                        if (request.method == HttpMethod.Put) {
                            respond("")
                        } else {
                            getAttempts++
                            if (getAttempts < 3) emptyMetadataResponse() else matchingMetadataResponse()
                        }
                    }
                withUploader(engine, enabledConfig) { uploader, store, cache ->
                    cache["demo.mp4"].writeText("mp4")
                    val (storedDemo, recording) =
                        store.insert(
                            demo,
                            Recording(lane = RecordingLane.SHORT, state = RecordingState.RECORDED, file = "demo.mp4", md5 = md5),
                        )

                    uploader.send(storedDemo, recording)
                    uploader.close()
                    uploader.run()

                    getAttempts shouldBe 3
                    store
                        .load()
                        .recordings
                        .single()
                        .state shouldBe RecordingState.ARCHIVED
                }
            }
        }

        test("retries a failed metadata check instead of stopping the uploader") {
            runTest {
                var getAttempts = 0
                val engine =
                    MockEngine { request ->
                        if (request.method == HttpMethod.Put) {
                            respond("")
                        } else {
                            getAttempts++
                            when (getAttempts) {
                                1 -> respond("", HttpStatusCode.InternalServerError)
                                2 -> respond("not json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                                else -> matchingMetadataResponse()
                            }
                        }
                    }
                withUploader(engine, enabledConfig) { uploader, store, cache ->
                    cache["demo.mp4"].writeText("mp4")
                    val (firstDemo, firstRecording) =
                        store.insert(
                            demo,
                            Recording(lane = RecordingLane.SHORT, state = RecordingState.RECORDED, file = "demo.mp4", md5 = md5),
                        )
                    val (secondDemo, secondRecording) =
                        store.insert(
                            demo.copy(sourceId = "9a1b3c4d"),
                            Recording(lane = RecordingLane.SHORT, state = RecordingState.RECORDED, file = "demo.mp4", md5 = md5),
                        )

                    uploader.send(firstDemo, firstRecording)
                    uploader.send(secondDemo, secondRecording)
                    uploader.close()
                    uploader.run()

                    getAttempts shouldBe 4
                    store.load().recordings.map { it.state } shouldBe
                        listOf(RecordingState.ARCHIVED, RecordingState.ARCHIVED)
                }
                currentTime shouldBe 6_000_000L
            }
        }

        test("a disabled uploader never calls the engine") {
            runTest {
                val engine = MockEngine { error("the disabled uploader must never call the engine") }
                withUploader(engine, disabledConfig) { uploader, store, cache ->
                    cache["demo.mp4"].writeText("mp4")
                    val (storedDemo, recording) =
                        store.insert(
                            demo,
                            Recording(lane = RecordingLane.SHORT, state = RecordingState.RECORDED, file = "demo.mp4", md5 = md5),
                        )

                    uploader.send(storedDemo, recording)
                    uploader.isEmpty shouldBe true
                    uploader.close()
                    uploader.run()

                    store
                        .load()
                        .recordings
                        .single()
                        .state shouldBe RecordingState.RECORDED
                }
            }
        }
    })
