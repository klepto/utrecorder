package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingJobs
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class JobEndpointTest :
    FunSpec({
        val root = tempdir().toPath()

        test("lists the running recordings with their attempt and progress") {
            val jobs = RecordingJobs()
            val config = ServerConfig(ConfigReader.EMPTY, directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home")))
            val demo =
                Demo(
                    capId = "running",
                    map = "CTF-BT-Maverick",
                    player = "klepto",
                    capTime = 10.seconds,
                    timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                    demoUrl = "http://api.utbt.net/demos/running",
                )
            testApplication {
                application { HttpService(listOf(JobEndpoint(jobs)), config).module(this) }
                jobs.track(demo) { tracker ->
                    tracker.startAttempt(2)
                    tracker.report(Progress(RecorderStage.FetchingDemo, 0.5))
                    val job =
                        Json
                            .parseToJsonElement(client.get("/api/jobs").bodyAsText())
                            .jsonArray
                            .single()
                            .jsonObject

                    job["demo"]!!.jsonObject["capId"]!!.jsonPrimitive.content shouldBe "running"
                    job["attempt"]!!.jsonPrimitive.content shouldBe "2"
                    job["stage"]!!.jsonPrimitive.content shouldBe "FetchingDemo"
                    job["progress"]!!.jsonPrimitive.content shouldBe "0.5"
                }
                client.get("/api/jobs").bodyAsText() shouldBe "[]"
            }
        }

        test("pages the running recordings in start order by cap id") {
            val jobs = RecordingJobs()
            val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("paged-home"))
            val config = ServerConfig(ConfigReader.EMPTY, directories = directories)

            fun demo(capId: String) =
                Demo(
                    capId = capId,
                    map = "CTF-BT-Maverick",
                    player = "klepto",
                    capTime = 10.seconds,
                    timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                    demoUrl = "http://api.utbt.net/demos/$capId",
                )
            testApplication {
                application { HttpService(listOf(JobEndpoint(jobs)), config).module(this) }
                jobs.track(demo("first")) {
                    jobs.track(demo("second")) {
                        jobs.track(demo("third")) {
                            fun capIds(text: String) =
                                Json
                                    .parseToJsonElement(text)
                                    .jsonArray
                                    .map {
                                        it.jsonObject["demo"]!!
                                            .jsonObject["capId"]!!
                                            .jsonPrimitive.content
                                    }

                            capIds(client.get("/api/jobs").bodyAsText()) shouldBe listOf("first", "second", "third")
                            capIds(client.get("/api/jobs?after=first&limit=1").bodyAsText()) shouldBe listOf("second")
                            val unknown = client.get("/api/jobs?before=missing")
                            unknown.status shouldBe HttpStatusCode.BadRequest
                        }
                    }
                }
            }
        }
    })
