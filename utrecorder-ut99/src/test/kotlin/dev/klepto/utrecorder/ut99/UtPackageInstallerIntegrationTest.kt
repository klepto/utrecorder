package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.days

class UtPackageInstallerIntegrationTest :
    FunSpec({
        val packageName = "swJumpPad"
        val packageGuid = "5AC8779640DFA12A546D2B907A0A7D52"
        val home = createTempDirectory("utrecorder-utpackageinstaller-integration")
        val defaults = UtConfig.DEFAULT
        val config = defaults.copy(directories = Directories(home), gameDirectory = defaults.gameDirectory)

        beforeSpec { UtInstaller(defaults).install() }
        afterSpec { home.toFile().deleteRecursively() }

        val installation by lazy { UtInstallation(config.gameDirectory, Workspace(home / "tasks", keep = false, retention = 7.days)) }

        context("install") {
            test("fetches a real package from a real redirect into a real installation") {
                val required = UtPackage.named(packageName, packageGuid)
                val installed = installation.directory[UtDirectory.System]["$packageName.u"]
                val fetched = config.directories.packages["$packageName.u"]
                val installer = UtPackageInstaller(listOf(required), installation, config)

                installer.isInstalled() shouldBe false
                installed.exists() shouldBe false

                try {
                    installer.install()

                    installer.installed.map { it.name } shouldBe listOf(packageName)
                    installer.isInstalled() shouldBe true
                    fetched.exists() shouldBe true
                    installed.exists() shouldBe true
                    UtPackage.read(installed).guid shouldBe packageGuid
                } finally {
                    installer.uninstall()
                }

                installed.exists() shouldBe false
            }
        }
    })
