package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import dev.klepto.utrecorder.ut99.UtPackageException
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class RecordingServiceTest :
    FunSpec({
        val root = tempdir().toPath()
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home"))

        fun demo(
            capId: String,
            minute: Int,
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                userId = capId,
                capTime = 10.seconds,
                timestamp = Instant.parse("2026-09-26T12:00:00Z") + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-27T08:00:00Z")
            }

        fun config(
            recordings: Path,
            maxAttempts: Int = 3,
            cooldown: Duration = 20.milliseconds,
        ): ServerConfig =
            ServerConfig(
                ConfigReader.EMPTY,
                directories,
                recordings = recordings,
                recordingMaxAttempts = maxAttempts,
                recordingCooldown = cooldown,
            )

        fun recorder(record: suspend (Demo, ProgressListener<RecorderStage>) -> RecorderResult): RecorderProvider =
            object : RecorderProvider {
                override suspend fun record(
                    demo: Demo,
                    onProgress: ProgressListener<RecorderStage>,
                ): RecorderResult = record(demo, onProgress)
            }

        fun done(demo: Demo): RecorderResult {
            val mp4 =
                createTempDirectory(root, "workspace")
                    .resolve("${demo.capId}.mp4")
                    .also { it.writeText(demo.capId) }
            return RecorderResult.Done(mp4)
        }

        fun names(directory: Path): List<String> = directory.listDirectoryEntries().map { it.name }

        suspend fun running(
            demos: List<Demo>,
            service: (DemoRepository, RecordingRepository) -> RecordingService,
            block: suspend (RecordingRepository) -> Unit,
        ) {
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demoRepository = DemoRepository(store)
                val repository = RecordingRepository(store, demoRepository, ServerConfig(ConfigReader.EMPTY, directories), clock)
                demoRepository += demos
                coroutineScope {
                    val job = launch { service(demoRepository, repository).start() }
                    block(repository)
                    job.cancelAndJoin()
                }
            }
        }

        test("sets a stored recording to recorded with the time it was recorded and the time its last attempt took") {
            val recordings = createTempDirectory(root, "recordings")
            val attempts = AtomicInteger()
            running(
                listOf(demo("cap", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, _ ->
                                when (attempts.incrementAndGet()) {
                                    1 -> RecorderResult.Failed(IllegalStateException("Capture failed")).also { delay(1.seconds) }
                                    else -> done(demo).also { delay(100.milliseconds) }
                                }
                            },
                        ),
                        config(recordings),
                    )
                },
            ) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Recorded }
                val recording = repository["cap"]!!

                recording.recordedAt shouldBe Instant.parse("2026-09-27T08:00:00Z")
                recording.processingTime!! shouldBeGreaterThanOrEqualTo 100.milliseconds
                recording.processingTime!! shouldBeLessThan 1.seconds
            }
        }

        test("moves the mp4 into the recordings directory and deletes its workspace") {
            val recordings = createTempDirectory(root, "recordings")
            val mp4s = CopyOnWriteArrayList<Path>()
            running(
                listOf(demo("cap", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, _ -> done(demo).also { mp4s.add((it as RecorderResult.Done).mp4) } },
                        ),
                        config(recordings),
                    )
                },
            ) {
                eventually(5.seconds) { recordings.resolve("cap.mp4").readText() shouldBe "cap" }
            }

            mp4s
                .single()
                .parent
                .exists()
                .shouldBeFalse()
        }

        test("skips a demo whose mp4 already exists") {
            val recordings = createTempDirectory(root, "recordings")
            recordings.resolve("known.mp4").writeText("known")
            val recorded = CopyOnWriteArrayList<String>()
            running(
                listOf(demo("known", 1), demo("cap", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, _ -> done(demo).also { recorded += demo.capId } },
                        ),
                        config(recordings),
                    )
                },
            ) { repository ->
                eventually(5.seconds) { recordings.resolve("cap.mp4").exists() shouldBe true }
                eventually(5.seconds) { repository["cap"]?.status shouldBe Recorded }
                repository["known"]?.status shouldBe Recorded
            }

            recorded shouldContainExactly listOf("cap")
        }

        test("records one demo at a time per recorder, once each, and waits the cooldown after each") {
            val recordings = createTempDirectory(root, "recordings")
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val starts = CopyOnWriteArrayList<TimeSource.Monotonic.ValueTimeMark>()
            val recorded = CopyOnWriteArrayList<String>()
            running(
                listOf(demo("newer", 1), demo("older", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, _ ->
                                starts += TimeSource.Monotonic.markNow()
                                maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                                delay(50.milliseconds)
                                active.decrementAndGet()
                                done(demo).also { recorded += demo.capId }
                            },
                        ),
                        config(recordings, cooldown = 200.milliseconds),
                    )
                },
            ) {
                eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("older.mp4", "newer.mp4") }
                delay(500.milliseconds)
            }

            recorded shouldContainExactly listOf("newer", "older")
            maxActive.get() shouldBe 1
            starts[1] - starts[0] shouldBeGreaterThanOrEqualTo 250.milliseconds
        }

        test("records on two recorders at once") {
            val recordings = createTempDirectory(root, "recordings")
            val started = CopyOnWriteArrayList<String>()
            val gate = CompletableDeferred<Unit>()
            val record: suspend (Demo, ProgressListener<RecorderStage>) -> RecorderResult = { demo, _ ->
                started += demo.capId
                gate.await()
                done(demo)
            }
            running(
                listOf(demo("first", 0), demo("second", 1)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(recorder(record), recorder(record)),
                        config(recordings),
                    )
                },
            ) {
                eventually(5.seconds) { started shouldContainExactlyInAnyOrder listOf("first", "second") }
                gate.complete(Unit)
                eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("first.mp4", "second.mp4") }
            }
        }

        test("gives up on a demo after the maximum number of attempts") {
            val recordings = createTempDirectory(root, "recordings")
            val attempts = AtomicInteger()
            running(
                listOf(demo("broken", 1), demo("cap", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, _ ->
                                if (demo.capId == "cap") return@recorder done(demo)
                                attempts.incrementAndGet()
                                RecorderResult.Failed(IllegalStateException("Capture failed"))
                            },
                        ),
                        config(recordings),
                    )
                },
            ) { repository ->
                eventually(5.seconds) { names(recordings) shouldContainExactly listOf("cap.mp4") }
                val broken = repository["broken"]
                broken?.status shouldBe Failed
                broken?.error shouldBe "java.lang.IllegalStateException: Capture failed"
            }

            attempts.get() shouldBe 3
        }

        test("gives up on a demo at once when a package resolves nowhere") {
            val recordings = createTempDirectory(root, "recordings")
            val attempts = AtomicInteger()
            running(
                listOf(demo("unresolvable", 1), demo("cap", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, _ ->
                                if (demo.capId == "cap") return@recorder done(demo)
                                attempts.incrementAndGet()
                                RecorderResult.Unresolvable(UtPackageException("BTPog", listOf("http://packages.example")))
                            },
                        ),
                        config(recordings),
                    )
                },
            ) { repository ->
                eventually(5.seconds) { names(recordings) shouldContainExactly listOf("cap.mp4") }
                repository["unresolvable"]?.status shouldBe Unresolvable
            }

            attempts.get() shouldBe 1
        }

        test("tracks a recording in jobs with its attempt and last progress until it ends") {
            val recordings = createTempDirectory(root, "recordings")
            val gate = CompletableDeferred<Unit>()
            val jobs = RecordingJobs()
            running(
                listOf(demo("cap", 0)),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(
                            recorder { demo, onProgress ->
                                if (!gate.isCompleted) {
                                    onProgress(Progress(FetchingDemo, 0.5))
                                    gate.await()
                                    return@recorder RecorderResult.Failed(IllegalStateException("Capture failed"))
                                }
                                done(demo)
                            },
                        ),
                        config(recordings),
                        jobs,
                    )
                },
            ) {
                eventually(5.seconds) {
                    val job = jobs.jobs.first().single()
                    job.demo.capId shouldBe "cap"
                    job.attempt shouldBe 1
                    job.progress shouldBe Progress(FetchingDemo, 0.5)
                }
                gate.complete(Unit)
                eventually(5.seconds) { recordings.resolve("cap.mp4").exists() shouldBe true }
                jobs.jobs.first().shouldBeEmpty()
            }
        }

        test("records only the fastest demo of each player on each map, and no demo without a user id") {
            val recordings = createTempDirectory(root, "recordings")

            fun cap(
                capId: String,
                minute: Int,
                userId: String?,
                seconds: Int,
                map: String = "CTF-BT-Maverick",
            ): Demo = demo(capId, minute).copy(userId = userId, map = map, capTime = seconds.seconds)
            running(
                listOf(
                    cap("slower", 0, "1", 12),
                    cap("fastest", 1, "1", 10),
                    cap("tie", 2, "1", 10),
                    cap("other-player", 3, "2", 12),
                    cap("other-map", 4, "1", 12, map = "CTF-BT-Other"),
                    cap("no-user", 5, null, 12),
                    cap("no-user-again", 6, null, 12),
                ),
                { demos, repository ->
                    RecordingService(
                        demos,
                        repository,
                        listOf(recorder { demo, _ -> done(demo) }),
                        config(recordings),
                    )
                },
            ) { repository ->
                eventually(5.seconds) {
                    names(recordings) shouldContainExactlyInAnyOrder listOf("fastest.mp4", "other-player.mp4", "other-map.mp4")
                }
                delay(100.milliseconds)
                names(recordings).size shouldBe 3
                repository["no-user"] shouldBe null
                repository["no-user-again"] shouldBe null
            }
        }

        test("removes the pending recording of a demo once a faster demo of its player on its map arrives") {
            val recordings = createTempDirectory(root, "recordings")
            val gate = CompletableDeferred<Unit>()
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, ServerConfig(ConfigReader.EMPTY, directories), clock)
                val recorder = recorder { demo, _ -> done(demo).also { gate.await() } }
                demos += listOf(demo("busy", 3), demo("slower", 0).copy(userId = "1", capTime = 12.seconds))
                coroutineScope {
                    val job = launch { RecordingService(demos, repository, listOf(recorder), config(recordings)).start() }
                    eventually(5.seconds) { repository["slower"]?.status shouldBe RecordingStatus.Pending }
                    demos += listOf(demo("faster", 1).copy(userId = "1", capTime = 10.seconds))
                    eventually(5.seconds) { repository["slower"] shouldBe null }
                    gate.complete(Unit)
                    eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("busy.mp4", "faster.mp4") }
                    job.cancelAndJoin()
                }
            }
        }

        test("sets a beaten recorded, uploaded or archived recording to outdated once the faster one is recorded") {
            val recordings = createTempDirectory(root, "recordings")
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, ServerConfig(ConfigReader.EMPTY, directories), clock)
                repository +=
                    listOf(
                        demo("recorded", 0).copy(userId = "1", capTime = 12.seconds),
                        demo("uploaded", 1).copy(userId = "2", capTime = 12.seconds),
                        demo("archived", 2).copy(userId = "3", capTime = 12.seconds),
                    )
                repository.finish("recorded", Recorded)
                repository.update("uploaded") { it.copy(status = Uploaded, md5 = "uploaded-md5") }
                repository.update("archived") { it.copy(status = Archived, md5 = "archived-md5", views = 2) }
                demos +=
                    listOf(
                        demo("faster-recorded", 3).copy(userId = "1", capTime = 10.seconds),
                        demo("faster-uploaded", 4).copy(userId = "2", capTime = 10.seconds),
                        demo("faster-archived", 5).copy(userId = "3", capTime = 10.seconds),
                    )
                val recorder = recorder { demo, _ -> done(demo) }
                coroutineScope {
                    val job = launch { RecordingService(demos, repository, listOf(recorder), config(recordings)).start() }
                    eventually(5.seconds) {
                        repository["recorded"]?.status shouldBe Outdated
                        repository["uploaded"]?.status shouldBe Outdated
                        repository["archived"]?.status shouldBe Outdated
                    }
                    repository["faster-recorded"]?.status shouldBe Recorded
                    repository["uploaded"]?.md5 shouldBe "uploaded-md5"
                    repository["archived"]?.md5 shouldBe "archived-md5"
                    repository["archived"]?.views shouldBe 2
                    job.cancelAndJoin()
                }
            }
        }

        test("keeps a recorded recording while the faster demo of its player on its map is only pending or failed") {
            val recordings = createTempDirectory(root, "recordings")
            val gate = CompletableDeferred<Unit>()
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, ServerConfig(ConfigReader.EMPTY, directories), clock)
                repository +=
                    listOf(
                        demo("beaten-by-pending", 0).copy(userId = "1", capTime = 12.seconds),
                        demo("beaten-by-failed", 1).copy(userId = "2", capTime = 12.seconds),
                    )
                repository.finish("beaten-by-pending", Recorded)
                repository.finish("beaten-by-failed", Recorded)
                demos +=
                    listOf(
                        demo("pending", 2).copy(userId = "1", capTime = 10.seconds),
                        demo("busy", 3),
                        demo("failed", 4).copy(userId = "2", capTime = 10.seconds),
                    )
                val recorder =
                    recorder { demo, _ ->
                        when (demo.capId) {
                            "failed" -> RecorderResult.Failed(IllegalStateException("Capture failed"))
                            else -> done(demo).also { gate.await() }
                        }
                    }
                coroutineScope {
                    val job =
                        launch {
                            RecordingService(demos, repository, listOf(recorder), config(recordings, maxAttempts = 1)).start()
                        }
                    eventually(5.seconds) { repository["busy"]?.status shouldBe RecordingStatus.Recording }
                    repository["failed"]?.status shouldBe Failed
                    repository["pending"]?.status shouldBe Pending
                    repository["beaten-by-pending"]?.status shouldBe Recorded
                    repository["beaten-by-failed"]?.status shouldBe Recorded
                    gate.complete(Unit)
                    job.cancelAndJoin()
                }
            }
        }

        test("sets a beaten recording to outdated once its capture ends") {
            val recordings = createTempDirectory(root, "recordings")
            val gate = CompletableDeferred<Unit>()
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, ServerConfig(ConfigReader.EMPTY, directories), clock)
                val record: suspend (Demo, ProgressListener<RecorderStage>) -> RecorderResult = { demo, _ ->
                    if (demo.capId == "slower") gate.await()
                    done(demo)
                }
                demos += listOf(demo("slower", 0).copy(userId = "1", capTime = 12.seconds))
                coroutineScope {
                    val job =
                        launch {
                            RecordingService(demos, repository, listOf(recorder(record), recorder(record)), config(recordings))
                                .start()
                        }
                    eventually(5.seconds) { repository["slower"]?.status shouldBe RecordingStatus.Recording }
                    demos += listOf(demo("faster", 1).copy(userId = "1", capTime = 10.seconds))
                    eventually(5.seconds) { repository["faster"]?.status shouldBe Recorded }
                    repository["slower"]?.status shouldBe RecordingStatus.Recording
                    gate.complete(Unit)
                    eventually(5.seconds) { repository["slower"]?.status shouldBe Outdated }
                    recordings.resolve("slower.mp4").exists() shouldBe true
                    job.cancelAndJoin()
                }
            }
        }

        test("records recording, failed and unresolvable recordings again on start, and keeps the rest") {
            val recordings = createTempDirectory(root, "recordings")
            val recorded = CopyOnWriteArrayList<String>()
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, ServerConfig(ConfigReader.EMPTY, directories), clock)
                repository += listOf(demo("recording", 0))
                repository.take()
                repository += listOf(demo("failed", 1), demo("recorded", 2), demo("unresolvable", 3))
                repository.finish("failed", Failed, "Capture failed")
                repository.finish("recorded", Recorded)
                repository.finish("unresolvable", Unresolvable, "Missing BTPog")
                val recorder = recorder { demo, _ -> done(demo).also { recorded += demo.capId } }
                coroutineScope {
                    val job = launch { RecordingService(demos, repository, listOf(recorder), config(recordings)).start() }
                    eventually(5.seconds) { recorded shouldContainExactlyInAnyOrder listOf("recording", "failed", "unresolvable") }
                    job.cancelAndJoin()
                }
            }
        }

        test("rejects an empty list of recorders") {
            shouldThrow<IllegalArgumentException> {
                SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                    val demos = DemoRepository(store)
                    RecordingService(
                        demos,
                        RecordingRepository(store, demos, ServerConfig(ConfigReader.EMPTY, directories), clock),
                        emptyList(),
                        config(root),
                    )
                }
            }
        }
    })
