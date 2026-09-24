package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
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

class DemoPlaybackResourcesTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-playback-resources-test")
        val installationDirectory = home / "install"
        (installationDirectory / "System").createDirectories()
        (installationDirectory / "System" / UtPlatform.current().gameExecutable).writeText("")
        val installation =
            UtInstallation(installationDirectory, UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = home)))

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("gameIni carries the patch number that skips the first-run screen") {
            DemoPlaybackResources.gameIni shouldContain "FirstRun=469"
        }

        test("userIni carries the player section") {
            DemoPlaybackResources.userIni shouldContain "[DefaultPlayer]"
        }

        test("installHudLayout replaces the HUD layout in the System directory") {
            installation.systemDirectory["UTBT_HudLayout.ini"].writeText("stale")

            val written = DemoPlaybackResources.installHudLayout(installation)

            written shouldBe installation.systemDirectory["UTBT_HudLayout.ini"]
            written.readText() shouldContain "[HudLayout]"
        }
    })
