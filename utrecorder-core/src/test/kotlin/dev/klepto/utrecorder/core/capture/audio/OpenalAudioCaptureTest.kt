package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.math.PI
import kotlin.math.sin

class OpenalAudioCaptureTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-audio-capture-test")
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)
        val installationDirectory = home / "install"
        val systemDirectory = installationDirectory / "System"
        systemDirectory.createDirectories()
        (systemDirectory / UtPlatform.current().gameExecutable).writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(ConfigReader.EMPTY, directories))
        val config = AudioCaptureConfig(ConfigReader.EMPTY)
        val log =
            listOf(
                "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 0 4567 12.5",
                "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 45 0 98765 300.75",
            )

        afterSpec {
            home.toFile().deleteRecursively()
        }

        fun capture(settings: AudioCaptureConfig = config) = OpenalAudioCapture(settings, installation, directories)

        fun wallClock(text: String): FileTime = FileTime.from(LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant())

        fun writeWav(path: Path): Path {
            val sampleRate = 8000
            val samples = sampleRate / 2
            val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(samples) { i ->
                pcm.putShort((32000 * sin(2 * PI * 440 * i / sampleRate)).toInt().toShort())
            }
            val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray())
            bytes.putInt(36 + samples * 2)
            bytes.put("WAVEfmt ".toByteArray())
            bytes.putInt(16)
            bytes.putShort(1)
            bytes.putShort(1)
            bytes.putInt(sampleRate)
            bytes.putInt(sampleRate * 2)
            bytes.putShort(2)
            bytes.putShort(16)
            bytes.put("data".toByteArray())
            bytes.putInt(samples * 2)
            bytes.put(pcm.array())
            path.parent.createDirectories()
            path.writeBytes(bytes.array())
            return path
        }

        test("openAl selects the wave driver and writes a conf that sends the wav of the workspace beside the game binary") {
            workspace("audio-capture", installation.systemDirectory, directories) { workspace ->
                val environment = capture().openAl(workspace)

                val conf = Path.of(environment.getValue("ALSOFT_CONF"))
                conf shouldBe workspace["alsoft.conf"].toAbsolutePath()
                conf.fileName.toString() shouldStartWith "audio-capture-"
                conf.readText() shouldBe
                    "[general]\ndrivers = wave\nfrequency = 44100\nchannels = stereo\nsample-type = int16\n\n" +
                    "[wave]\nfile = ${workspace["audio.wav"].fileName}\n"
                environment["ALSOFT_DRIVERS"] shouldBe "wave"
                environment["ALSOFT_LOGLEVEL"] shouldBe "2"
            }
        }

        test("openAl names a different wav for each workspace on one installation") {
            suspend fun conf() =
                workspace("audio-capture", installation.systemDirectory, directories) { workspace ->
                    Path.of(capture().openAl(workspace).getValue("ALSOFT_CONF")).readText()
                }

            conf() shouldNotBe conf()
        }

        test("execScript rewinds the demo to the start") {
            capture().execScript() shouldBe listOf("RewindStart")
        }

        test("execScript disables rendering when the config turns the audio capture's rendering off") {
            capture(AudioCaptureConfig(ConfigReader.EMPTY, render = false)).execScript() shouldBe listOf("RewindStart", "DisableRendering")
        }

        test("track starts 6 seconds into the wav and runs for the 5 minutes 40 seconds between the marks") {
            val wav = writeWav(home / "audio.wav")
            wav.setLastModifiedTime(wallClock("2026-09-13T10:30:11"))

            capture().track(wav, log, 12.0) shouldBe
                AudioTrack(
                    file = wav,
                    offset = 6.0,
                    length = 340.0,
                    demoEnd = 300.75,
                )
        }

        test("track fails naming the wav when the wav does not exist") {
            val failure = shouldThrow<IllegalStateException> { capture().track(home / "missing.wav", log, 12.0) }

            failure.message shouldContain "audio.wav"
        }

        test("track fails naming the missing mark when the log holds no end mark") {
            val wav = writeWav(home / "audio.wav")

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, log.dropLast(1), 12.0) }

            failure.message shouldContain "end mark"
        }

        test("track fails when the end mark lies no later in the demo than the start mark") {
            val wav = writeWav(home / "audio.wav")
            wav.setLastModifiedTime(wallClock("2026-09-13T10:30:11"))
            val invertedLog =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 0 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 45 0 98765 10.0",
                )

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, invertedLog, 12.0) }

            failure.message shouldContain "outside"
        }

        test("track fails when the start mark lies before the wav was opened") {
            val wav = writeWav(home / "audio.wav")
            wav.setLastModifiedTime(wallClock("2026-09-13T10:30:40"))

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, log, 12.0) }

            failure.message shouldContain "outside"
        }
    })
