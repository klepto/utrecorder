package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.seconds

class FrameEncoderTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-framerecorder")
        val target = home / "video.mp4"

        fun videoConfig(
            preset: String = "4",
            crf: String = "28",
            bitrate: String = "4800k",
            svtav1Params: String = "tune=0",
            arguments: String = "",
            motionBlur: Boolean = true,
            motionBlurSamples: Int = 2,
        ): VideoCaptureConfig =
            VideoCaptureConfig(
                ConfigReader.EMPTY,
                port = 12000,
                fps = 60,
                codec = "libsvtav1",
                preset = preset,
                crf = crf,
                bitrate = bitrate,
                svtav1Params = svtav1Params,
                gop = 300,
                width = 960,
                height = 540,
                queue = 32,
                arguments = arguments,
                motionBlur = motionBlur,
                motionBlurSamples = motionBlurSamples,
            )

        val defaultConfig = videoConfig()

        fun standIn(
            finalised: Boolean = true,
            frames: Int = 0,
        ): MediaTool =
            object : MediaTool {
                override suspend fun run(
                    vararg arguments: String,
                    onProgress: ((MediaProgress) -> Unit)?,
                ): Boolean = finalised

                override suspend fun frames(file: Path): Int = frames

                override suspend fun duration(file: Path): Double = 0.0
            }

        fun encoder(
            config: VideoCaptureConfig = defaultConfig,
            captureTarget: Path = target,
            ffmpeg: MediaTool = standIn(),
        ): FrameEncoder = FrameEncoder(config, 5.seconds, captureTarget, ffmpeg)

        fun arguments(config: VideoCaptureConfig = defaultConfig): List<String> = encoder(config).captureArguments()

        test("captureArguments reads the frames from a loopback listener with the listen timeout and a one-frame receive buffer") {
            val read = arguments()
            read.size shouldBe 41
            read[11] shouldBe "-i"
            read[12] shouldBe
                "tcp://127.0.0.1:12000?listen=1&listen_timeout=5000" +
                "&recv_buffer_size=${960 * 540 * 4}"
        }

        test("captureArguments describes the raw 8-bit BGRA input by the frame size, render fps and frame queue of the config") {
            val read = arguments()
            read[0] shouldBe "-y"
            read[1] shouldBe "-f"
            read[2] shouldBe "rawvideo"
            read[3] shouldBe "-pix_fmt"
            read[4] shouldBe "bgra"
            read[5] shouldBe "-s"
            read[6] shouldBe "960x540"
            read[7] shouldBe "-framerate"
            read[8] shouldBe "120"
            read[9] shouldBe "-thread_queue_size"
            read[10] shouldBe "32"
        }

        test("captureArguments flips and converts the frames to BT.709 limited-range yuv420p10le, then blends them") {
            val read = arguments()
            read[13] shouldBe "-vf"
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le," +
                "tmix=frames=3:weights='1 4 1',framestep=2"
            read[15] shouldBe "-map"
            read[16] shouldBe "0:v:0"
            read[17] shouldBe "-c:v"
            read[18] shouldBe "libsvtav1"
            read[19] shouldBe "-preset"
            read[20] shouldBe "4"
            read[21] shouldBe "-crf"
            read[22] shouldBe "28"
            read[23] shouldBe "-maxrate"
            read[24] shouldBe "4800k"
            read[25] shouldBe "-svtav1-params"
            read[26] shouldBe "tune=0"
            read[27] shouldBe "-g"
            read[28] shouldBe "300"
        }

        test("captureArguments tags the output BT.709 limited range and ends with the yuv420p10le pixel format, no audio and the target") {
            val read = arguments()
            read[29] shouldBe "-colorspace"
            read[30] shouldBe "bt709"
            read[31] shouldBe "-color_primaries"
            read[32] shouldBe "bt709"
            read[33] shouldBe "-color_trc"
            read[34] shouldBe "bt709"
            read[35] shouldBe "-color_range"
            read[36] shouldBe "tv"
            read[37] shouldBe "-pix_fmt"
            read[38] shouldBe "yuv420p10le"
            read[39] shouldBe "-an"
            read[40] shouldBe target.absolutePathString()
        }

        test("captureArguments leaves out the frame blend when motion blur is off") {
            val read = arguments(videoConfig(motionBlur = false))
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le"
            read[8] shouldBe "60"
        }

        test("captureArguments blends as many frames as the motion blur samples") {
            val read = arguments(videoConfig(motionBlurSamples = 4))
            read[8] shouldBe "240"
            read[14] shouldBe
                "vflip,scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le," +
                "tmix=frames=5:weights='1 4 4 4 1',framestep=4"
        }

        test("captureArguments passes the bitrate as the average when the rate factor is blank") {
            val read = arguments(videoConfig(crf = ""))
            read shouldNotContainAnyOf listOf("-crf", "28", "-maxrate")
            read[21] shouldBe "-b:v"
            read[22] shouldBe "4800k"
        }

        test("captureArguments leaves out the flags of blank options and appends the record arguments") {
            val config =
                videoConfig(
                    preset = "",
                    crf = "",
                    bitrate = "",
                    svtav1Params = "",
                    arguments = "-tune vq",
                )
            val read = arguments(config)
            read shouldNotContainAnyOf listOf("-preset", "4", "-crf", "28", "-b:v", "-maxrate", "4800k", "-svtav1-params", "tune=0")
            read[19] shouldBe "-g"
            read[21] shouldBe "-tune"
            read[22] shouldBe "vq"
        }

        test("captureArguments holds no trim, time scale or keyframe flags") {
            arguments() shouldNotContainAnyOf listOf("-ss", "-t", "-itsscale", "-force_key_frames")
        }

        context("record") {
            test("fails when ffmpeg does not finalise the capture") {
                val failing = encoder(ffmpeg = standIn(finalised = false))

                shouldThrow<IllegalStateException> {
                    failing.encode { }
                }.message shouldBe "Frame capture was not finalised by ffmpeg"
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

            test("cancels the capture when the coroutine running it is cancelled") {
                val workerStarted = CompletableDeferred<Unit>()
                val workerCancelled = CompletableDeferred<Unit>()
                val ffmpeg =
                    object : MediaTool {
                        override suspend fun run(
                            vararg arguments: String,
                            onProgress: ((MediaProgress) -> Unit)?,
                        ): Boolean =
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
        }
    })
