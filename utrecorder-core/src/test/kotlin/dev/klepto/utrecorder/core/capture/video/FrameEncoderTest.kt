package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FrameEncoderTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-framerecorder")
        val target = home / "video.mp4"

        fun videoConfig(
            svtav1Params: String = "tune=0",
            pixelFormat: String = "yuv420p",
            arguments: String = "",
            motionBlur: Boolean = true,
            motionBlurSamples: Int = 2,
            finishTimeout: Duration = 2.minutes,
        ): VideoCaptureConfig =
            VideoCaptureConfig(
                ConfigReader.EMPTY,
                port = 0,
                fps = 60,
                codec = "libsvtav1",
                svtav1Params = svtav1Params,
                pixelFormat = pixelFormat,
                gop = 300,
                queue = 32,
                arguments = arguments,
                motionBlur = motionBlur,
                motionBlurSamples = motionBlurSamples,
                finishTimeout = finishTimeout,
            )

        val defaultConfig = videoConfig()

        val defaultFormat = VideoFormat(width = 960, height = 540, bitrate = 4_800_000, preset = "4", fitsBudget = true)

        fun standIn(
            failure: String? = null,
            frames: Int = 0,
        ): MediaTool =
            object : MediaTool {
                override suspend fun run(
                    vararg arguments: String,
                    input: (suspend (OutputStream) -> Unit)?,
                    onProgress: ((MediaProgress) -> Unit)?,
                ) {
                    failure?.let { throw IllegalStateException(it) }
                }

                override suspend fun frames(file: Path): Int = frames

                override suspend fun duration(file: Path): Double = 0.0
            }

        fun encoder(
            config: VideoCaptureConfig = defaultConfig,
            format: VideoFormat = defaultFormat,
            captureTarget: Path = target,
            ffmpeg: MediaTool = standIn(),
            operatingSystem: OperatingSystem = OperatingSystem.current(),
            processors: Int = 6,
        ): FrameEncoder =
            FrameEncoder(config, format, 5.seconds, captureTarget, ffmpeg, operatingSystem = operatingSystem, processors = processors)

        fun arguments(
            config: VideoCaptureConfig = defaultConfig,
            format: VideoFormat = defaultFormat,
            processors: Int = 6,
        ): List<String> = encoder(config, format, processors = processors).captureArguments()

        test("captureArguments reads the frames from standard input") {
            val read = arguments()
            read.size shouldBe 43
            read[11] shouldBe "-i"
            read[12] shouldBe "pipe:0"
        }

        test("captureArguments describes the raw 8-bit BGRA input by the frame size of the format and the fps and queue of the config") {
            val read = arguments(format = VideoFormat(width = 1600, height = 900, bitrate = 6_512_000, preset = "6", fitsBudget = true))
            read[0] shouldBe "-y"
            read[1] shouldBe "-f"
            read[2] shouldBe "rawvideo"
            read[3] shouldBe "-pix_fmt"
            read[4] shouldBe "bgra"
            read[5] shouldBe "-s"
            read[6] shouldBe "1600x900"
            read[7] shouldBe "-framerate"
            read[8] shouldBe "120"
            read[9] shouldBe "-thread_queue_size"
            read[10] shouldBe "32"
        }

        test("captureArguments flips and converts the frames to BT.709 limited-range yuv420p, then blends them") {
            val read = arguments()
            read[13] shouldBe "-vf"
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p," +
                "tmix=frames=3:weights='1 4 1',framestep=2"
            read[15] shouldBe "-map"
            read[16] shouldBe "0:v:0"
            read[17] shouldBe "-c:v"
            read[18] shouldBe "libsvtav1"
            read[23] shouldBe "-svtav1-params"
            read[24] shouldBe "tune=0"
            read[25] shouldBe "-g"
            read[26] shouldBe "300"
        }

        test("captureArguments encodes at the preset and the average bitrate of the format") {
            val read = arguments(format = VideoFormat(width = 1920, height = 1080, bitrate = 8_348_000, preset = "6", fitsBudget = true))
            read.subList(19, 23) shouldBe listOf("-preset", "6", "-b:v", "8348k")
        }

        test("captureArguments passes no preset for a format without one") {
            val read = arguments(format = VideoFormat(width = 1280, height = 720, bitrate = 6_000_000, preset = null, fitsBudget = false))
            read shouldNotContain "-preset"
            read.subList(17, 23) shouldBe listOf("-c:v", "libsvtav1", "-b:v", "6000k", "-svtav1-params", "tune=0")
        }

        test("captureArguments never passes a constant rate factor or a bitrate ceiling") {
            arguments() shouldNotContainAnyOf listOf("-crf", "-maxrate")
        }

        test("captureArguments sizes the threads of the encoder and the filters by the processors the JVM sees") {
            val read = arguments(processors = 4)
            read[27] shouldBe "-threads"
            read[28] shouldBe "4"
            read[29] shouldBe "-filter_threads"
            read[30] shouldBe "4"
        }

        test("captureArguments passes the thread options as output options, before the record arguments, so a user's own win") {
            val read = arguments(videoConfig(arguments = "-threads 2 -filter_threads 1"), processors = 4)
            read[12] shouldBe "pipe:0"
            read.subList(27, 35) shouldBe listOf("-threads", "4", "-filter_threads", "4", "-threads", "2", "-filter_threads", "1")
        }

        test("captureArguments tags the output BT.709 limited range and ends with the yuv420p pixel format, no audio and the target") {
            val read = arguments()
            read[31] shouldBe "-colorspace"
            read[32] shouldBe "bt709"
            read[33] shouldBe "-color_primaries"
            read[34] shouldBe "bt709"
            read[35] shouldBe "-color_trc"
            read[36] shouldBe "bt709"
            read[37] shouldBe "-color_range"
            read[38] shouldBe "tv"
            read[39] shouldBe "-pix_fmt"
            read[40] shouldBe "yuv420p"
            read[41] shouldBe "-an"
            read[42] shouldBe target.absolutePathString()
        }

        test("captureArguments leaves out the frame blend when motion blur is off") {
            val read = arguments(videoConfig(motionBlur = false))
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p"
            read[8] shouldBe "60"
        }

        test("captureArguments converts the frames to and encodes them in the pixel format of the config") {
            val read = arguments(videoConfig(pixelFormat = "yuv420p10le", motionBlur = false))
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le"
            read[40] shouldBe "yuv420p10le"
        }

        test("captureArguments blends as many frames as the motion blur samples") {
            val read = arguments(videoConfig(motionBlurSamples = 4))
            read[8] shouldBe "240"
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p," +
                "tmix=frames=5:weights='1 4 4 4 1',framestep=4"
        }

        test("captureArguments leaves out the flags of blank options and appends the record arguments") {
            val read = arguments(videoConfig(svtav1Params = "", arguments = "-tune vq"))
            read shouldNotContainAnyOf listOf("-svtav1-params", "tune=0")
            read[23] shouldBe "-g"
            read[29] shouldBe "-tune"
            read[30] shouldBe "vq"
        }

        test("captureArguments holds no trim, time scale or keyframe flags") {
            arguments() shouldNotContainAnyOf listOf("-ss", "-t", "-itsscale", "-force_key_frames")
        }

        context("listen") {
            val systemBuffer = ServerSocketChannel.open().use { it.getOption(StandardSocketOptions.SO_RCVBUF) }

            test("sizes the receive buffer of the listener on Windows") {
                encoder(operatingSystem = OperatingSystem.WINDOWS).listen().use {
                    it.getOption(StandardSocketOptions.SO_RCVBUF) shouldNotBe systemBuffer
                }
            }

            test("leaves the receive buffer of the listener at the system default on Linux") {
                encoder(operatingSystem = OperatingSystem.LINUX).listen().use {
                    it.getOption(StandardSocketOptions.SO_RCVBUF) shouldBe systemBuffer
                }
            }
        }

        context("record") {
            test("fails with the failure of ffmpeg") {
                val failing = encoder(ffmpeg = standIn(failure = "Process ffmpeg exited with code 1"))

                shouldThrow<IllegalStateException> {
                    failing.encode { }
                }.message shouldBe "Process ffmpeg exited with code 1"
            }

            test("fails when ffmpeg produces no capture file") {
                val missing = encoder(captureTarget = home / "missing.mp4")

                shouldThrow<IllegalStateException> {
                    missing.encode { }
                }.message shouldBe "Frame capture was not produced by ffmpeg"
            }

            test("fails when the capture holds no frames") {
                val empty = home / "empty.mp4"
                empty.writeBytes(byteArrayOf(1))
                val holdingNone = encoder(captureTarget = empty, ffmpeg = standIn(frames = 0))

                shouldThrow<IllegalStateException> {
                    holdingNone.encode { }
                }.message shouldBe "Frame capture holds no frames"
            }

            test("holds the port it hands to the block until the block returns") {
                val finished = home / "finished.mp4"
                finished.writeBytes(byteArrayOf(1))
                val holding = encoder(captureTarget = finished, ffmpeg = standIn(frames = 1))

                holding.encode {
                    shouldThrow<BindException> {
                        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
                    }
                }
            }

            fun relaying(
                captureTarget: Path,
                relayed: OutputStream,
            ): FrameEncoder {
                val ffmpeg =
                    object : MediaTool {
                        override suspend fun run(
                            vararg arguments: String,
                            input: (suspend (OutputStream) -> Unit)?,
                            onProgress: ((MediaProgress) -> Unit)?,
                        ) {
                            input?.invoke(relayed)
                        }

                        override suspend fun frames(file: Path): Int = 1

                        override suspend fun duration(file: Path): Double = 0.0
                    }
                return encoder(format = defaultFormat.copy(width = 1, height = 1), captureTarget = captureTarget, ffmpeg = ffmpeg)
            }

            test("relays the bytes the hook library sends to the standard input of ffmpeg") {
                val relayed = ByteArrayOutputStream()
                val finished = home / "relayed.mp4"
                finished.writeBytes(byteArrayOf(1))

                relaying(finished, relayed).encode {
                    Socket(InetAddress.getLoopbackAddress(), port).use {
                        it.getOutputStream().write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
                    }
                }

                relayed.toByteArray() shouldBe byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            }

            test("fails when the hook library closes the connection inside a frame") {
                val partial = home / "partial.mp4"
                partial.writeBytes(byteArrayOf(1))

                shouldThrow<IllegalStateException> {
                    relaying(partial, ByteArrayOutputStream()).encode {
                        Socket(InetAddress.getLoopbackAddress(), port).use {
                            it.getOutputStream().write(byteArrayOf(1, 2, 3, 4, 5, 6))
                        }
                    }
                }.message shouldBe "Frame capture ended inside a frame, 2 bytes past the last whole frame"
            }

            test("fails when the block returns before the hook library connects") {
                val unconnected = home / "unconnected.mp4"
                unconnected.writeBytes(byteArrayOf(1))

                shouldThrow<IllegalStateException> {
                    relaying(unconnected, ByteArrayOutputStream()).encode { }
                }.message shouldBe "Hook library did not connect before the capture ended"
            }

            test("cancels the capture when the coroutine running it is cancelled") {
                val workerStarted = CompletableDeferred<Unit>()
                val workerCancelled = CompletableDeferred<Unit>()
                val ffmpeg =
                    object : MediaTool {
                        override suspend fun run(
                            vararg arguments: String,
                            input: (suspend (OutputStream) -> Unit)?,
                            onProgress: ((MediaProgress) -> Unit)?,
                        ): Unit =
                            try {
                                workerStarted.complete(Unit)
                                awaitCancellation()
                            } finally {
                                workerCancelled.complete(Unit)
                            }

                        override suspend fun frames(file: Path): Int = 0

                        override suspend fun duration(file: Path): Double = 0.0
                    }
                val cancelling = encoder(ffmpeg = ffmpeg)

                coroutineScope {
                    val job = launch { cancelling.encode { awaitCancellation() } }
                    workerStarted.await()
                    job.cancelAndJoin()
                }

                workerCancelled.isCompleted shouldBe true
            }

            test("closes ffmpeg and fails when ffmpeg does not finish within the finish timeout after the block returns") {
                val workerCancelled = CompletableDeferred<Unit>()
                val ffmpeg =
                    object : MediaTool {
                        override suspend fun run(
                            vararg arguments: String,
                            input: (suspend (OutputStream) -> Unit)?,
                            onProgress: ((MediaProgress) -> Unit)?,
                        ): Unit =
                            try {
                                awaitCancellation()
                            } finally {
                                workerCancelled.complete(Unit)
                            }

                        override suspend fun frames(file: Path): Int = 0

                        override suspend fun duration(file: Path): Double = 0.0
                    }
                val hanging = encoder(config = videoConfig(finishTimeout = 100.milliseconds), ffmpeg = ffmpeg)

                shouldThrow<TimeoutException> {
                    hanging.encode { }
                }.message shouldBe "Frame capture was not finalised by ffmpeg within 100ms"
                workerCancelled.isCompleted shouldBe true
            }
        }
    })
