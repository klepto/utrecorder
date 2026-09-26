package dev.klepto.utrecorder.server.http

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
import dev.klepto.utrecorder.server.cache.RecordingCache
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Lane
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingLane
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class ServeRouteTest :
    FunSpec({
        val demo =
            Demo(
                id = "11111111-1111-1111-1111-111111111111",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83.seconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
            )

        suspend fun <T> withRoute(
            block: suspend (
                Path,
                DocumentStore.Collection<Recording>,
                DocumentStore.Collection<Cleanup>,
                RecordingCache,
            ) -> T,
        ): T {
            val directory = createTempDirectory("utrecorder-serve-route-test")
            val cacheDir = directory["cache"].createDirectories()
            try {
                val database = DocumentStore.open(directory["server.db"])
                try {
                    val recordings = database.collection("recordings", Recording.serializer(), Recording::demoId)
                    val cleanups = database.collection("cleanups", Cleanup.serializer()) { it.path.toString() }
                    val cache =
                        RecordingCache(
                            recordings,
                            { identifier, file -> "https://archive.org/download/$identifier/$file" },
                            ServerConfig(ConfigReader.EMPTY, cache = cacheDir),
                        )
                    return block(directory, recordings, cleanups, cache)
                } finally {
                    database.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

        test("a source_id that is not a UUID answers 404 before any lookup") {
            withRoute { _, recordings, _, cache ->
                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }
                    client.get("/demos/not-a-uuid.mp4").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("an unknown source_id answers 404") {
            withRoute { _, recordings, _, cache ->
                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }
                    val unknown = "22222222-2222-2222-2222-222222222222"
                    client.get("/demos/$unknown.mp4").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("a local mp4 is served with range support and touches accessed_at") {
            withRoute { directory, recordings, _, cache ->
                val cacheDir = directory["cache"]
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.RECORDED)
                recordings.put(recording)
                val fileName = "11111111-1111-1111-1111-111111111111.mp4"
                cacheDir[fileName].writeText("0123456789")
                val withFile = recording.copy(file = fileName)
                recordings.put(withFile)

                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }

                    val full = client.get("/demos/${demo.id}.mp4")
                    full.status shouldBe HttpStatusCode.OK
                    full.bodyAsText() shouldBe "0123456789"

                    val partial =
                        client.get("/demos/${demo.id}.mp4") {
                            header(HttpHeaders.Range, "bytes=0-3")
                        }
                    partial.status shouldBe HttpStatusCode.PartialContent
                    partial.bodyAsText() shouldBe "0123"
                }

                recordings
                    .all()
                    .single()
                    .accessedAt
                    .shouldNotBeNull()
            }
        }

        test("archived with no local mp4 redirects to the Archive and re-caches once for two requests") {
            withRoute { _, recordings, _, cache ->
                val recording =
                    Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED, archiveId = "item-1")
                recordings.put(recording)

                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }
                    val client = createClient { followRedirects = false }

                    val first = client.get("/demos/${demo.id}.mp4")
                    first.status shouldBe HttpStatusCode.Found
                    first.headers[HttpHeaders.Location] shouldBe
                        "https://archive.org/download/item-1/11111111-1111-1111-1111-111111111111.mp4"

                    val second = client.get("/demos/${demo.id}.mp4")
                    second.status shouldBe HttpStatusCode.Found
                }

                cache.close()
                cache.run()
                recordings
                    .all()
                    .single()
                    .accessedAt
                    .shouldNotBeNull()
            }
        }

        test("pending answers 202 with Retry-After and no progress") {
            withRoute { _, recordings, _, cache ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.PENDING)
                recordings.put(recording)

                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }
                    val response = client.get("/demos/${demo.id}.mp4")
                    response.status shouldBe HttpStatusCode.Accepted
                    response.headers[HttpHeaders.RetryAfter] shouldBe "5"
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body["state"]?.jsonPrimitive?.content shouldBe "pending"
                    body.containsKey("progress") shouldBe false
                }
            }
        }

        test("recording answers 202 with the progress of the lane recording it") {
            withRoute { directory, recordings, cleanups, cache ->
                val pending = Recording(demoId = demo.id, lane = RecordingLane.SHORT)
                recordings.put(pending)
                val queue = RecordingQueue()
                queue.enqueue(demo to pending)
                val reported = RecorderProgress(RecorderStage.entries.first(), 0.5)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val archiveConfig = ArchiveConfig(ConfigReader.EMPTY)
                val engine =
                    MockEngine {
                        respond("""{"files":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                val laneConfig =
                    ServerConfig(
                        ConfigReader.EMPTY,
                        database = directory["server.db"],
                        cache = directory["cache"],
                        demoUrl = "http://demos.test",
                        archive = archiveConfig,
                    )
                val lane =
                    Lane(
                        RecordingLane.SHORT,
                        queue,
                        recordings,
                        CleanupService(cleanups),
                        InternetArchive(archiveConfig, engine),
                        upload = { _, _ -> },
                        config = laneConfig,
                    ) { _, onProgress ->
                        onProgress(reported)
                        started.complete(Unit)
                        release.await()
                        directory["workspace"].createDirectories()["demo.mp4"].apply { writeText("data") }
                    }

                coroutineScope {
                    val job = launch(Dispatchers.Default) { lane.recordNext() }
                    started.await()

                    testApplication {
                        application { serveRoute(recordings, cache, listOf(lane)) }
                        val response = client.get("/demos/${demo.id}.mp4")
                        response.status shouldBe HttpStatusCode.Accepted
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body["state"]?.jsonPrimitive?.content shouldBe "recording"
                        val progress = body["progress"]!!.jsonObject
                        progress["stage"]?.jsonPrimitive?.content shouldBe reported.stage.name
                        progress["progress"]?.jsonPrimitive?.content?.toDouble() shouldBe reported.progress
                    }

                    release.complete(Unit)
                    job.join()
                }
            }
        }

        test("failed answers 410 with the error") {
            withRoute { _, recordings, _, cache ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.FAILED, error = "boom")
                recordings.put(recording)

                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }
                    val response = client.get("/demos/${demo.id}.mp4")
                    response.status shouldBe HttpStatusCode.Gone
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body["state"]?.jsonPrimitive?.content shouldBe "failed"
                    body["error"]?.jsonPrimitive?.content shouldBe "boom"
                }
            }
        }

        test("expired answers 410") {
            withRoute { _, recordings, _, cache ->
                val recording = Recording(demoId = demo.id, lane = RecordingLane.SHORT, state = RecordingState.EXPIRED)
                recordings.put(recording)

                testApplication {
                    application { serveRoute(recordings, cache, emptyList()) }
                    client.get("/demos/${demo.id}.mp4").status shouldBe HttpStatusCode.Gone
                }
            }
        }
    })
