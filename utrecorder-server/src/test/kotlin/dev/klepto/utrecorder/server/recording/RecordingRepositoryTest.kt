package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.core.capture.video.VideoStream
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordingRepositoryTest :
    FunSpec({
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
                block(RecordingRepository(it, DemoRepository(it), clock))
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
                RecordingRepository(store, DemoRepository(store), clock) += listOf(demo("cap", 0))
            }

            val stored =
                SqliteDocumentStore.open(file) { store ->
                    val demos = DemoRepository(store)
                    demos.all() to RecordingRepository(store, demos, clock)["cap"]?.demo
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

        test("sets a recorded recording with the time it was recorded, the time it took, the size of its mp4 and its video") {
            val entry =
                repository {
                    it += listOf(demo("cap", 0))
                    it.recorded("cap", 90.seconds, 1_048_576, VideoStream(1920, 1080, 60))
                    it["cap"]
                }
            entry shouldBe
                Recording(
                    demo("cap", 0),
                    Recorded,
                    lastUpdate = now.get(),
                    recordedAt = now.get(),
                    processingTime = 90.seconds,
                    size = 1_048_576,
                    width = 1920,
                    height = 1080,
                    fps = 60,
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

        test("claims a pending recording by setting it to recording and stamping its last update") {
            val claimed =
                repository {
                    now.set(Instant.parse("2026-09-27T08:00:00Z"))
                    it += listOf(demo("cap", 0))
                    now.set(Instant.parse("2026-09-27T09:00:00Z"))
                    it.claim("cap") to it["cap"]
                }
            val expected = Recording(demo("cap", 0), RecordingStatus.Recording, lastUpdate = Instant.parse("2026-09-27T09:00:00Z"))
            claimed shouldBe (expected to expected)
        }

        test("does not claim a missing recording or one that is not pending") {
            val claimed =
                repository {
                    it += listOf(demo("failed", 0), demo("recording", 1))
                    it.finish("failed", Failed)
                    it.finish("recording", RecordingStatus.Recording)
                    listOf(it.claim("missing"), it.claim("failed"), it.claim("recording"), it["failed"]?.status)
                }
            claimed shouldBe listOf(null, null, null, Failed)
        }

        test("signals status changes once when collected and again after a recording is added, changes status or is removed") {
            val counts =
                repository { repository ->
                    coroutineScope {
                        val count = AtomicInteger()
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) { repository.statusChanges.collect { count.incrementAndGet() } }
                        val counts = mutableListOf(count.get())
                        repository += listOf(demo("cap", 0))
                        delay(50.milliseconds)
                        counts += count.get()
                        repository.finish("cap", Failed)
                        delay(50.milliseconds)
                        counts += count.get()
                        repository.retainUnrecorded { false }
                        delay(50.milliseconds)
                        counts += count.get()
                        collector.cancel()
                        counts
                    }
                }
            counts shouldBe listOf(1, 2, 3, 4)
        }

        test("does not signal a status change after a write that keeps every status") {
            val count =
                repository { repository ->
                    repository += listOf(demo("cap", 0))
                    coroutineScope {
                        val count = AtomicInteger()
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) { repository.statusChanges.collect { count.incrementAndGet() } }
                        repository.update("cap") { it.copy(md5 = "md5") }
                        delay(50.milliseconds)
                        collector.cancel()
                        count.get()
                    }
                }
            count shouldBe 1
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

        test("stores the missing package of a finished recording and reads it once reopened") {
            val file = file()
            SqliteDocumentStore.open(file) { store ->
                val repository = RecordingRepository(store, DemoRepository(store), clock)
                repository += listOf(demo("cap", 0))
                repository.finish("cap", Unresolvable, "Missing BTPog", MissingPackage("BTPog", "123456789ABCDEF0876543210BADF00D"))
            }

            val entry = SqliteDocumentStore.open(file) { store -> RecordingRepository(store, DemoRepository(store), clock)["cap"] }
            entry shouldBe
                Recording(
                    demo("cap", 0),
                    Unresolvable,
                    "Missing BTPog",
                    MissingPackage("BTPog", "123456789ABCDEF0876543210BADF00D"),
                    lastUpdate = now.get(),
                )
        }

        test("keeps the stored missing package when a later finish names none, also once reopened") {
            val file = file()
            SqliteDocumentStore.open(file) { store ->
                val repository = RecordingRepository(store, DemoRepository(store), clock)
                repository += listOf(demo("cap", 0))
                repository.finish("cap", Unresolvable, "Missing BTPog", MissingPackage("BTPog", "123456789ABCDEF0876543210BADF00D"))
                repository.finish("cap", Failed)
            }

            val entry = SqliteDocumentStore.open(file) { store -> RecordingRepository(store, DemoRepository(store), clock)["cap"] }
            entry?.status shouldBe Failed
            entry?.missingPackage shouldBe MissingPackage("BTPog", "123456789ABCDEF0876543210BADF00D")
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

        test("reads a stored recording without interruptions as none, and keeps the interruptions written, once reopened") {
            val file = file()
            repository(file) {
                it += listOf(demo("legacy", 0), demo("interrupted", 1))
                it.update("interrupted") { recording -> recording.copy(interruptions = 2) }
            }
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.prepareStatement("UPDATE recordings SET document = ? WHERE key = ?").use {
                    it.setString(1, """{"capId":"legacy","status":"Failed","error":"Capture failed"}""")
                    it.setString(2, "legacy")
                    it.executeUpdate()
                }
            }

            val interruptions = repository(file) { repository -> repository.all().map { it.demo.capId to it.interruptions } }
            interruptions shouldBe listOf("legacy" to 0, "interrupted" to 2)
        }
    })
