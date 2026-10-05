package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.core.capture.video.VideoStream
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
            val workspace = createTempDirectory(root, "workspace")
            val mp4 = workspace.resolve("${demo.capId}.mp4").also { it.writeText(demo.capId) }
            val thumbnail = workspace.resolve("${demo.capId}.jpg").also { it.writeText("${demo.capId}.jpg") }
            return RecorderResult.Done(mp4, thumbnail, VideoStream(1920, 1080, 60))
        }

        fun names(directory: Path): List<String> = directory.listDirectoryEntries().map { it.name }

        suspend fun running(
            demos: List<Demo>,
            service: (RecordingRepository, RecordingQueue) -> RecordingService,
            block: suspend (RecordingRepository) -> Unit,
        ) {
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demoRepository = DemoRepository(store)
                val repository = RecordingRepository(store, demoRepository, clock)
                val queue = RecordingQueue(demoRepository, repository, ServerConfig(ConfigReader.EMPTY, directories), clock)
                demoRepository += demos
                coroutineScope {
                    val services: List<Service> = listOf(queue, service(repository, queue))
                    val job = launch { services.forEach { launch { it.start() } } }
                    block(repository)
                    job.cancelAndJoin()
                }
            }
        }

        test("sets a stored recording to recorded with its time, the time its last attempt took, its mp4 size and its video") {
            val recordings = createTempDirectory(root, "recordings")
            val attempts = AtomicInteger()
            running(
                listOf(demo("cap", 0)),
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
                        listOf(
                            recorder { demo, _ ->
                                when (attempts.incrementAndGet()) {
                                    1 -> RecorderResult.Failed(IllegalStateException("Capture failed")).also { delay(400.milliseconds) }
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
                recording.processingTime!! shouldBeLessThan 400.milliseconds
                recording.size shouldBe 3
                recording.width shouldBe 1920
                recording.height shouldBe 1080
                recording.fps shouldBe 60
            }
        }

        test("moves the mp4 into the recordings directory, the thumbnail into the thumbnails directory, and deletes their workspace") {
            val recordings = createTempDirectory(root, "recordings")
            val thumbnails = createTempDirectory(root, "thumbnails")
            val mp4s = CopyOnWriteArrayList<Path>()
            running(
                listOf(demo("cap", 0)),
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
                        listOf(
                            recorder { demo, _ -> done(demo).also { mp4s.add((it as RecorderResult.Done).mp4) } },
                        ),
                        ServerConfig(ConfigReader.EMPTY, directories, recordings = recordings, thumbnails = thumbnails),
                    )
                },
            ) {
                eventually(5.seconds) { recordings.resolve("cap.mp4").readText() shouldBe "cap" }
            }

            thumbnails.resolve("cap.jpg").readText() shouldBe "cap.jpg"

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
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
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
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
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
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
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
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
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
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
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
                { repository, queue ->
                    RecordingService(
                        repository,
                        queue,
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

        test("does not record a recording claimed elsewhere between the head of the queue and its claim") {
            val recordings = createTempDirectory(root, "recordings")
            val recorded = CopyOnWriteArrayList<String>()
            val claimElsewhere = AtomicReference<(() -> Unit)?>()
            val queueClock =
                object : Clock {
                    override fun now(): Instant {
                        claimElsewhere.getAndSet(null)?.invoke()
                        return clock.now()
                    }
                }
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val demos = DemoRepository(store)
                val repository = RecordingRepository(store, demos, clock)
                val queue = RecordingQueue(demos, repository, ServerConfig(ConfigReader.EMPTY, directories), queueClock)
                repository += listOf(demo("cap", 0))
                val recorder = recorder { demo, _ -> done(demo).also { recorded += demo.capId } }
                coroutineScope {
                    claimElsewhere.set { launch(start = CoroutineStart.UNDISPATCHED) { repository.claim("cap") } }
                    val services: List<Service> = listOf(queue, RecordingService(repository, queue, listOf(recorder), config(recordings)))
                    val job = launch { services.forEach { launch { it.start() } } }
                    eventually(5.seconds) { claimElsewhere.get() shouldBe null }
                    delay(200.milliseconds)
                    repository["cap"]?.status shouldBe RecordingStatus.Recording
                    job.cancelAndJoin()
                }
            }

            recorded.shouldBeEmpty()
        }

        test("rejects an empty list of recorders") {
            shouldThrow<IllegalArgumentException> {
                SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                    val demos = DemoRepository(store)
                    val repository = RecordingRepository(store, demos, clock)
                    RecordingService(
                        repository,
                        RecordingQueue(demos, repository, ServerConfig(ConfigReader.EMPTY, directories), clock),
                        emptyList(),
                        config(root),
                    )
                }
            }
        }
    })
