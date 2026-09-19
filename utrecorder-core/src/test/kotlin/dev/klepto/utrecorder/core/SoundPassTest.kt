package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration

class SoundPassTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-soundpass-test")
        val installationDirectory = home / "install"
        val gameExecutable = UtPlatform.current().gameExecutable
        installationDirectory.resolve("System").createDirectories()
        installationDirectory.resolve("System/$gameExecutable").writeText("")
        val installation = UtInstallation(installationDirectory, Directories(home))
        val config = RecorderConfig(installationDirectory = installationDirectory)
        val openAlEnvironment = mapOf("ALSOFT_DRIVERS" to "wave", "ALSOFT_LOGLEVEL" to "2")

        afterSpec {
            home.toFile().deleteRecursively()
        }

        fun pass(
            settings: RecorderConfig = config,
            environment: Map<String, String> = openAlEnvironment,
        ) = SoundPass(settings, installation, openAlEnvironment = environment)

        fun wavFile(path: Path): Path {
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
            path.parent.createDirectories()
            path.writeBytes(bytes.array())
            return path
        }

        context("gameIni") {
            test("the ini edits carry the 64 by 64 viewport and its 60 fps cap in both client sections and the render device section") {
                val ini = pass().gameIni(Duration.ZERO)

                val clientValues =
                    mapOf(
                        "WindowedViewportX" to "64",
                        "WindowedViewportY" to "64",
                        "FullscreenViewportX" to "64",
                        "FullscreenViewportY" to "64",
                        "FrameRateLimit" to "60.000000",
                    )

                ini shouldBe
                    mapOf(
                        "WinDrv.WindowsClient" to clientValues,
                        "SDLDrv.SDLClient" to clientValues,
                        "OpenGLDrv.OpenGLRenderDevice" to mapOf("FrameRateLimit" to "60.000000"),
                    )
            }
        }

        context("execScript") {
            test("the exec script holds the staged demo at real speed") {
                val description = PlaybackDescription.of("staged-demo", Duration.ZERO, config)

                pass().execScript(description) shouldBe listOf("demoplay staged-demo?timebased")
            }

            test("the exec script appends DisableRendering only when rendering is disabled") {
                val description = PlaybackDescription.of("staged-demo", Duration.ZERO, config)

                pass(config.copy(soundRender = false)).execScript(description) shouldBe
                    listOf(
                        "demoplay staged-demo?timebased",
                        "DisableRendering",
                    )
            }
        }

        context("track") {
            val wavLog =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 0 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 45 0 98765 300.75",
                )

            fun wallClock(text: String): Instant = LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()

            test("the marks of a wav they fall inside place it 6 seconds in, running the 5 minutes 40 they bound") {
                val wav = wavFile(home / "audio.wav")
                Files.setLastModifiedTime(wav, FileTime.from(wallClock("2026-09-13T10:30:11")))

                val track = pass().track(wav, wavLog, 12.0)

                track shouldBe
                    SoundTrack(
                        file = wav,
                        offset = 6.0,
                        length = 340.0,
                        demoEnd = 300.75,
                        demoSpan = 288.25,
                    )
            }

            test("a pass missing its wav fails the recording naming what was missing") {
                val failure = shouldThrow<IllegalStateException> { pass().track(home / "missing.wav", wavLog, 12.0) }

                failure.message shouldContain "audio.wav"
            }

            test("a pass logging no start mark fails the recording naming it") {
                val wav = wavFile(home / "audio.wav")

                val failure = shouldThrow<IllegalStateException> { pass().track(wav, wavLog.drop(1), 12.0) }

                failure.message shouldContain "start"
            }

            test("a pass logging no end mark fails the recording naming it") {
                val wav = wavFile(home / "audio.wav")

                val failure = shouldThrow<IllegalStateException> { pass().track(wav, wavLog.dropLast(1), 12.0) }

                failure.message shouldContain "end mark"
            }

            test("marks bounding no demo time beyond the start fail the recording") {
                val wav = wavFile(home / "audio.wav")
                Files.setLastModifiedTime(wav, FileTime.from(Instant.parse("2026-09-13T10:30:11Z")))
                val invertedLog =
                    listOf(
                        "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 0 4567 12.5",
                        "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 45 0 98765 10.0",
                    )

                val failure = shouldThrow<IllegalStateException> { pass().track(wav, invertedLog, 12.0) }

                failure.message shouldContain "outside"
            }

            test("marks falling outside the wav fail the recording") {
                val wav = wavFile(home / "audio.wav")
                Files.setLastModifiedTime(wav, FileTime.from(wallClock("2026-09-13T10:30:40")))

                val failure = shouldThrow<IllegalStateException> { pass().track(wav, wavLog, 12.0) }

                failure.message shouldContain "audio.wav"
            }
        }
    })
