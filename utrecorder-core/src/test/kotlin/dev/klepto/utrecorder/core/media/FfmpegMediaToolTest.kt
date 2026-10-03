package dev.klepto.utrecorder.core.media

import dev.klepto.utrecorder.common.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes

class FfmpegMediaToolTest :
    FunSpec({
        fun silentWav(
            sampleRate: Int,
            frames: Int,
        ): ByteArray {
            val dataSize = frames * 2
            return ByteBuffer
                .allocate(44 + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(36 + dataSize)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(1)
                .putInt(sampleRate)
                .putInt(sampleRate * 2)
                .putShort(2)
                .putShort(16)
                .put("data".toByteArray())
                .putInt(dataSize)
                .array()
        }

        context("run") {
            test("returns when ffmpeg exits with code 0") {
                FfmpegMediaTool().run("-version")
            }

            test("fails with the output of ffmpeg when ffmpeg exits with a nonzero code") {
                shouldThrow<IllegalStateException> {
                    FfmpegMediaTool().run("-bogus-flag", "no-such-input-file")
                }.message shouldContain "Unrecognized option 'bogus-flag'"
            }

            test("fails with the output of ffmpeg when ffmpeg exits while its input is written") {
                shouldThrow<IllegalStateException> {
                    FfmpegMediaTool().run(
                        "-f",
                        "rawvideo",
                        "-pix_fmt",
                        "bgra",
                        "-s",
                        "16x16",
                        "-i",
                        "pipe:0",
                        "-c:v",
                        "no-such-encoder",
                        "-f",
                        "null",
                        "-",
                        input = { stream -> repeat(4096) { stream.write(ByteArray(16 * 16 * 4)) } },
                    )
                }.message shouldContain "Unknown encoder 'no-such-encoder'"
            }
        }

        context("executable") {
            test("puts the library directory in front of LD_LIBRARY_PATH on Linux") {
                val executable =
                    FfmpegMediaTool().executable(
                        Path.of("bundled", "ffmpeg.exe"),
                        Path.of("bundled"),
                        operatingSystem = OperatingSystem.LINUX,
                    )

                executable.environment["LD_LIBRARY_PATH"] shouldBe "${Path.of("bundled")}"
            }

            test("starts the program through nice -n 10 on Linux") {
                val executable =
                    FfmpegMediaTool().executable(
                        Path.of("bundled", "ffmpeg"),
                        Path.of("bundled"),
                        listOf("-version"),
                        operatingSystem = OperatingSystem.LINUX,
                    )

                executable.executablePath shouldBe Path.of("nice")
                executable.arguments shouldBe listOf("-n", "10", "${Path.of("bundled", "ffmpeg")}", "-version")
            }

            test("starts the program itself on Windows") {
                val executable =
                    FfmpegMediaTool().executable(
                        Path.of("bundled", "ffmpeg.exe"),
                        Path.of("bundled"),
                        listOf("-version"),
                        operatingSystem = OperatingSystem.WINDOWS,
                    )

                executable.executablePath shouldBe Path.of("bundled", "ffmpeg.exe")
                executable.arguments shouldBe listOf("-version")
            }
        }

        context("run with progress") {
            test("reports the frames written once ffmpeg finishes") {
                val file = createTempDirectory("utrecorder-ffmpeg-progress") / "black.mp4"
                val reports = mutableListOf<MediaProgress>()

                FfmpegMediaTool().run(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=black:s=64x64:d=0.7:r=10",
                    "-c:v",
                    "mpeg4",
                    "${file.toAbsolutePath()}",
                ) { reports += it }

                reports.last().frames shouldBe 7
            }

            test("encodes the raw frames written to its standard input") {
                val file = createTempDirectory("utrecorder-ffmpeg-input") / "black.mp4"
                val reports = mutableListOf<MediaProgress>()

                FfmpegMediaTool().run(
                    "-f",
                    "rawvideo",
                    "-pix_fmt",
                    "bgra",
                    "-s",
                    "16x16",
                    "-framerate",
                    "10",
                    "-i",
                    "pipe:0",
                    "-c:v",
                    "mpeg4",
                    "${file.toAbsolutePath()}",
                    input = { it.write(ByteArray(16 * 16 * 4 * 3)) },
                ) { reports += it }

                reports.last().frames shouldBe 3
            }
        }

        context("duration") {
            test("returns the length of a wav file in seconds") {
                val file = createTempDirectory("utrecorder-ffmpeg-duration") / "silence.wav"
                file.writeBytes(silentWav(sampleRate = 8000, frames = 4000))

                FfmpegMediaTool().duration(file) shouldBe 0.5
            }
        }

        context("frames") {
            test("returns the number of frames in the first video stream") {
                val file = createTempDirectory("utrecorder-ffmpeg-frames") / "black.mp4"
                FfmpegMediaTool().run(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=black:s=64x64:d=0.7:r=10",
                    "-c:v",
                    "mpeg4",
                    "-qscale:v",
                    "4",
                    "${file.toAbsolutePath()}",
                )

                FfmpegMediaTool().frames(file) shouldBe 7
            }
        }
    })
