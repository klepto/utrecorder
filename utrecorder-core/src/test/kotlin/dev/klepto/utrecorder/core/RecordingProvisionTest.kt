package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText

class RecordingProvisionTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-provision-test")
        val installationDirectory = home / "install"
        val gameExecutable = UtPlatform.current().gameExecutable
        installationDirectory.resolve("System").createDirectories()
        installationDirectory.resolve("System/$gameExecutable").createDirectories()
        val config = RecorderConfig(installationDirectory = installationDirectory)
        val installation = UtInstallation(installationDirectory, Directories(home))
        val provision = RecordingProvision(installation, config)

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("hook extraction writes the shim and the launcher into the instance workspace as the paths the launch environment names") {
            val paths = provision.hooks()

            paths shouldHaveSize 2
            paths.forEach { path -> path.exists() shouldBe true }
            paths.map { it.fileName.toString() } shouldBe setOf("uthooks.dll", "utinject.exe")
        }

        test("the OpenAL environment points at the workspace conf and the conf holds the relative wave path and the wave driver") {
            val environment = provision.openAl()

            val conf = environment.getValue("ALSOFT_CONF")
            conf shouldContain "alsoft.conf"
            val confFile = Path.of(conf)
            confFile.exists() shouldBe true
            val content = confFile.readText()
            content shouldContain "drivers = wave"
            content shouldContain "file = audio.wav"
            content shouldContain "channels = stereo"
            content shouldContain "sample-type = int16"
            environment["ALSOFT_DRIVERS"] shouldBe "wave"
            environment["ALSOFT_LOGLEVEL"] shouldBe "2"
        }

        test("audio off produces an empty OpenAL environment and no wave conf") {
            val audioOffProvision = RecordingProvision(installation, config.copy(audio = false))

            val environment = audioOffProvision.openAl()

            environment shouldBe emptyMap()
        }
    })
