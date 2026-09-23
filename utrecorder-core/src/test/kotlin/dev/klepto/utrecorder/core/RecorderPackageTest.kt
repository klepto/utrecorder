package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtConfig
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

class RecorderPackageTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-package-test")
        val installationDirectory = home / "install"
        val gameExecutable = UtPlatform.current().gameExecutable
        installationDirectory.resolve("System").createDirectories()
        installationDirectory.resolve("System/$gameExecutable").writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(DirectoriesConfig(home)))

        // The tag every Unreal package file opens with, little endian.
        val packageTag = byteArrayOf(0xC1.toByte(), 0x83.toByte(), 0x2A, 0x9E.toByte())

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("the package lands in the installation's System directory under the name the console is named by") {
            val written = RecorderPackage(installation).install()

            written shouldBe listOf(installation.systemDirectory["UTRecorder.u"])
            written.single().exists() shouldBe true
        }

        test("the file written is the compiled package the uscript module embeds") {
            val written = RecorderPackage(installation).install()
            val content = written.single().readBytes()

            content shouldBe EmbeddedArtifacts("unrealscript").read("UTRecorder.u")
            content.take(packageTag.size) shouldBe packageTag
        }

        test("installing over an earlier run's package replaces it") {
            installation.systemDirectory["UTRecorder.u"].writeText("stale")

            val written = RecorderPackage(installation).install()

            written.single().readBytes() shouldBe EmbeddedArtifacts("unrealscript").read("UTRecorder.u")
        }

        test("the console settings are written as the ini the installed console reads, the switch it does nothing without") {
            val written = RecorderPackage(installation).configure(UtIni("[UTRecorder.UTRecorderConsole]\nbAutoExit=True\n"))

            written shouldBe installation.systemDirectory["UTRecorder.ini"]
            written.readText() shouldContain "bAutoExit=True"
        }

        test("clearing the settings leaves the installation without a console configuration") {
            val recorderPackage = RecorderPackage(installation)
            val written = recorderPackage.configure(UtIni("[UTRecorder.UTRecorderConsole]\nbAutoExit=True\n"))

            recorderPackage.clear()

            written.exists() shouldBe false
        }
    })
