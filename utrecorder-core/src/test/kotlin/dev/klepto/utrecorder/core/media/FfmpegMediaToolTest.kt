package dev.klepto.utrecorder.core.media

import dev.klepto.utrecorder.common.OperatingSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
            test("returns true when ffmpeg exits with code 0") {
                FfmpegMediaTool().run("-version") shouldBe true
            }

            test("returns false when ffmpeg exits with a nonzero code") {
                FfmpegMediaTool().run("-bogus-flag", "no-such-input-file") shouldBe false
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

                executable.executablePath shouldBe Path.of("bundled", "ffmpeg.exe")
                executable.environment["LD_LIBRARY_PATH"] shouldBe "${Path.of("bundled")}"
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
                ) { reports += it } shouldBe true

                reports.last().frames shouldBe 7
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
