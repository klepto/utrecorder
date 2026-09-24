package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DemoPlaybackUscriptTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-package-test")
        val installationDirectory = home / "install"
        (installationDirectory / "System").createDirectories()
        (installationDirectory / "System" / UtPlatform.current().gameExecutable).writeText("")
        val installation =
            UtInstallation(installationDirectory, UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = home)))
        val recorderPackage = DemoPlaybackUscript(installation)

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("install writes the embedded UTRecorder.u into the System directory") {
            val written = recorderPackage.install()
            val content = written.single().readBytes()

            written shouldBe listOf(installation.systemDirectory["UTRecorder.u"])
            content shouldBe EmbeddedArtifacts("unrealscript").read("UTRecorder.u")
            // The tag every Unreal package file opens with, little endian.
            content.copyOf(4) shouldBe byteArrayOf(0xC1.toByte(), 0x83.toByte(), 0x2A, 0x9E.toByte())
        }

        test("install overwrites a UTRecorder.u already in the System directory") {
            installation.systemDirectory["UTRecorder.u"].writeText("stale")

            val written = recorderPackage.install()

            written.single().readBytes() shouldBe EmbeddedArtifacts("unrealscript").read("UTRecorder.u")
        }

        test("configure writes the settings to UTRecorder.ini in the System directory") {
            val written = recorderPackage.configure(UtIni("[UTRecorder.UTRecorderConsole]\nbAutoExit=True\n"))

            written shouldBe installation.systemDirectory["UTRecorder.ini"]
            written.readText() shouldContain "bAutoExit=True"
        }

        test("clear deletes the settings file") {
            val written = recorderPackage.configure(UtIni("[UTRecorder.UTRecorderConsole]\nbAutoExit=True\n"))

            recorderPackage.clear()

            written.exists() shouldBe false
        }
    })
