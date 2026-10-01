package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordingRepositoryTest :
    FunSpec({
        val config = ServerConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = tempdir().toPath()))

        fun demo(
            capId: String,
            minute: Int,
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = Instant.parse("2026-09-26T12:00:00Z") + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        val now = AtomicReference(Instant.parse("2026-09-27T08:00:00Z"))
        val clock =
            object : Clock {
                override fun now(): Instant = now.get()
            }

        fun file(): Path = createTempDirectory("utrecorder-recording-repository-test").resolve("server.db")

        suspend fun <R> repository(
            file: Path = file(),
            block: suspend (RecordingRepository) -> R,
        ): R =
            SqliteDocumentStore.open(file) {
                block(RecordingRepository(it, DemoRepository(it), config, clock))
            }

        test("stores a pending recording of an added demo and skips a demo already stored") {
            val entry =
                repository {
                    it += listOf(demo("cap", 0))
                    it.finish("cap", Recorded)
                    it += listOf(demo("cap", 0))
                    it["cap"]
                }
            entry shouldBe Recording(demo("cap", 0), Recorded, lastUpdate = now.get())
        }

        test("stores the demo of an added recording in the demo repository and reads it from there once reopened") {
            val file = file()
            SqliteDocumentStore.open(file) { store ->
                RecordingRepository(store, DemoRepository(store), config, clock) += listOf(demo("cap", 0))
            }

            val stored =
                SqliteDocumentStore.open(file) { store ->
                    val demos = DemoRepository(store)
                    demos.all() to RecordingRepository(store, demos, config, clock)["cap"]?.demo
                }
            stored shouldBe (listOf(demo("cap", 0)) to demo("cap", 0))
        }

        test("reads every stored recording when a demo is added first after reopening") {
            val file = file()
            repository(file) { it += listOf(demo("stored", 0)) }

            val capIds =
                repository(file) { repository ->
                    repository += listOf(demo("added", 1))
                    repository.all().map { it.demo.capId }
                }
            capIds shouldBe listOf("stored", "added")
        }

        test("sets a recorded recording with the time it was recorded and the time it took") {
            val entry =
                repository {
                    it += listOf(demo("cap", 0))
                    it.recorded("cap", 90.seconds)
                    it["cap"]
                }
            entry shouldBe
                Recording(
                    demo("cap", 0),
                    Recorded,
                    lastUpdate = now.get(),
                    recordedAt = now.get(),
                    processingTime = 90.seconds,
                )
        }

        test("removes the unrecorded recordings that are not kept, and keeps every other one") {
            val capIds =
                repository { repository ->
                    val capIds = listOf("pending", "failed", "unresolvable", "recording", "recorded", "viewed", "kept")
                    repository += capIds.mapIndexed { minute, capId -> demo(capId, minute) }
                    repository.update("recording") { it.copy(status = RecordingStatus.Recording) }
                    repository.finish("failed", Failed)
                    repository.finish("unresolvable", Unresolvable)
                    repository.finish("recorded", Recorded)
                    repository.update("viewed") { it.copy(recordedAt = now.get()) }
                    repository.retainUnrecorded { it == "kept" }
                    capIds.filter { repository[it] != null }
                }
            capIds shouldBe listOf("recording", "recorded", "viewed", "kept")
        }

        test("takes the demo of the newest pending recording and sets it to recording") {
            val taken =
                repository { repository ->
                    repository += listOf(demo("older", 0), demo("newer", 1))
                    val first = repository.take()
                    val second = repository.take()
                    listOf(first.capId, second.capId, repository["newer"]?.status)
                }
            taken shouldBe listOf("newer", "older", RecordingStatus.Recording)
        }

        test("suspends a take until a demo is added") {
            val taken =
                repository { repository ->
                    coroutineScope {
                        val take = async { repository.take() }
                        delay(50.milliseconds)
                        take.isActive shouldBe true
                        repository += listOf(demo("cap", 0))
                        take.await().capId
                    }
                }
            taken shouldBe "cap"
        }

        test("takes a backlog demo only while fewer recordings than the backlog buffer are recording or recorded") {
            val taken =
                repository { repository ->
                    repository += listOf(demo("backlog-older", -20_000), demo("backlog-newer", -10_000))
                    val first = repository.take()
                    coroutineScope {
                        val second = async { repository.take() }
                        delay(50.milliseconds)
                        second.isActive shouldBe true
                        repository.finish(first.capId, Recorded)
                        delay(50.milliseconds)
                        second.isActive shouldBe true
                        repository.finish(first.capId, Uploaded)
                        listOf(first.capId, second.await().capId)
                    }
                }
            taken shouldBe listOf("backlog-newer", "backlog-older")
        }

        test("takes a recent demo while the backlog buffer is full") {
            val taken =
                repository { repository ->
                    repository += listOf(demo("backlog", -10_000), demo("recorded", -20_000), demo("recent", 0))
                    repository.finish("recorded", Recorded)
                    listOf(repository.take().capId, repository["backlog"]?.status)
                }
            taken shouldBe listOf("recent", Pending)
        }

        test("takes a pending demo recorded before while the backlog buffer is full") {
            val taken =
                repository { repository ->
                    repository += listOf(demo("backlog", -10_000), demo("recorded", -20_000), demo("evicted", -30_000))
                    repository.finish("recorded", Recorded)
                    repository.update("evicted") { it.copy(recordedAt = Instant.parse("2026-09-20T12:00:00Z")) }
                    listOf(repository.take().capId, repository["backlog"]?.status)
                }
            taken shouldBe listOf("evicted", Pending)
        }

        test("keeps the error of a finished recording") {
            val entry =
                repository {
                    it += listOf(demo("cap", 0))
                    it.finish("cap", Unresolvable, "Missing BTPog")
                    it["cap"]
                }
            entry shouldBe Recording(demo("cap", 0), Unresolvable, "Missing BTPog", lastUpdate = now.get())
        }

        test("replaces every recording with the result of a transform") {
            val all =
                repository {
                    it += listOf(demo("failed", 0), demo("recorded", 1))
                    it.finish("failed", Failed, "Capture failed")
                    it.finish("recorded", Recorded)
                    it.updateAll { recording -> if (recording.status == Failed) recording.copy(status = Pending) else recording }
                    it.all()
                }
            all shouldBe
                listOf(
                    Recording(demo("failed", 0), Pending, "Capture failed", lastUpdate = now.get()),
                    Recording(demo("recorded", 1), Recorded, lastUpdate = now.get()),
                )
        }

        test("sets the last update when the status changes, and only then") {
            val updates =
                repository {
                    now.set(Instant.parse("2026-09-27T08:00:00Z"))
                    it += listOf(demo("cap", 0))
                    val added = it["cap"]?.lastUpdate
                    now.set(Instant.parse("2026-09-27T09:00:00Z"))
                    it.finish("cap", Recorded)
                    val recorded = it["cap"]?.lastUpdate
                    now.set(Instant.parse("2026-09-27T10:00:00Z"))
                    it.update("cap") { recording -> recording.copy(md5 = "md5") }
                    listOf(added, recorded, it["cap"]?.lastUpdate)
                }
            updates shouldBe
                listOf(
                    Instant.parse("2026-09-27T08:00:00Z"),
                    Instant.parse("2026-09-27T09:00:00Z"),
                    Instant.parse("2026-09-27T09:00:00Z"),
                )
        }
    })
