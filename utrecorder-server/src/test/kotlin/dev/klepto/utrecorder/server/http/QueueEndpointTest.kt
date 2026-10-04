package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class QueueEndpointTest :
    FunSpec({
        val root = tempdir().toPath()
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-27T08:00:00Z")
            }

        fun demo(
            capId: String,
            timestamp: Instant,
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = timestamp,
                demoUrl = "http://api.utbt.net/demos/$capId",
                userId = capId,
            )

        test("lists the demos of the pending recordings in queue order, up to 100: recent, medal, cap time, newest") {
            val config = ServerConfig(ConfigReader.EMPTY, directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home")))
            SqliteDocumentStore.open(root.resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, clock)
                val queue = RecordingQueue(demos, repository, config, clock)
                testApplication {
                    application { HttpService(listOf(QueueEndpoint(queue)), config).module(this) }
                    coroutineScope {
                        val job = launch { queue.start() }
                        repository +=
                            listOf(
                                demo("backlog", Instant.parse("2026-09-25T08:00:00Z")).copy(medal = 6),
                                demo("casual-slow", Instant.parse("2026-09-27T07:00:00Z")).copy(capTime = 20.seconds),
                                demo("casual-fast", Instant.parse("2026-09-27T06:00:00Z")),
                                demo("gold", Instant.parse("2026-09-27T05:00:00Z")).copy(medal = 4),
                                demo("world-record", Instant.parse("2026-09-27T04:00:00Z")).copy(medal = 6),
                                demo("recorded", Instant.parse("2026-09-27T07:30:00Z")).copy(medal = 6),
                            )
                        repository +=
                            (1..8).map {
                                demo("filler-$it", Instant.parse("2026-09-26T20:00:00Z") - it.hours).copy(capTime = 30.seconds)
                            }
                        repository.finish("recorded", Recorded)

                        eventually(5.seconds) {
                            val queued = Json.parseToJsonElement(client.get("/api/queue").bodyAsText()).jsonArray
                            queued.map { it.jsonObject["capId"]!!.jsonPrimitive.content } shouldBe
                                listOf("world-record", "gold", "casual-fast", "casual-slow") + (1..8).map { "filler-$it" } + "backlog"
                            val after = client.get("/api/queue?after=casual-fast&limit=2").bodyAsText()
                            Json.parseToJsonElement(after).jsonArray.map { it.jsonObject["capId"]!!.jsonPrimitive.content } shouldBe
                                listOf("casual-slow", "filler-1")
                            client.get("/api/queue?after=recorded").status shouldBe HttpStatusCode.BadRequest
                        }
                        job.cancelAndJoin()
                    }
                }
            }
        }
    })
