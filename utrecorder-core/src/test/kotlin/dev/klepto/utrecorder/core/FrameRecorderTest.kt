package dev.klepto.utrecorder.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.time.Duration.Companion.seconds

class FfmpegLikeStandIn(
    val captureOutcome: Boolean = true,
    val frameCount: Int = 0,
) : FfmpegLike {
    val startedWith: List<String> get() = started.single()

    private val started = mutableListOf<List<String>>()

    override fun capture(vararg arguments: String): Boolean {
        started += arguments.toList()
        return captureOutcome
    }

    override fun frames(file: Path): Int = frameCount

    override fun duration(file: Path): Double = durationValue

    val durationValue: Double = 8.0
}

class FrameRecorderTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-framerecorder")
        val target = home / "capture.mp4"
        val port = 12000

        fun recorder(options: Map<String, String> = emptyMap()): FrameRecorder =
            FrameRecorder(
                RecorderConfig(
                    installationDirectory = Path.of("install"),
                    startupTimeout = 5.seconds,
                    fps = 60,
                    codec = "libsvtav1",
                    preset = options["preset"] ?: "4",
                    crf = options["crf"] ?: "28",
                    bitrate = options["bitrate"] ?: "4800k",
                    svtav1Params = options["svtav1Params"] ?: "tune=0",
                    gop = 300,
                    width = 960,
                    height = 540,
                    frameQueue = 32,
                    recordArguments = options["recordArguments"] ?: "",
                ),
                target,
                FfmpegLikeStandIn(),
            )

        fun arguments(options: Map<String, String> = emptyMap()): List<String> = recorder(options).captureArguments(port)

        fun List<String>.without(vararg held: String): List<String> = filterNot { it in held }

        test("captureArguments reads the stream from the loopback with the listen timeout and the frame-sized receive buffer") {
            val read = arguments()
            read.size shouldBe 33
            read[11] shouldBe "-i"
            read[12] shouldBe
                "tcp://127.0.0.1:12000?listen=1&listen_timeout=5000" +
                "&recv_buffer_size=${960 * 540 * 4}"
        }

        test("captureArguments reads the frame size, framerate and queue bound off the config in their argument positions") {
            val read = arguments()
            read[0] shouldBe "-y"
            read[1] shouldBe "-f"
            read[2] shouldBe "rawvideo"
            read[3] shouldBe "-pix_fmt"
            read[4] shouldBe "bgra"
            read[5] shouldBe "-s"
            read[6] shouldBe "960x540"
            read[7] shouldBe "-framerate"
            read[8] shouldBe "60"
            read[9] shouldBe "-thread_queue_size"
            read[10] shouldBe "32"
            read[11] shouldBe "-i"
        }

        test("captureArguments encodes into the configured final codec with preset, capped rate factor, params and GOP") {
            val read = arguments()
            read[13] shouldBe "-vf"
            read[14] shouldBe "vflip"
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

        test("captureArguments reads the output pixel format and the output file off the config") {
            val read = arguments()
            read[29] shouldBe "-pix_fmt"
            read[30] shouldBe "yuv420p"
            read[31] shouldBe "-an"
            read[32] shouldBe target.absolutePathString()
        }

        test("a blank rate factor hands the bitrate over as the average instead of the ceiling") {
            val read = arguments(mapOf("crf" to ""))
            read shouldBe read.without("-crf", "28", "-maxrate")
            read[21] shouldBe "-b:v"
            read[22] shouldBe "4800k"
        }

        test("a blank preset, rate factor, bitrate and params leave their flags out and extra record arguments land verbatim") {
            val read =
                arguments(
                    mapOf(
                        "preset" to "",
                        "crf" to "",
                        "bitrate" to "",
                        "svtav1Params" to "",
                        "recordArguments" to "-tune vq",
                    ),
                )
            read shouldBe read.without("-preset", "4", "-crf", "28", "-b:v", "-maxrate", "4800k", "-svtav1-params", "tune=0")
            read[19] shouldBe "-g"
            read[21] shouldBe "-tune"
            read[22] shouldBe "vq"
        }

        test("captureArguments holds none of the trim or keyframe flags a capture the shim cuts at the source takes") {
            arguments().filter { it in setOf("-ss", "-t", "-itsscale", "-force_key_frames") } shouldBe emptyList()
        }
    })
