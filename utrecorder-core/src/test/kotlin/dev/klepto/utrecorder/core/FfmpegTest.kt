package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.PropertySource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes

class FfmpegTest :
    FunSpec({
        fun source(environment: Map<String, String> = emptyMap()) =
            PropertySource(
                properties = mapOf("os.name" to "Linux", "user.dir" to "/work")::get,
                environment = environment::get,
            )

        context("run") {
            test("reports success for a zero-exit command line") {
                Ffmpeg(source()).run("-version") shouldBe true
            }

            test("reports failure for a nonzero-exit command line, wearing the tail of its output") {
                Ffmpeg(source()).run("-bogus-flag", "no-such-input-file") shouldBe false
            }
        }

        context("builder") {
            test("prepends the executable's library directory to LD_LIBRARY_PATH") {
                val executable = Ffmpeg(source()).builder(Path.of("bundled", "ffmpeg.exe"), Path.of("bundled"))

                executable.executablePath shouldBe Path.of("bundled", "ffmpeg.exe")
                executable.environment["LD_LIBRARY_PATH"] shouldBe "${Path.of("bundled")}"
            }

            test("keeps an existing LD_LIBRARY_PATH value behind the prepended one") {
                val properties = source(mapOf("LD_LIBRARY_PATH" to "/existing"))

                val executable = Ffmpeg(properties).builder(Path.of("bundled", "ffmpeg.exe"), Path.of("bundled"))

                val separator = File.pathSeparator
                executable.environment["LD_LIBRARY_PATH"] shouldBe "${Path.of("bundled")}$separator/existing"
            }
        }

        context("duration") {
            test("reads a file ffprobe reports a duration for as its literal length") {
                val home = createTempDirectory("utrecorder-ffmpeg-duration")
                val file = home / "tone.wav"
                val seconds = 0.5
                val sampleRate = 8000
                val frames = (seconds * sampleRate).toInt()
                val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(frames) { i ->
                    pcm.putShort((32000 * Math.sin(2 * Math.PI * 440 * i / sampleRate.toDouble())).toInt().toShort())
                }
                val bytes = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.putInt(0x46464952)
                bytes.putInt(36 + frames * 2)
                bytes.putInt(0x45564157)
                bytes.putInt(0x20746D66)
                bytes.putInt(16)
                bytes.putShort(1)
                bytes.putShort(1)
                bytes.putInt(sampleRate)
                bytes.putInt(sampleRate * 2)
                bytes.putShort(2)
                bytes.putShort(16)
                bytes.putInt(0x61746164)
                bytes.putInt(frames * 2)
                bytes.put(pcm.array())
                file.writeBytes(bytes.array())

                Ffmpeg(source()).duration(file) shouldBe 0.5
            }
        }

        context("frames") {
            test("reads a file ffprobe reports packet counts for as the video stream's count") {
                val home = createTempDirectory("utrecorder-ffmpeg-frames")
                val file = home / "packet-count.mp4"

                Ffmpeg(source()).run(
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
                val count = (0.7 * 10).toInt()

                Ffmpeg(source()).frames(file) shouldBe count
            }
        }
    })
