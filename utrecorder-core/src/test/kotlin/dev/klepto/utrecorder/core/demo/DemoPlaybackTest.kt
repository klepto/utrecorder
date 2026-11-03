package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.NoSuchFileException
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration

class DemoPlaybackTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-playback-test")
        val installationDirectory = home / "install"
        val systemDirectory = installationDirectory / "System"
        systemDirectory.createDirectories()
        val gameExecutable = UtPlatform.current().gameExecutable
        (systemDirectory / gameExecutable).writeText("")
        val installation =
            UtInstallation(installationDirectory, UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = home)))
        val playback = DemoPlayback(installation, installation.config.directoriesConfig)

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("play fails without running the block or staging anything when no file exists at the demo path") {
            var ran = false

            shouldThrow<NoSuchFileException> {
                playback.play(home / "missing.dem", "pb-") { ran = true }
            }

            ran shouldBe false
            systemDirectory.listDirectoryEntries().map { it.name } shouldBe listOf(gameExecutable)
        }

        test("play fails without running the block or staging anything when the demo does not parse") {
            val demo = home / "broken.dem"
            demo.writeBytes(byteArrayOf(0x1, 0x2, 0x3, 0x4))
            var ran = false

            shouldThrow<IllegalStateException> {
                playback.play(demo, "pb-") { ran = true }
            }

            ran shouldBe false
            systemDirectory.listDirectoryEntries().map { it.name } shouldBe listOf(gameExecutable)
        }

        test("process opens the Entry map and plays the demo windowed and frame-based before the exec lines the configuration adds") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)
            val viewport = DemoPlaybackViewport(width = 64, height = 48, frameRateLimit = 60.0)

            val process = playback.process("capture", description, viewport) { exec += "HideMessages" }

            process.map shouldBe "Entry"
            process.exec shouldBe listOf("demoplay staged-demo", "HideMessages")
            process.windowed shouldBe true
            process.baseGameIni shouldBe DemoPlaybackResources.gameIni
            process.baseUserIni shouldBe DemoPlaybackResources.userIni
        }

        test("process keeps only the mark lines of the log of the run") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)
            val viewport = DemoPlaybackViewport(width = 64, height = 48, frameRateLimit = 60.0)

            val process = playback.process("capture", description, viewport) {}

            process.logFilter("ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5") shouldBe true
            process.logFilter("ScriptLog: UTRecorder: f 4600 tick 0.016 level 5.0 stream 13.0 dil 1.0") shouldBe false
        }

        test("process configures the recorder console in the game ini of the run, not in a file shared by every run") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)
            val viewport = DemoPlaybackViewport(width = 64, height = 48, frameRateLimit = 60.0)
            val gameIni = UtIni("")

            playback.process("capture", description, viewport) {}.gameIniEdits.forEach { gameIni.it() }

            gameIni["Engine.Engine", "Console"] shouldBe listOf("UTRecorder.UTRecorderConsole")
            gameIni["UTRecorder.UTRecorderConsole", "bAutoExit"] shouldBe listOf("True")
            gameIni["UTRecorder.UTRecorderConsole", "MaxRunSeconds"] shouldBe listOf("120")
            systemDirectory.listDirectoryEntries().map { it.name } shouldBe listOf(gameExecutable)
        }
    })
