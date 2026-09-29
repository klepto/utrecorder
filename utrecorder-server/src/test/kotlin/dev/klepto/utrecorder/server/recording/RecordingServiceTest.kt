package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
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

        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-27T08:00:00Z")
            }

        fun config(
            recordings: Path,
            maxAttempts: Int = 3,
            cooldown: Duration = 20.milliseconds,
        ): ServerConfig {
            val recording = RecordingConfig(ConfigReader.EMPTY, maxAttempts = maxAttempts, cooldown = cooldown)
            return ServerConfig(ConfigReader.EMPTY, recording = recording, recordings = recordings)
        }

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
            service: (RecordingRepository) -> RecordingService,
            block: suspend (RecordingRepository) -> Unit,
        ) {
            DocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val repository = RecordingRepository(store, RecordingConfig(ConfigReader.EMPTY), clock)
                repository += demos
                coroutineScope {
                    val job = launch { service(repository).start() }
                    block(repository)
                    job.cancelAndJoin()
                }
            }
        }

        test("moves the mp4 into the recordings directory and deletes its workspace") {
            val recordings = createTempDirectory(root, "recordings")
            val mp4s = CopyOnWriteArrayList<Path>()
            running(
                listOf(demo("cap", 0)),
                { repository ->
                    RecordingService(
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
                { repository ->
                    RecordingService(
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
                { repository ->
                    RecordingService(
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
                { repository ->
                    RecordingService(
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
                { repository ->
                    RecordingService(
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
                { repository ->
                    RecordingService(
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
                { repository ->
                    RecordingService(
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

        test("rejects an empty list of recorders") {
            shouldThrow<IllegalArgumentException> {
                DocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                    RecordingService(RecordingRepository(store, RecordingConfig(ConfigReader.EMPTY), clock), emptyList(), config(root))
                }
            }
        }
    })
