package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RecorderSettingsTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-settings-test")
        val installationDirectory = home / "install"
        installationDirectory.resolve("System").createDirectories()
        installationDirectory.resolve("System/${UtPlatform.current().gameExecutable}").writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(DirectoriesConfig(home)))

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("the game configuration carries the patch number that skips the first-run screen") {
            RecorderSettings.gameIni shouldContain "FirstRun=469"
        }

        test("the user configuration carries the player section") {
            RecorderSettings.userIni shouldContain "[DefaultPlayer]"
        }

        test("the HUD layout lands in the installation's System directory over the one it held") {
            installation.systemDirectory["UTBT_HudLayout.ini"].writeText("stale")

            val written = RecorderSettings.install(installation)

            written shouldBe installation.systemDirectory["UTBT_HudLayout.ini"]
            written.readText() shouldContain "[HudLayout]"
        }
    })
