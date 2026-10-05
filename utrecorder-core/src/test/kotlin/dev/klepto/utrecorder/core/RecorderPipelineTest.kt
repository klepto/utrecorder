package dev.klepto.utrecorder.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.capture.audio.AudioCapture
import dev.klepto.utrecorder.core.capture.audio.AudioCaptureConfig
import dev.klepto.utrecorder.core.capture.audio.AudioTrack
import dev.klepto.utrecorder.core.capture.video.VideoCapture
import dev.klepto.utrecorder.core.capture.video.VideoCaptureConfig
import dev.klepto.utrecorder.core.capture.video.VideoFormat
import dev.klepto.utrecorder.core.capture.video.VideoStream
import dev.klepto.utrecorder.core.capture.video.VideoTrack
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.core.media.Muxer
import dev.klepto.utrecorder.core.media.Thumbnailer
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
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Path
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

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
            private val format: VideoFormat = VideoFormat(1280, 720, 4_000_000, "slow", true),
        ) : VideoCapture {
            override suspend fun record(
                demo: Path,
                onProgress: ProgressListener<Unit>,
            ): VideoTrack {
                audioThreadStarted?.await()
                onProgress(Progress(0.5))
                events += videoCapturePlayed
                audioThreadReleased?.countDown()
                return VideoTrack(file = videoFile, length = 8.0, format = format, demoStart = 292.75)
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
                onProgress: ProgressListener<Unit>,
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
            private val size: Int = 1,
        ) : Muxer {
            override suspend fun merge(
                video: Path,
                audio: Path,
                offset: Double,
                length: Double,
                target: Path,
                onProgress: ProgressListener<Unit>,
            ): Path {
                events += muxRan
                target.writeBytes(ByteArray(size))
                return target
            }
        }

        class FakeThumbnailer : Thumbnailer {
            override suspend fun extract(
                video: Path,
                time: Double,
                target: Path,
            ): Path = target.also { it.writeText("jpg") }
        }

        fun track(file: String) =
            AudioTrack(
                file = Path.of(file),
                offset = 2.0,
                length = 12.0,
                demoStart = 288.75,
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
                thumbnailer = FakeThumbnailer(),
            )

        class FakeMediaTool(
            private val length: Double,
        ) : MediaTool {
            override suspend fun run(
                vararg arguments: String,
                input: (suspend (OutputStream) -> Unit)?,
                onProgress: ((MediaProgress) -> Unit)?,
            ) = Unit

            override suspend fun duration(file: Path): Double = length

            override suspend fun frames(file: Path): Int = 0
        }

        fun lengthCheckedPipeline(length: Double): RecorderPipeline =
            RecorderPipeline(
                VideoCaptureConfig(ConfigReader.EMPTY),
                AudioCaptureConfig(ConfigReader.EMPTY),
                installation,
                directories,
                FakeMediaTool(length),
                FakeVideoCapture(ConcurrentLinkedQueue(), null, null),
                FakeAudioCapture(ConcurrentLinkedQueue(), null, null, track("audio.wav"), null),
                FakeMuxer(ConcurrentLinkedQueue()),
                FakeThumbnailer(),
            )

        fun demoIn(recording: Workspace): Path = recording["demo.dem"].also { it.writeBytes(byteArrayOf(1)) }

        suspend fun warnings(
            size: Int,
            targetSize: Long,
            fitsBudget: Boolean,
        ): Pair<Path, List<String>> {
            val logger = LoggerFactory.getLogger(RecorderPipeline::class.java.name) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            try {
                val format = VideoFormat(1280, 720, 4_000_000, "slow", fitsBudget)
                val oversizedPipeline =
                    RecorderPipeline(
                        VideoCaptureConfig(ConfigReader.EMPTY, targetSize = targetSize),
                        AudioCaptureConfig(ConfigReader.EMPTY),
                        installation,
                        directories,
                        videoCapture = FakeVideoCapture(ConcurrentLinkedQueue(), null, null, format),
                        audioCapture = FakeAudioCapture(ConcurrentLinkedQueue(), null, null, track("audio.wav"), null),
                        muxer = FakeMuxer(ConcurrentLinkedQueue(), size),
                        thumbnailer = FakeThumbnailer(),
                    )
                val produced =
                    workspace("record", config = directories) { recording ->
                        oversizedPipeline.record(demoIn(recording), "demo-file").mp4
                    }
                return produced to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            } finally {
                logger.detachAppender(appender)
            }
        }

        test("record warns of the size against the target when the budget chose the format and the mp4 is over the target") {
            val (produced, warned) = warnings(size = 3000, targetSize = 2000, fitsBudget = true)

            produced.fileName.toString() shouldBe "demo-file.mp4"
            produced.resolveSibling("demo-file.jpg").exists() shouldBe true
            warned.single() shouldContain "demo-file.mp4"
            warned.single() shouldContain "3000 bytes"
            warned.single() shouldContain "2000 bytes"
        }

        test("record logs no warning for an mp4 over the target when the fallback chose the format") {
            warnings(size = 3000, targetSize = 2000, fitsBudget = false).second shouldBe emptyList()
        }

        test("record logs no warning for an mp4 at the target") {
            warnings(size = 2000, targetSize = 2000, fitsBudget = true).second shouldBe emptyList()
        }

        test("record logs no warning for an mp4 under the target") {
            warnings(size = 1000, targetSize = 2000, fitsBudget = true).second shouldBe emptyList()
        }

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("record returns <name>.mp4 beside <name>.jpg in the workspace of the run and keeps the audio file of the audio track") {
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
                        .mp4
                }

            produced.fileName.toString() shouldBe "demo-file.mp4"
            produced.parent shouldBe directories.workspace.listDirectoryEntries().single()
            produced.exists() shouldBe true
            produced.resolveSibling("demo-file.jpg").exists() shouldBe true
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

        test("record returns the mp4 when it runs up to 2 seconds longer than the run") {
            val produced =
                workspace("record", config = directories) { recording ->
                    lengthCheckedPipeline(24.533).record(demoIn(recording), "demo-file", 23.842.seconds).mp4
                }

            produced.fileName.toString() shouldBe "demo-file.mp4"
        }

        test("record fails when the mp4 runs shorter than the run") {
            val failure =
                workspace("record", config = directories) { recording ->
                    shouldThrow<IllegalStateException> {
                        lengthCheckedPipeline(12.0).record(demoIn(recording), "demo-file", 23.842.seconds)
                    }
                }

            failure.message shouldBe "The mp4 runs 12.000 seconds, but the run takes 23.842 seconds"
        }

        test("record fails when the mp4 runs more than 2 seconds longer than the run") {
            val failure =
                workspace("record", config = directories) { recording ->
                    shouldThrow<IllegalStateException> {
                        lengthCheckedPipeline(25.9).record(demoIn(recording), "demo-file", 23.842.seconds)
                    }
                }

            failure.message shouldBe "The mp4 runs 25.900 seconds, but the run takes 23.842 seconds"
        }

        test("record hands the mux the track anchored on the start of the video, nudged by the offset") {
            var merged: Triple<Path, Double, Double>? = null
            val capturingMuxer =
                object : Muxer {
                    override suspend fun merge(
                        video: Path,
                        audio: Path,
                        offset: Double,
                        length: Double,
                        target: Path,
                        onProgress: ProgressListener<Unit>,
                    ): Path {
                        merged = Triple(audio, offset, length)
                        target.writeBytes(byteArrayOf(1))
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
                    thumbnailer = FakeThumbnailer(),
                )

            workspace("record", config = directories) { recording ->
                nudgedPipeline.record(demoIn(recording), "demo-file")
            }

            merged shouldBe Triple(Path.of("audio.wav"), 6.5, 8.0)
        }

        test("record takes the thumbnail of the mp4 one second into it") {
            var extracted: Triple<Path, Double, Path>? = null
            val capturingThumbnailer =
                object : Thumbnailer {
                    override suspend fun extract(
                        video: Path,
                        time: Double,
                        target: Path,
                    ): Path {
                        extracted = Triple(video, time, target)
                        return target
                    }
                }
            val thumbnailedPipeline =
                RecorderPipeline(
                    VideoCaptureConfig(ConfigReader.EMPTY),
                    AudioCaptureConfig(ConfigReader.EMPTY),
                    installation,
                    directories,
                    videoCapture = FakeVideoCapture(ConcurrentLinkedQueue(), null, null),
                    audioCapture = FakeAudioCapture(ConcurrentLinkedQueue(), null, null, track("audio.wav"), null),
                    muxer = FakeMuxer(ConcurrentLinkedQueue()),
                    thumbnailer = capturingThumbnailer,
                )

            val produced =
                workspace("record", config = directories) { recording ->
                    thumbnailedPipeline.record(demoIn(recording), "demo-file").mp4
                }

            extracted shouldBe Triple(produced, 1.0, produced.resolveSibling("demo-file.jpg"))
        }

        test("record returns the frame size of the format of the video track with the configured frame rate") {
            val output =
                workspace("record", config = directories) { recording ->
                    pipeline(ConcurrentLinkedQueue(), track("audio.wav")).record(demoIn(recording), "demo-file")
                }

            output.video shouldBe VideoStream(1280, 720, 60)
        }

        test("record reports the capture with the lesser progress of the two captures, then the mux") {
            val reports = ConcurrentLinkedQueue<Progress<RecorderStage>>()

            workspace("record", config = directories) { recording ->
                pipeline(ConcurrentLinkedQueue(), track("audio.wav")).record(demoIn(recording), "demo-file") { reports += it }
            }

            reports.toList() shouldBe
                listOf(
                    Progress(RecorderStage.Capturing, 0.0),
                    Progress(RecorderStage.Capturing, 0.0),
                    Progress(RecorderStage.Capturing, 1.0),
                    Progress(RecorderStage.Muxing, 0.0),
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
                        onProgress: ProgressListener<Unit>,
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
                        onProgress: ProgressListener<Unit>,
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
