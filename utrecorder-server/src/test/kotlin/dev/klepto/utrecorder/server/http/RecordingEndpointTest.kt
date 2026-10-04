package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordingEndpointTest :
    FunSpec({
        val root = tempdir().toPath()
        val now = AtomicReference(Instant.parse("2026-09-27T08:00:00Z"))
        val clock =
            object : Clock {
                override fun now(): Instant = now.get()
            }

        beforeTest { now.set(Instant.parse("2026-09-27T08:00:00Z")) }

        fun demo(
            capId: String,
            timestamp: Instant = Instant.parse("2026-09-26T12:00:00Z"),
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = timestamp,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        fun capIds(body: String): List<String> =
            Json
                .parseToJsonElement(body)
                .jsonArray
                .map {
                    it.jsonObject["demo"]!!
                        .jsonObject["capId"]!!
                        .jsonPrimitive.content
                }

        suspend fun http(
            statuses: Map<String, RecordingStatus>,
            mp4s: List<String> = emptyList(),
            block: suspend ApplicationTestBuilder.(RecordingRepository) -> Unit,
        ) {
            val directory = createTempDirectory(root, "http")
            val recordings = directory.resolve("recordings").createDirectories()
            mp4s.forEach { recordings.resolve("$it.mp4").writeText("0123456789") }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories = DirectoriesConfig(ConfigReader.EMPTY, home = directory.resolve("home")),
                    recordings = recordings,
                )
            SqliteDocumentStore.open(directory.resolve("server.db")) { store ->
                val repository = RecordingRepository(store, DemoRepository(store), clock)
                repository += statuses.keys.map { demo(it) }
                statuses.forEach { (capId, status) -> repository.finish(capId, status) }
                testApplication {
                    application { HttpService(listOf(RecordingEndpoint(repository, config)), config).module(this) }
                    block(repository)
                }
            }
        }

        test("answers the recording of a cap whatever its status, with its error, and a JSON 404 for an unknown cap") {
            http(mapOf("failed" to Failed, "pending" to Pending)) { repository ->
                repository.finish("failed", Failed, "Capture timed out")
                val failed = Json.parseToJsonElement(client.get("/api/recording/failed").bodyAsText()).jsonObject
                val pending = Json.parseToJsonElement(client.get("/api/recording/pending").bodyAsText()).jsonObject
                val unknown = client.get("/api/recording/unknown")

                failed["demo"]!!.jsonObject["capId"]!!.jsonPrimitive.content shouldBe "failed"
                failed["status"]!!.jsonPrimitive.content shouldBe "Failed"
                failed["error"]!!.jsonPrimitive.content shouldBe "Capture timed out"
                pending["status"]!!.jsonPrimitive.content shouldBe "Pending"
                unknown.status shouldBe HttpStatusCode.NotFound
                unknown.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
            }
        }

        test("lists an outdated recording while its mp4 exists, in the same order as the others") {
            http(
                mapOf("outdated" to Outdated, "recorded" to Recorded),
                listOf("outdated", "recorded"),
            ) { repository ->
                now.set(Instant.parse("2026-09-27T00:00:00Z"))
                repository.recorded("outdated", 1.hours)
                now.set(Instant.parse("2026-09-27T01:00:00Z"))
                repository.recorded("recorded", 1.hours)
                repository.finish("outdated", Outdated)
                repository.finish("recorded", Recorded)

                capIds(client.get("/api/recordings").bodyAsText()) shouldBe listOf("recorded", "outdated")
            }
        }

        test("drops an outdated recording once its mp4 is gone") {
            http(mapOf("outdated" to Outdated)) {
                capIds(client.get("/api/recordings").bodyAsText()) shouldBe emptyList()
            }
        }

        test("lists the completed recordings, newest first") {
            http(emptyMap()) { repository ->
                repository += (1..12).map { demo("recording-$it", Instant.parse("2026-09-26T00:00:00Z") - it.hours) }
                repository += listOf(demo("pending", Instant.parse("2026-09-27T00:00:00Z")))
                (1..12).forEach {
                    now.set(Instant.parse("2026-09-27T00:00:00Z") + it.hours)
                    repository.recorded("recording-$it", 1.hours)
                    if (it % 2 == 0) repository.finish("recording-$it", Archived)
                }

                capIds(client.get("/api/recordings").bodyAsText()) shouldBe (12 downTo 1).map { "recording-$it" }
            }
        }

        test("pages the recordings with limit and offset, and caps the limit at 100") {
            http(emptyMap()) { repository ->
                repository += (1..110).map { demo("recording-$it") }
                (1..110).forEach {
                    now.set(Instant.parse("2026-09-27T00:00:00Z") + it.hours)
                    repository.recorded("recording-$it", 1.hours)
                }

                capIds(client.get("/api/recordings?limit=3&offset=2").bodyAsText()) shouldBe
                    listOf("recording-108", "recording-107", "recording-106")
                capIds(client.get("/api/recordings?offset=105").bodyAsText()) shouldBe (5 downTo 1).map { "recording-$it" }
                capIds(client.get("/api/recordings").bodyAsText()).size shouldBe 100
                capIds(client.get("/api/recordings?limit=500").bodyAsText()).size shouldBe 100
                capIds(client.get("/api/recordings?limit=0").bodyAsText()) shouldBe emptyList()
            }
        }

        test("answers 400 for a limit or offset that is not a non-negative integer") {
            http(emptyMap()) {
                listOf("limit=-1", "limit=abc", "offset=-1", "offset=1.5", "limit=").forEach { query ->
                    client.get("/api/recordings?$query").status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("lists every recording of the statuses named in any case, an outdated one without its mp4 included") {
            http(mapOf("failed" to Failed, "unresolvable" to Unresolvable, "recorded" to Recorded, "outdated" to Outdated)) {
                capIds(client.get("/api/recordings?status=failed,UNRESOLVABLE").bodyAsText()) shouldBe
                    listOf("unresolvable", "failed")
                capIds(client.get("/api/recordings?status=Outdated").bodyAsText()) shouldBe listOf("outdated")
            }
        }

        test("pages the recordings of a status by default and cap of 100") {
            http(emptyMap()) { repository ->
                repository += (1..110).map { demo("pending-$it") }

                capIds(client.get("/api/recordings?status=pending").bodyAsText()).size shouldBe 100
                capIds(client.get("/api/recordings?status=pending&limit=500").bodyAsText()).size shouldBe 100
                capIds(client.get("/api/recordings?status=pending&limit=55").bodyAsText()).size shouldBe 55
            }
        }

        test("pages after a cap id cursor, and answers 400 for a cursor that names no recording") {
            http(emptyMap()) { repository ->
                repository += listOf(demo("a"), demo("b"), demo("c"))
                listOf("a", "b", "c").forEach { repository.recorded(it, 1.hours) }

                capIds(client.get("/api/recordings?after=c&limit=1").bodyAsText()) shouldBe listOf("b")
                client.get("/api/recordings?after=unknown").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("answers the status message for an unknown status together with a malformed limit") {
            http(emptyMap()) {
                val response = client.get("/api/recordings?status=unknown&limit=abc")

                response.status shouldBe HttpStatusCode.BadRequest
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject.values
                    .single()
                    .jsonPrimitive.content shouldBe
                    "Status must be recording statuses separated by commas"
            }
        }

        test("answers 400 for a status that names no recording status") {
            http(emptyMap()) {
                listOf("status=unknown", "status=failed,unknown", "status=failed,", "status=").forEach { query ->
                    client.get("/api/recordings?$query").status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("orders recordings with equal recording and demo times by capId, descending") {
            http(emptyMap()) { repository ->
                repository += listOf(demo("b"), demo("c"), demo("a"))
                listOf("b", "c", "a").forEach { repository.recorded(it, 1.hours) }

                capIds(client.get("/api/recordings").bodyAsText()) shouldBe listOf("c", "b", "a")
            }
        }
    })
