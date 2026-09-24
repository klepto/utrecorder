package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.capture.audio.AudioCapture
import dev.klepto.utrecorder.core.capture.audio.AudioCaptureConfig
import dev.klepto.utrecorder.core.capture.audio.AudioTrack
import dev.klepto.utrecorder.core.capture.video.VideoCapture
import dev.klepto.utrecorder.core.capture.video.VideoCaptureConfig
import dev.klepto.utrecorder.core.capture.video.VideoTrack
import dev.klepto.utrecorder.core.media.Muxer
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

class RecorderPipelineTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-pipeline-test")
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)
        val installationDirectory = home / "install"
        (installationDirectory / "System").createDirectories()
        (installationDirectory / "System" / UtPlatform.current().gameExecutable).writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(ConfigReader.EMPTY, directories))
        val videoFile = (home / "video.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val audioThreadLaunched = "audio-thread-launched"
        val videoCapturePlayed = "video-capture-played"
        val audioCapturePlayed = "audio-capture-played"
        val muxRan = "mux-ran"

        class FakeVideoCapture(
            private val events: Queue<String>,
            private val audioThreadStarted: CountDownLatch?,
            private val audioThreadReleased: CountDownLatch?,
        ) : VideoCapture {
            override suspend fun record(
                demo: Path,
                onProgress: ProgressListener,
            ): VideoTrack {
                audioThreadStarted?.await()
                onProgress(0.5)
                events += videoCapturePlayed
                audioThreadReleased?.countDown()
                return VideoTrack(file = videoFile, length = 8.0, demoEnd = 300.75)
            }
        }

        class FakeAudioCapture(
            private val events: Queue<String>,
            private val audioThreadStarted: CountDownLatch?,
            private val audioThreadReleased: CountDownLatch?,
            private val outcome: AudioTrack?,
            private val failure: String?,
        ) : AudioCapture {
            override suspend fun record(
                demo: Path,
                onProgress: ProgressListener,
            ): AudioTrack {
                events += audioThreadLaunched
                audioThreadStarted?.countDown()
                audioThreadReleased?.await()
                events += audioCapturePlayed
                return outcome ?: throw IllegalStateException(failure)
            }
        }

        class FakeMuxer(
            private val events: Queue<String>,
        ) : Muxer {
            override suspend fun merge(
                video: Path,
                audio: Path,
                offset: Double,
                length: Double,
                target: Path,
                onProgress: ProgressListener,
            ): Path {
                events += muxRan
                video.copyTo(target, overwrite = true)
                return target
            }
        }

        fun track(file: String) =
            AudioTrack(
                file = Path.of(file),
                offset = 2.0,
                length = 12.0,
                demoEnd = 300.75,
            )

        fun pipeline(
            events: Queue<String>,
            outcome: AudioTrack?,
            failure: String? = null,
            choreography: Pair<CountDownLatch, CountDownLatch>? = null,
        ): RecorderPipeline =
            RecorderPipeline(
                VideoCaptureConfig(ConfigReader.EMPTY),
                AudioCaptureConfig(ConfigReader.EMPTY),
                installation,
                directories,
                videoCapture = FakeVideoCapture(events, choreography?.first, choreography?.second),
                audioCapture = FakeAudioCapture(events, choreography?.first, choreography?.second, outcome, failure),
                muxer = FakeMuxer(events),
            )

        fun demoIn(recording: Workspace): Path = recording["demo.dem"].also { it.writeBytes(byteArrayOf(1)) }

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("record returns <name>.mp4 in the workspace of the run and keeps the audio file of the audio track") {
            directories.workspace.toFile().deleteRecursively()
            val cacheAudio =
                (directories.cache / "audio.wav").also { file ->
                    file.parent.createDirectories()
                    file.writeText("wav")
                }

            val produced =
                workspace("record", config = directories) { recording ->
                    pipeline(ConcurrentLinkedQueue(), track(cacheAudio.toString()))
                        .record(demoIn(recording), "demo-file")
                }

            produced.fileName.toString() shouldBe "demo-file.mp4"
            produced.parent shouldBe directories.workspace.listDirectoryEntries().single()
            produced.exists() shouldBe true
            cacheAudio.exists() shouldBe true
        }

        test("record rethrows a failed audio capture and writes no video-only mp4 to the workspace it keeps") {
            directories.workspace.toFile().deleteRecursively()

            val failure =
                workspace("record", config = directories) { recording ->
                    shouldThrow<IllegalStateException> {
                        pipeline(ConcurrentLinkedQueue(), null, "The audio capture produced no audio.wav")
                            .record(demoIn(recording), "demo-file")
                    }
                }

            failure.message shouldContain "audio.wav"
            val kept = directories.workspace.listDirectoryEntries().single()
            kept.listDirectoryEntries().map { it.fileName.toString() } shouldNotContain "demo-file.mp4"
        }

        test("record hands the mux the track anchored on the end of the 8 second video and nudged by the sound offset") {
            var merged: Triple<Path, Double, Double>? = null
            val capturingMuxer =
                object : Muxer {
                    override suspend fun merge(
                        video: Path,
                        audio: Path,
                        offset: Double,
                        length: Double,
                        target: Path,
                        onProgress: ProgressListener,
                    ): Path {
                        merged = Triple(audio, offset, length)
                        return target
                    }
                }
            val nudgedPipeline =
                RecorderPipeline(
                    VideoCaptureConfig(ConfigReader.EMPTY),
                    AudioCaptureConfig(ConfigReader.EMPTY, offset = 0.5),
                    installation,
                    directories,
                    videoCapture = FakeVideoCapture(ConcurrentLinkedQueue(), null, null),
                    audioCapture = FakeAudioCapture(ConcurrentLinkedQueue(), null, null, track("audio.wav"), null),
                    muxer = capturingMuxer,
                )

            workspace("record", config = directories) { recording ->
                nudgedPipeline.record(demoIn(recording), "demo-file")
            }

            merged shouldBe Triple(Path.of("audio.wav"), 6.5, 8.0)
        }

        test("record reports the capture with the lesser progress of the two captures, then the mux") {
            val reports = ConcurrentLinkedQueue<RecorderProgress>()

            workspace("record", config = directories) { recording ->
                pipeline(ConcurrentLinkedQueue(), track("audio.wav")).record(demoIn(recording), "demo-file") { reports += it }
            }

            reports.toList() shouldBe
                listOf(
                    RecorderProgress(RecorderStage.Capturing, 0.0),
                    RecorderProgress(RecorderStage.Capturing, 0.0),
                    RecorderProgress(RecorderStage.Capturing, 1.0),
                    RecorderProgress(RecorderStage.Muxing, 0.0),
                )
        }

        test("record starts the audio capture before the video capture plays and muxes once both have finished") {
            val events = ConcurrentLinkedQueue<String>()
            val audioThreadStarted = CountDownLatch(1)
            val audioThreadReleased = CountDownLatch(1)

            workspace("record", config = directories) { recording ->
                pipeline(events, track("audio.wav"), choreography = audioThreadStarted to audioThreadReleased)
                    .record(demoIn(recording), "demo-file")
            }

            events.toList() shouldBe
                listOf(
                    audioThreadLaunched,
                    videoCapturePlayed,
                    audioCapturePlayed,
                    muxRan,
                )
        }

        test("cancelling record cancels both captures and keeps the workspace of the run") {
            directories.workspace.toFile().deleteRecursively()
            val videoCaptureStarted = CompletableDeferred<Unit>()
            val audioCaptureStarted = CompletableDeferred<Unit>()
            val videoCaptureCancelled = CompletableDeferred<Unit>()
            val audioCaptureCancelled = CompletableDeferred<Unit>()

            val cancellingVideoCapture =
                object : VideoCapture {
                    override suspend fun record(
                        demo: Path,
                        onProgress: ProgressListener,
                    ): VideoTrack =
                        try {
                            videoCaptureStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            videoCaptureCancelled.complete(Unit)
                        }
                }
            val cancellingAudioCapture =
                object : AudioCapture {
                    override suspend fun record(
                        demo: Path,
                        onProgress: ProgressListener,
                    ): AudioTrack =
                        try {
                            audioCaptureStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            audioCaptureCancelled.complete(Unit)
                        }
                }

            val cancelledPipeline =
                RecorderPipeline(
                    VideoCaptureConfig(ConfigReader.EMPTY),
                    AudioCaptureConfig(ConfigReader.EMPTY),
                    installation,
                    directories,
                    videoCapture = cancellingVideoCapture,
                    audioCapture = cancellingAudioCapture,
                    muxer = FakeMuxer(ConcurrentLinkedQueue()),
                )

            coroutineScope {
                val job =
                    launch {
                        workspace("record", config = directories) { recording ->
                            cancelledPipeline.record(demoIn(recording), "demo-file")
                        }
                    }
                videoCaptureStarted.await()
                audioCaptureStarted.await()
                job.cancelAndJoin()
            }

            videoCaptureCancelled.isCompleted shouldBe true
            audioCaptureCancelled.isCompleted shouldBe true
            directories.workspace.listDirectoryEntries().size shouldBe 1
        }
    })
