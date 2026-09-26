package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingLane
import dev.klepto.utrecorder.server.recording.RecordingState
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.time.Instant
import java.util.Collections
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class UtRecorderServerTest :
    FunSpec({
        fun demo(
            demoId: String,
            addedAt: String,
        ) = Demo(
            id = demoId,
            map = "CTF-BT-Maverick",
            player = "klepto",
            capTime = 83.seconds,
            duration = 1.minutes,
            addedAt = Instant.parse(addedAt),
        )

        test("restores the stored recordings, clears a missing mp4, records the pending ones, serves a recorded one and stops on cancel") {
            val directory = createTempDirectory("utrecorder-server-test")
            try {
                val cacheDir = directory["recordings"].createDirectories()
                val port = withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
                val keys =
                    mapOf(
                        "home" to directory.toString(),
                        "server.port" to port.toString(),
                        "server.database" to directory["server.db"].toString(),
                        "server.cache" to cacheDir.toString(),
                        "server.source_url" to "https://gateway.test",
                        "server.demo_url" to "http://demos.test",
                        "server.archive.access_key" to "access",
                        "server.archive.secret_key" to "secret",
                    )
                val config = ServerConfig(ConfigReader { keys[it] })

                val pendingId = "11111111-1111-1111-1111-111111111111"
                val interruptedId = "22222222-2222-2222-2222-222222222222"
                val recordedId = "33333333-3333-3333-3333-333333333333"
                val missingId = "44444444-4444-4444-4444-444444444444"
                val store = DocumentStore.open(config.database)
                try {
                    val demos = store.collection("demos", Demo.serializer(), Demo::id)
                    val recordings = store.collection("recordings", Recording.serializer(), Recording::demoId)
                    demos.put(demo(pendingId, "2026-09-01T12:02:00Z"))
                    recordings.put(Recording(demoId = pendingId, lane = RecordingLane.SHORT))
                    demos.put(demo(interruptedId, "2026-09-01T12:01:00Z"))
                    recordings.put(Recording(demoId = interruptedId, lane = RecordingLane.SHORT, state = RecordingState.RECORDING))
                    demos.put(demo(recordedId, "2026-09-01T12:00:00Z"))
                    cacheDir["$recordedId.mp4"].writeText("0123456789")
                    recordings.put(
                        Recording(
                            demoId = recordedId,
                            lane = RecordingLane.SHORT,
                            state = RecordingState.RECORDED,
                            file = "$recordedId.mp4",
                            md5 = "781e5e245d69b566979b86e28d23f2c7",
                            accessedAt = Instant.now(),
                        ),
                    )
                    demos.put(demo(missingId, "2026-08-31T12:00:00Z"))
                    recordings.put(
                        Recording(demoId = missingId, lane = RecordingLane.SHORT, state = RecordingState.ARCHIVED, file = "$missingId.mp4"),
                    )
                } finally {
                    store.close()
                }

                val recordedUrls = Collections.synchronizedList(mutableListOf<String>())
                val requests = Collections.synchronizedList(mutableListOf<String>())
                val engine =
                    MockEngine { request ->
                        requests += "${request.method.value} ${request.url}"
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        when {
                            request.method == HttpMethod.Put -> respond("", HttpStatusCode.OK)
                            request.url.host == "gateway.test" -> respond("""{"data":[]}""", HttpStatusCode.OK, json)
                            else -> respond("""{"files":[]}""", HttpStatusCode.OK, json)
                        }
                    }

                val server =
                    launch {
                        UtRecorderServer.run(config, engine) { url, _ ->
                            recordedUrls += url
                            val workspace = directory["workspace-${recordedUrls.size}"].createDirectories()
                            workspace["demo.mp4"].also { it.writeText("recorded") }
                        }
                    }

                eventually(30.seconds) {
                    recordedUrls.toList() shouldContainExactly
                        listOf("http://demos.test/$interruptedId", "http://demos.test/$pendingId")
                }
                eventually(30.seconds) {
                    requests.toList() shouldContain "PUT https://s3.us.archive.org/utrecorder-$recordedId/$recordedId.mp4"
                }
                HttpClient(CIO).use { client ->
                    eventually(30.seconds) {
                        client.get("http://localhost:$port/demos/$recordedId.mp4").bodyAsText() shouldBe "0123456789"
                    }
                }

                withTimeout(30.seconds) { server.cancelAndJoin() }
                server.isCancelled shouldBe true
                val reopened = DocumentStore.open(config.database)
                try {
                    val recordings = reopened.collection("recordings", Recording.serializer(), Recording::demoId)
                    recordings[missingId]?.file shouldBe null
                } finally {
                    reopened.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    })
