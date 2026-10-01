package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
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

        test("install leaves a UTRecorder.u that already holds the package untouched") {
            recorderPackage.install()
            installation.systemDirectory["UTRecorder.u"].setLastModifiedTime(FileTime.fromMillis(0))

            recorderPackage.install()

            installation.systemDirectory["UTRecorder.u"].getLastModifiedTime() shouldBe FileTime.fromMillis(0)
        }
    })
