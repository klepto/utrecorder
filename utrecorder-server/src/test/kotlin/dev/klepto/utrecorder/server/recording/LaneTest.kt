package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.core.RecorderProgress
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.ArchiveConfig
import dev.klepto.utrecorder.server.Cleanup
import dev.klepto.utrecorder.server.CleanupService
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.archive.InternetArchive
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.ut99.UtPackageException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class LaneTest :
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

        class Harness(
            val directory: Path,
            val recordings: DocumentStore.Collection<Recording>,
            val queue: RecordingQueue,
            val config: ServerConfig,
            val sent: MutableList<Recording>,
            val urls: MutableList<String>,
        ) {
            val workspace: Path = directory["workspace"]

            suspend fun enqueue(demo: Demo): Recording {
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT)
                recordings.put(recording)
                queue.enqueue(demo to recording)
                return recording
            }

            suspend fun stored(recording: Recording): Recording = recordings.all().single { it.demoId == recording.demoId }
        }

        suspend fun withLane(
            metadata: String = """{}""",
            archiveEnabled: Boolean = false,
            cacheIsFile: Boolean = false,
            record: suspend (Path, (RecorderProgress) -> Unit) -> Path = { workspace, _ ->
                workspace.createDirectories()["demo.mp4"].apply { writeText("data") }
            },
            block: suspend Harness.(Lane) -> Unit,
        ) {
            val directory = createTempDirectory("utrecorder-lane-test")
            val cache = directory["cache"]
            if (cacheIsFile) cache.writeText("not a directory")
            val archiveConfig =
                if (archiveEnabled) {
                    ArchiveConfig(ConfigReader.EMPTY, accessKey = "access", secretKey = "secret")
                } else {
                    ArchiveConfig(ConfigReader.EMPTY)
                }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    database = directory["server.db"],
                    cache = cache,
                    demoUrl = "http://demos.test",
                    archive = archiveConfig,
                )
            val engine =
                MockEngine {
                    respond(metadata, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val database = DocumentStore.open(config.database)
            try {
                val recordings = database.collection("recordings", Recording.serializer(), Recording::demoId)
                val cleanups = database.collection("cleanups", Cleanup.serializer()) { it.path.toString() }
                val queue = RecordingQueue()
                val sent = mutableListOf<Recording>()
                val urls = mutableListOf<String>()
                val fixture = Harness(directory, recordings, queue, config, sent, urls)
                val lane =
                    Lane(
                        RecordingLane.SHORT,
                        queue,
                        recordings,
                        CleanupService(cleanups),
                        InternetArchive(archiveConfig, engine),
                        upload = { _, recording -> sent += recording },
                        config = config,
                    ) { url, onProgress ->
                        urls += url
                        record(fixture.workspace, onProgress)
                    }
                fixture.block(lane)
            } finally {
                database.close()
                directory.deleteRecursively()
            }
        }

        test("moves the mp4 into the cache, writes it recorded, deletes the workspace and sends it to upload") {
            withLane {
                val recording = enqueue(demo)

                it.recordNext()

                urls shouldBe listOf("http://demos.test/7f0e2a52")
                val stored = stored(recording)
                stored.state shouldBe RecordingState.RECORDED
                stored.file shouldBe "7f0e2a52.mp4"
                stored.md5 shouldBe "8d777f385d3dfec8815d20f7496026dc"
                stored.recordedAt.shouldNotBeNull()
                stored.accessedAt shouldBe stored.recordedAt
                config.cache["7f0e2a52.mp4"].readText() shouldBe "data"
                workspace.exists() shouldBe false
                sent shouldBe listOf(stored)
            }
        }

        test("exposes the progress of the recording it records") {
            val reported = RecorderProgress(RecorderStage.entries.first(), 0.5)
            val recording = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withLane(record = { workspace, onProgress ->
                onProgress(reported)
                recording.complete(Unit)
                release.await()
                workspace.createDirectories()["demo.mp4"].apply { writeText("data") }
            }) { lane ->
                enqueue(demo)
                coroutineScope {
                    val next = async { lane.recordNext() }
                    recording.await()
                    lane.progress shouldBe reported
                    release.complete(Unit)
                    next.await()
                }
                lane.progress.shouldBeNull()
            }
        }

        test("puts a failed recording back at the end of the queue until it fails for good") {
            withLane(record = { workspace, _ ->
                workspace.createDirectories()
                error("capture failed")
            }) { lane ->
                val recording = enqueue(demo)

                lane.recordNext()
                stored(recording).state shouldBe RecordingState.PENDING
                stored(recording).attempts shouldBe 1
                stored(recording).error shouldBe "capture failed"

                lane.recordNext()
                lane.recordNext()
                stored(recording).state shouldBe RecordingState.FAILED
                stored(recording).attempts shouldBe 3
                workspace.exists() shouldBe true
                urls.size shouldBe 3
                sent.shouldBeEmpty()
            }
        }

        test("fails a recording at once when a package resolves nowhere") {
            withLane(record = { _, _ -> throw UtPackageException("BTPog", listOf("local")) }) { lane ->
                val recording = enqueue(demo)

                lane.recordNext()

                stored(recording).state shouldBe RecordingState.FAILED
                stored(recording).attempts shouldBe 1
            }
        }

        test("fails a recording at once without recording it when its demo does not parse") {
            withLane { lane ->
                val recording = enqueue(demo.copy(map = null, duration = null))

                lane.recordNext()

                stored(recording).state shouldBe RecordingState.FAILED
                urls.shouldBeEmpty()
            }
        }

        test("keeps the workspace and counts a failed move as a failed attempt") {
            withLane(cacheIsFile = true) { lane ->
                val recording = enqueue(demo)

                lane.recordNext()

                stored(recording).state shouldBe RecordingState.PENDING
                stored(recording).attempts shouldBe 1
                workspace["demo.mp4"].exists() shouldBe true
            }
        }

        test("writes a recording the Archive already lists archived without recording it") {
            withLane(metadata = """{"files":[{"name":"7f0e2a52.mp4","md5":"abc"}]}""", archiveEnabled = true) { lane ->
                val recording = enqueue(demo)

                lane.recordNext()

                val stored = stored(recording)
                stored.state shouldBe RecordingState.ARCHIVED
                stored.archiveId shouldBe "utrecorder-7f0e2a52"
                urls.shouldBeEmpty()
            }
        }

        test("records a recording the Archive does not list") {
            withLane(metadata = """{"files":[]}""", archiveEnabled = true) { lane ->
                val recording = enqueue(demo)

                lane.recordNext()

                stored(recording).state shouldBe RecordingState.RECORDED
            }
        }

        test("does not count a cancelled recording as a failed attempt") {
            val started = CompletableDeferred<Unit>()
            withLane(record = { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }) { lane ->
                val recording = enqueue(demo)
                coroutineScope {
                    val next = async { lane.recordNext() }
                    started.await()
                    next.cancelAndJoin()
                }

                stored(recording).attempts shouldBe 0
            }
        }
    })
