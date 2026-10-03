package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
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
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
                "ScriptLog: UTRecorder: mark start 1999 12 31 23 59 50 0 4000 0.5",
                "ScriptLog: UTRecorder: mark spawn 2000 1 1 0 0 6 0 4567 12.5",
                "ScriptLog: UTRecorder: mark end 2000 1 1 0 5 46 0 98765 300.75",
            )

        afterSpec {
            home.toFile().deleteRecursively()
        }

        fun capture(settings: AudioCaptureConfig = config) = OpenalAudioCapture(settings, installation, directories)

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
                    "[general]\ndrivers = wave\nfrequency = 44100\nchannels = stereo\nsample-type = int16\n" +
                    "period_size = 512\n\n" +
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

        test("environment puts the hook library in audio mode at the sample rate and the mixer period of the wav") {
            capture().environment() shouldBe mapOf("UTRECORDER_AUDIO_RATE" to "44100", "UTRECORDER_AUDIO_PERIOD" to "512")
        }

        test("process loads the hook library through the injector on Windows, and preloads it under nice on Linux") {
            val hookLibrary = systemDirectory / "uthooks.dll"
            val injector = systemDirectory / "utinject.exe"
            val (files, executable) =
                workspace("audio-capture", installation.systemDirectory, directories) { workspace ->
                    val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)
                    val process = capture().process(description, emptyMap(), listOf(hookLibrary, injector))
                    val files = process.files(workspace)
                    files to process.executable(files)
                }

            executable.environment["UTRECORDER_AUDIO_PERIOD"] shouldBe "512"
            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    executable.executablePath shouldBe injector
                    executable.arguments[0] shouldBe hookLibrary.absolutePathString()
                    executable.arguments[1] shouldBe files.executable.absolutePathString()
                }

                UtPlatform.LINUX -> {
                    executable.executablePath shouldBe Path.of("nice")
                    executable.arguments.take(3) shouldBe listOf("-n", "10", files.executable.absolutePathString())
                    executable.environment["LD_PRELOAD"] shouldBe hookLibrary.absolutePathString()
                }
            }
        }

        test("execScript rewinds the demo to the start and arms the hooks at the spawn") {
            capture().execScript(DemoPlaybackConfig.of("staged-demo", Duration.ZERO)) shouldBe listOf("SeekStart", "DemoFrames 86.132813")
        }

        test("execScript disables rendering when the config turns the audio capture's rendering off") {
            capture(AudioCaptureConfig(ConfigReader.EMPTY, render = false))
                .execScript(DemoPlaybackConfig.of("staged-demo", Duration.ZERO)) shouldBe
                listOf("SeekStart", "DemoFrames 86.132813", "DisableRendering")
        }

        test("execScript passes the demo times the run starts and stops at") {
            val description =
                DemoPlaybackConfig.of("staged-demo", Duration.ZERO, runStart = 10.120962.seconds, runStop = 64.572962.seconds)

            capture().execScript(description) shouldBe
                listOf("SeekStart", "DemoFrames 86.132813", "TimerReset 10.120962", "TimerStop 64.572962")
        }

        test("track starts 6 seconds into the wav at the spawn mark and runs for the 5 minutes 40 seconds to the end mark") {
            val wav = writeWav(home / "audio.wav")

            capture().track(wav, log, 400.0) shouldBe
                AudioTrack(
                    file = wav,
                    offset = 6.0,
                    length = 340.0,
                    demoStart = 12.5,
                )
        }

        test("track fails naming the wav when the wav does not exist") {
            val failure = shouldThrow<IllegalStateException> { capture().track(home / "missing.wav", log, 400.0) }

            failure.message shouldContain "audio.wav"
        }

        test("track fails naming the missing mark when the log holds no end mark") {
            val wav = writeWav(home / "audio.wav")

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, log.dropLast(1), 400.0) }

            failure.message shouldContain "end mark"
        }

        test("track fails naming the missing mark when the log holds no spawn mark") {
            val wav = writeWav(home / "audio.wav")

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, log.filterNot { "spawn" in it }, 400.0) }

            failure.message shouldContain "spawn mark"
        }

        test("track fails when the end mark lies no later in the demo than the spawn mark") {
            val wav = writeWav(home / "audio.wav")
            val invertedLog =
                listOf(
                    "ScriptLog: UTRecorder: mark start 1999 12 31 23 59 50 0 4000 0.5",
                    "ScriptLog: UTRecorder: mark spawn 2000 1 1 0 0 6 0 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2000 1 1 0 5 46 0 98765 10.0",
                )

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, invertedLog, 400.0) }

            failure.message shouldContain "outside"
        }

        test("track fails when the end mark lies past the end of the wav") {
            val wav = writeWav(home / "audio.wav")

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, log, 300.0) }

            failure.message shouldContain "outside"
        }

        test("track fails when the marks carry the wall clock rather than the position in the wav") {
            val wav = writeWav(home / "audio.wav")
            val wallClockLog =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 0 0 4000 0.5",
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 30 5 0 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 45 0 98765 300.75",
                )

            val failure = shouldThrow<IllegalStateException> { capture().track(wav, wallClockLog, 400.0) }

            failure.message shouldContain "outside"
        }
    })
