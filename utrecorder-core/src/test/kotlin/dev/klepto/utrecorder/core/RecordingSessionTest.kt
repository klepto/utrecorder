package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class RecordingSessionTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-session-test")
        val installationDirectory = home / "install"
        val gameExecutable = UtPlatform.current().gameExecutable
        installationDirectory.resolve("System").createDirectories()
        installationDirectory.resolve("System/$gameExecutable").writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(DirectoriesConfig(home)))
        val directories = DirectoriesConfig(home)
        val frameLog =
            listOf(
                "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 6 0 1 1.5",
                "ScriptLog: UTRecorder: mark end 2026 9 13 10 38 46 0 18046 300.75",
            )
        val soundThreadLaunched = "sound-thread-launched"
        val framePassPlayed = "frame-pass-played"
        val soundPassPlayed = "sound-pass-played"
        val soundThreadJoined = "sound-thread-joined"
        val captureStopping = "capture-stopping"
        val muxRan = "mux-ran"

        class EventLog {
            private val events: Queue<String> = ConcurrentLinkedQueue()

            fun record(event: String) {
                events.add(event)
            }

            fun hold(): List<String> = events.toList()
        }

        class FakeFramePass(
            private val events: EventLog,
            private val soundThreadStarted: CountDownLatch?,
            private val soundThreadReleased: CountDownLatch?,
        ) : FramePassLike {
            override suspend fun record(
                demo: Path,
                recorder: FrameRecorder,
                workspace: Workspace,
                afterPlay: suspend () -> Unit,
            ): DemoMarks {
                val (marks, _) =
                    recorder.record {
                        soundThreadStarted?.await()
                        events.record(framePassPlayed)
                        soundThreadReleased?.countDown()
                        afterPlay()
                        DemoMarks.parse(frameLog)
                    }
                return marks
            }
        }

        class SoundPassHolding(
            private val events: EventLog,
            private val soundThreadStarted: CountDownLatch?,
            private val soundThreadReleased: CountDownLatch?,
            private val outcome: SoundTrack?,
            private val failure: String?,
        ) : SoundPassLike {
            override suspend fun record(
                demo: Path,
                workspace: Workspace,
            ): SoundTrack {
                events.record(soundThreadLaunched)
                soundThreadStarted?.countDown()
                if (soundThreadReleased != null) soundThreadReleased.await()
                events.record(soundPassPlayed)
                failure?.let { throw IllegalStateException(it) }
                return checkNotNull(outcome)
            }
        }

        class FakeMuxer(
            private val events: EventLog,
        ) : MuxerLike {
            override suspend fun merge(
                video: Path,
                track: SoundTrack,
                target: Path,
            ): Path {
                events.record(muxRan)
                video.copyTo(target, overwrite = true)
                return target
            }
        }

        fun track(file: String) =
            SoundTrack(
                file = Path.of(file),
                offset = 2.0,
                length = 12.0,
                demoEnd = 300.75,
                demoSpan = 288.25,
            )

        class CaptureStandIn(
            private val events: EventLog?,
        ) : FfmpegLike {
            override suspend fun capture(vararg arguments: String): Boolean =
                apply {
                    Path.of(arguments.last()).writeBytes(byteArrayOf(1))
                }.let { true }

            override suspend fun frames(file: Path): Int =
                events?.let {
                    it.record(soundThreadJoined)
                    it.record(captureStopping)
                    480
                } ?: 480

            override suspend fun duration(file: Path): Double = 8.0
        }

        fun session(
            events: EventLog,
            outcome: SoundTrack?,
            failure: String? = null,
            choreography: Pair<CountDownLatch, CountDownLatch>? = null,
        ): RecordingSession =
            RecordingSession(
                RecorderConfig(installationDirectory = installationDirectory),
                installation,
                directories,
                ffmpeg = CaptureStandIn(if (choreography == null) null else events),
                framePass = FakeFramePass(events, choreography?.first, choreography?.second),
                soundPass = SoundPassHolding(events, choreography?.first, choreography?.second, outcome, failure),
                muxer = FakeMuxer(events),
            )

        fun demoIn(recording: Workspace): Path = recording["demo.dem"].also { it.writeBytes(byteArrayOf(1)) }

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test(
            "a recording whose frame pass and sound track both succeed produces the mp4 at output/<job-id>/<name>.mp4 and keeps the wav",
        ) {
            val cacheAudio =
                (directories.cache / "audio.wav").also { file ->
                    file.parent.createDirectories()
                    file.writeText("wav")
                }
            val events = EventLog()

            val produced =
                workspace("record", config = directories) { recording ->
                    session(events, track(cacheAudio.toString())).record(demoIn(this), "demo-file", this)
                }

            produced.fileName.toString() shouldBe "demo-file.mp4"
            produced.parent shouldBe directories.workspace.listDirectoryEntries().single()
            produced.exists() shouldBe true
            cacheAudio.exists() shouldBe true
        }

        test("a sound pass that fails propagates as a failed recording, never a silent video-only mp4") {
            val events = EventLog()
            directories.workspace.toFile().deleteRecursively()

            val failure =
                workspace("record", config = directories) { recording ->
                    shouldThrow<IllegalStateException> {
                        session(events, null, "The sound pass produced no audio.wav")
                            .record(demoIn(this), "demo-file", this)
                    }
                }

            failure.message shouldContain "audio.wav"
            val kept = directories.workspace.listDirectoryEntries().single()
            kept.listDirectoryEntries().map { it.fileName.toString() } shouldNotContain "demo-file.mp4"
        }

        test("the sound pass starts before the recorder and joins before the capture stops") {
            val events = EventLog()
            val soundThreadStarted = CountDownLatch(1)
            val soundThreadReleased = CountDownLatch(1)

            workspace("record", config = directories) { recording ->
                session(events, track("audio.wav"), choreography = soundThreadStarted to soundThreadReleased)
                    .record(demoIn(this), "demo-file", this)
            }

            events.hold() shouldBe
                listOf(
                    soundThreadLaunched,
                    framePassPlayed,
                    soundPassPlayed,
                    soundThreadJoined,
                    captureStopping,
                    muxRan,
                )
        }

        test("every recording keeps its own workspace, whether it threw or returned") {
            directories.workspace.toFile().deleteRecursively()

            shouldThrow<IllegalStateException> {
                workspace("record", config = directories) { recording ->
                    session(EventLog(), null, "The sound pass logged no start mark")
                        .record(demoIn(this), "demo-file", this)
                }
            }
            val keptEntry = directories.workspace.listDirectoryEntries().single()
            keptEntry.listDirectoryEntries().isNotEmpty() shouldBe true

            workspace("record", config = directories) { recording ->
                session(EventLog(), track("audio.wav")).record(demoIn(this), "demo-file", this)
            }

            directories.workspace.listDirectoryEntries().size shouldBe 2
        }

        test("cancelling the coroutine running a recording stops both passes and keeps the workspace it wrote") {
            directories.workspace.toFile().deleteRecursively()
            val framePassStarted = CompletableDeferred<Unit>()
            val soundPassStarted = CompletableDeferred<Unit>()
            val framePassCancelled = CompletableDeferred<Unit>()
            val soundPassCancelled = CompletableDeferred<Unit>()

            val cancellingFramePass =
                object : FramePassLike {
                    override suspend fun record(
                        demo: Path,
                        recorder: FrameRecorder,
                        workspace: Workspace,
                        afterPlay: suspend () -> Unit,
                    ): DemoMarks =
                        try {
                            framePassStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            framePassCancelled.complete(Unit)
                        }
                }
            val cancellingSoundPass =
                object : SoundPassLike {
                    override suspend fun record(
                        demo: Path,
                        workspace: Workspace,
                    ): SoundTrack =
                        try {
                            soundPassStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            soundPassCancelled.complete(Unit)
                        }
                }

            val cancelledSession =
                RecordingSession(
                    RecorderConfig(installationDirectory = installationDirectory),
                    installation,
                    directories,
                    ffmpeg = CaptureStandIn(null),
                    framePass = cancellingFramePass,
                    soundPass = cancellingSoundPass,
                    muxer = FakeMuxer(EventLog()),
                )

            coroutineScope {
                val job =
                    launch {
                        workspace("record", config = directories) { recording ->
                            cancelledSession.record(demoIn(this), "demo-file", this)
                        }
                    }
                framePassStarted.await()
                soundPassStarted.await()
                job.cancelAndJoin()
            }

            framePassCancelled.isCompleted shouldBe true
            soundPassCancelled.isCompleted shouldBe true
            directories.workspace.listDirectoryEntries().size shouldBe 1
        }
    })
