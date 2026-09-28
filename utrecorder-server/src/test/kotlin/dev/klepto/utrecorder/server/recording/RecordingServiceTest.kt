package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoFetcher
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
            capTime: Duration = 10.seconds,
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = capTime,
                timestamp = Instant.parse("2026-09-26T12:00:00Z") + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        fun config(
            recordings: Path,
            maxAttempts: Int = 3,
            cooldown: Duration = 20.milliseconds,
        ): ServerConfig = ServerConfig(ConfigReader.EMPTY, recordings = recordings, maxAttempts = maxAttempts, cooldown = cooldown)

        fun fetcher(vararg demos: Demo): DemoFetcher =
            object : DemoFetcher {
                override suspend fun fetch(): Flow<Demo> =
                    flow {
                        demos.forEach { emit(it) }
                        awaitCancellation()
                    }
            }

        fun recorder(record: suspend (Demo, ProgressListener<RecorderStage>) -> CaptureResult): CaptureProvider =
            object : CaptureProvider {
                override suspend fun record(
                    demo: Demo,
                    onProgress: ProgressListener<RecorderStage>,
                ): CaptureResult = record(demo, onProgress)
            }

        fun done(demo: Demo): CaptureResult {
            val mp4 =
                createTempDirectory(root, "workspace")
                    .resolve("${demo.capId}.mp4")
                    .also { it.writeText(demo.capId) }
            return CaptureResult.Done(demo, mp4)
        }

        fun names(directory: Path): List<String> = directory.listDirectoryEntries().map { it.name }

        suspend fun running(
            service: RecordingService,
            block: suspend () -> Unit,
        ) {
            coroutineScope {
                val job = launch { service.start() }
                block()
                job.cancelAndJoin()
            }
        }

        test("records a demo up to the long threshold on a primary recorder and a longer one on a secondary recorder") {
            val recordings = createTempDirectory(root, "recordings")
            val primary = CopyOnWriteArrayList<String>()
            val secondary = CopyOnWriteArrayList<String>()
            val service =
                RecordingService(
                    fetcher(demo("short", 1, 5.minutes), demo("long", 0, 301.seconds)),
                    listOf(recorder { demo, _ -> done(demo).also { primary += demo.capId } }),
                    listOf(recorder { demo, _ -> done(demo).also { secondary += demo.capId } }),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("short.mp4", "long.mp4") }
            }

            primary shouldContainExactly listOf("short")
            secondary shouldContainExactly listOf("long")
        }

        test("moves the mp4 into the recordings directory and deletes its workspace") {
            val recordings = createTempDirectory(root, "recordings")
            val mp4s = CopyOnWriteArrayList<Path>()
            val service =
                RecordingService(
                    fetcher(demo("cap", 0)),
                    listOf(
                        recorder { demo, _ -> done(demo).also { mp4s.add((it as CaptureResult.Done).mp4) } },
                    ),
                    listOf(recorder { _, _ -> error("No long demo") }),
                    config(recordings),
                )

            running(service) {
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
            val service =
                RecordingService(
                    fetcher(demo("known", 1), demo("cap", 0)),
                    listOf(
                        recorder { demo, _ -> done(demo).also { recorded += demo.capId } },
                    ),
                    listOf(recorder { _, _ -> error("No long demo") }),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { recordings.resolve("cap.mp4").exists() shouldBe true }
            }

            recorded shouldContainExactly listOf("cap")
        }

        test("records one demo at a time per recorder, once each, and waits the cooldown after each") {
            val recordings = createTempDirectory(root, "recordings")
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val starts = CopyOnWriteArrayList<TimeSource.Monotonic.ValueTimeMark>()
            val recorded = CopyOnWriteArrayList<String>()
            val service =
                RecordingService(
                    fetcher(demo("newer", 1), demo("older", 0)),
                    listOf(
                        recorder { demo, _ ->
                            starts += TimeSource.Monotonic.markNow()
                            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                            delay(50.milliseconds)
                            active.decrementAndGet()
                            done(demo).also { recorded += demo.capId }
                        },
                    ),
                    listOf(recorder { _, _ -> error("No long demo") }),
                    config(recordings, cooldown = 200.milliseconds),
                )

            running(service) {
                eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("older.mp4", "newer.mp4") }
                delay(500.milliseconds)
            }

            recorded shouldContainExactly listOf("newer", "older")
            maxActive.get() shouldBe 1
            starts[1] - starts[0] shouldBeGreaterThanOrEqualTo 250.milliseconds
        }

        test("records on two primary recorders at once") {
            val recordings = createTempDirectory(root, "recordings")
            val started = CopyOnWriteArrayList<String>()
            val gate = CompletableDeferred<Unit>()
            val record: suspend (Demo, ProgressListener<RecorderStage>) -> CaptureResult = { demo, _ ->
                started += demo.capId
                gate.await()
                done(demo)
            }
            val service =
                RecordingService(
                    fetcher(demo("first", 0), demo("second", 1)),
                    listOf(recorder(record), recorder(record)),
                    listOf(recorder { _, _ -> error("No long demo") }),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { started shouldContainExactlyInAnyOrder listOf("first", "second") }
                gate.complete(Unit)
                eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("first.mp4", "second.mp4") }
            }
        }

        test("records a short demo while long demos wait for the busy secondary recorder") {
            val recordings = createTempDirectory(root, "recordings")
            val secondary = CopyOnWriteArrayList<String>()
            val gate = CompletableDeferred<Unit>()
            val service =
                RecordingService(
                    fetcher(demo("long-newest", 2, 10.minutes), demo("long-newer", 1, 10.minutes), demo("short", 0)),
                    listOf(recorder { demo, _ -> done(demo) }),
                    listOf(
                        recorder { demo, _ ->
                            secondary += demo.capId
                            gate.await()
                            done(demo)
                        },
                    ),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { names(recordings) shouldContainExactly listOf("short.mp4") }
                secondary shouldContainExactly listOf("long-newest")
                gate.complete(Unit)
                eventually(5.seconds) { names(recordings).size shouldBe 3 }
            }

            secondary shouldContainExactly listOf("long-newest", "long-newer")
        }

        test("gives up on a demo after the maximum number of attempts") {
            val recordings = createTempDirectory(root, "recordings")
            val attempts = AtomicInteger()
            val service =
                RecordingService(
                    fetcher(demo("broken", 1), demo("cap", 0)),
                    listOf(
                        recorder { demo, _ ->
                            if (demo.capId == "cap") return@recorder done(demo)
                            attempts.incrementAndGet()
                            CaptureResult.Failed(demo, IllegalStateException("Capture failed"))
                        },
                    ),
                    listOf(recorder { _, _ -> error("No long demo") }),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { names(recordings) shouldContainExactly listOf("cap.mp4") }
            }

            attempts.get() shouldBe 3
        }

        test("gives up on a demo at once when a package resolves nowhere") {
            val recordings = createTempDirectory(root, "recordings")
            val attempts = AtomicInteger()
            val service =
                RecordingService(
                    fetcher(demo("unresolvable", 1), demo("cap", 0)),
                    listOf(
                        recorder { demo, _ ->
                            if (demo.capId == "cap") return@recorder done(demo)
                            attempts.incrementAndGet()
                            CaptureResult.Unresolvable(
                                demo,
                                UtPackageException("BTPog", listOf("http://packages.example")),
                            )
                        },
                    ),
                    listOf(recorder { _, _ -> error("No long demo") }),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { names(recordings) shouldContainExactly listOf("cap.mp4") }
            }

            attempts.get() shouldBe 1
        }

        test("records short and long demos on the primary recorders when there is no secondary recorder") {
            val recordings = createTempDirectory(root, "recordings")
            val service =
                RecordingService(
                    fetcher(demo("short", 1), demo("long", 0, 10.minutes)),
                    listOf(recorder { demo, _ -> done(demo) }),
                    emptyList(),
                    config(recordings),
                )

            running(service) {
                eventually(5.seconds) { names(recordings) shouldContainExactlyInAnyOrder listOf("short.mp4", "long.mp4") }
            }
        }

        test("tracks a recording in jobs with its attempt and last progress until it ends") {
            val recordings = createTempDirectory(root, "recordings")
            val gate = CompletableDeferred<Unit>()
            val jobs = RecordingJobs()
            val service =
                RecordingService(
                    fetcher(demo("cap", 0)),
                    listOf(
                        recorder { demo, onProgress ->
                            if (!gate.isCompleted) {
                                onProgress(Progress(FetchingDemo, 0.5))
                                gate.await()
                                return@recorder CaptureResult.Failed(demo, IllegalStateException("Capture failed"))
                            }
                            done(demo)
                        },
                    ),
                    emptyList(),
                    config(recordings),
                    jobs,
                )

            running(service) {
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

        test("rejects an empty list of primary recorders") {
            shouldThrow<IllegalArgumentException> {
                RecordingService(fetcher(), emptyList(), listOf(recorder { demo, _ -> done(demo) }), config(root))
            }
        }
    })
