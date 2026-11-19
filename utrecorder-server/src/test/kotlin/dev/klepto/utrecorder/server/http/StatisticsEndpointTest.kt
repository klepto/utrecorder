package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.DiskSpace
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.statistics.StatisticsReport
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StatisticsEndpointTest :
    FunSpec({
        val root = tempdir().toPath()
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-10-05T12:00:00Z")
            }

        test("answers the statistics document with its field names, ISO-8601 durations, instants and dates") {
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home")),
                    cacheMinFreeBytes = 1_000,
                    cacheTargetFreeBytes = 2_000,
                )
            SqliteDocumentStore.open(root.resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, clock)
                repository +=
                    listOf(
                        Demo(
                            capId = "recent",
                            map = "CTF-BT-Maverick",
                            player = "klepto",
                            userId = "klepto",
                            capTime = 90.seconds,
                            timestamp = Instant.parse("2026-10-05T11:00:00Z"),
                            demoUrl = "http://api.utbt.net/demos/recent",
                        ),
                    )
                val queue = RecordingQueue(demos, repository, config, clock)
                val jobs = RecordingJobs()
                val cache = CacheService(repository, null, config, clock, DiskSpace { 5_000 })
                val report = StatisticsReport(demos, repository, queue, jobs, cache, config, clock)
                testApplication {
                    application { HttpService(listOf(StatisticsEndpoint(report)), config).module(this) }
                    coroutineScope {
                        val job = launch { queue.start() }

                        val response = client.get("/api/statistics")

                        response.status shouldBe HttpStatusCode.OK
                        val statistics = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        statistics.keys shouldBe setOf("generatedAt", "queue", "recorder", "storage", "personalBests")
                        statistics["generatedAt"]!!.jsonPrimitive.content shouldBe "2026-10-05T12:00:00Z"
                        val queued = statistics["queue"]!!.jsonObject
                        queued.keys shouldBe
                            setOf("size", "recent", "backlog", "capTime", "recording", "failed", "unresolvable", "unavailable")
                        queued["capTime"]!!.jsonPrimitive.content shouldBe "PT1M30S"
                        val recorder = statistics["recorder"]!!.jsonObject
                        recorder.keys shouldBe setOf("pace", "lastDay", "days", "completed", "views")
                        recorder["pace"] shouldBe JsonNull
                        recorder["lastDay"]!!.jsonObject.keys shouldBe setOf("recordings", "capTime")
                        val recorderDay = recorder["days"]!!.jsonArray.last().jsonObject
                        recorderDay.keys shouldBe setOf("date", "recordings", "capTime", "processingTime")
                        recorderDay["date"]!!.jsonPrimitive.content shouldBe "2026-10-05"
                        val storage = statistics["storage"]!!.jsonObject
                        storage.keys shouldBe
                            setOf(
                                "cache",
                                "cacheTargetBytes",
                                "cacheMaxBytes",
                                "archived",
                                "archivedBytes",
                                "archivedUnsized",
                                "uploadsLastDay",
                                "dailyUploads",
                            )
                        storage["cache"] shouldBe JsonNull
                        storage["cacheMaxBytes"] shouldBe JsonNull
                        storage["cacheTargetBytes"] shouldBe JsonNull
                        val personalBests = statistics["personalBests"]!!.jsonObject
                        personalBests.keys shouldBe setOf("lastDay", "days", "players", "demos", "maps")
                        val personalBestDay = personalBests["days"]!!.jsonArray.last().jsonObject
                        personalBestDay.keys shouldBe setOf("date", "personalBests", "players")
                        personalBests["maps"]!!
                            .jsonArray
                            .single()
                            .jsonObject.keys shouldBe setOf("map", "personalBests")

                        cache.evict()
                        val evicted = Json.parseToJsonElement(client.get("/api/statistics").bodyAsText()).jsonObject
                        val limits = evicted["storage"]!!.jsonObject
                        limits["cache"]!!.jsonObject.keys shouldBe setOf("bytes", "files", "measuredAt", "usableBytes")
                        limits["cache"]!!.jsonObject["usableBytes"]!!.jsonPrimitive.content shouldBe "5000"
                        limits["cacheMaxBytes"]!!.jsonPrimitive.content shouldBe "4000"
                        limits["cacheTargetBytes"]!!.jsonPrimitive.content shouldBe "3000"
                        job.cancelAndJoin()
                    }
                }
            }
        }
    })
