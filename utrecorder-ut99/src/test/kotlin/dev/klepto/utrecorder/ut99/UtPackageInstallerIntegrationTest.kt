package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.get
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

/**
 * Tests [UtPackageInstaller] against a real Unreal Tournament installation and a real redirect: that it downloads a
 * package neither the installation nor an empty packages directory holds, and that [UtPackageInstaller.uninstall]
 * deletes it again.
 *
 * Runs only under `./gradlew :utrecorder-ut99:integrationTest`. The installation is built in a temporary home on every
 * run. Its downloads go to the cache directory that the `utrecorder.test.cache` system property names, so a later run
 * reuses them. Without the property, they go to the cache directory of the temporary home.
 */
class UtPackageInstallerIntegrationTest :
    FunSpec({
        val packageName = "swJumpPad"
        val packageGuid = "5AC8779640DFA12A546D2B907A0A7D52"
        val home = createTempDirectory("utrecorder-utpackageinstaller-integration")
        val reader = ConfigReader { mapOf("cache" to System.getProperty("utrecorder.test.cache"))[it] }
        val config = UtConfig(ConfigReader.EMPTY, DirectoriesConfig(reader, home = home))

        beforeSpec { UtInstaller(config).install() }
        afterSpec { home.toFile().deleteRecursively() }

        val installation by lazy { UtInstallation(config.gameDirectory, config) }

        context("install") {
            test("downloads the package from a redirect into the installation at the recorded GUID and uninstall deletes it") {
                val required = UtPackage.named(packageName, packageGuid)
                val installed = installation.directory[UtDirectory.System]["$packageName.u"]
                val packagesDirectoryFile = config.directoriesConfig.packages["$packageName.u"]
                val installer = UtPackageInstaller(listOf(required), installation, config)

                installer.isInstalled() shouldBe false
                installed.exists() shouldBe false

                try {
                    installer.install()

                    installer.installed.map { it.name } shouldBe listOf(packageName)
                    installer.isInstalled() shouldBe true
                    packagesDirectoryFile.exists() shouldBe false
                    installed.exists() shouldBe true
                    UtPackage.read(installed).guid() shouldBe packageGuid
                } finally {
                    installer.uninstall()
                }

                installed.exists() shouldBe false
            }
        }
    })
