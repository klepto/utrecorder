package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists

/**
 * The package the install is proven against: a Bunny Track package the demo fixture names, which no installation ships
 * and every configured redirect serves, so that the fetch is real rather than a local hit.
 */
private const val PACKAGE_NAME = "swJumpPad"

/**
 * The GUID the demo fixture records [PACKAGE_NAME] at, which the file the redirect serves has to carry for the
 * installer to accept it.
 */
private const val PACKAGE_GUID = "5AC8779640DFA12A546D2B907A0A7D52"

/**
 * Tests [UtPackageInstaller.install] against a real redirect and a real Unreal Tournament installation, pinning the
 * assumptions the local HTTP server and the fake compiler in [UtPackageInstallerTest] cannot reach: that a redirect
 * answers the URL the installer builds, that a real `.uz` decodes through the installation's own compiler, and that
 * the package it decodes to carries the GUID the demo recorded.
 *
 * Opt-in. The installation is downloaded on the first run and the package is fetched over the network, so this spec is
 * excluded from `test` and only runs under `./gradlew :utrecorder-ut99:integrationTest`.
 *
 * @author Augustinas R. <http://github.com/klepto>
 */
class UtPackageInstallerIntegrationTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-utpackageinstaller-integration")
        val defaults = UtConfig.DEFAULT

        /**
         * The installation is the shared one, so that a run costs no second download, while the packages directory is
         * a temporary one, so that the package can only come from a redirect.
         */
        val config = defaults.copy(directories = Directories(home), gameDirectory = defaults.gameDirectory)

        beforeSpec { UtInstaller(defaults).install() }
        afterSpec { home.toFile().deleteRecursively() }

        val installation by lazy { UtInstallation(config.gameDirectory, Workspace(directory = home / "tasks")) }

        context("install") {
            test("fetches a real package from a real redirect into a real installation") {
                val required = UtPackage.named(PACKAGE_NAME, PACKAGE_GUID)
                val installed = installation.directory[UtDirectory.System]["$PACKAGE_NAME.u"]
                val fetched = config.packagesDirectory["$PACKAGE_NAME.u"]
                val installer = UtPackageInstaller(listOf(required), installation, config)

                installer.isInstalled() shouldBe false
                installed.exists() shouldBe false

                try {
                    installer.install()

                    installer.installed.map { it.name } shouldBe listOf(PACKAGE_NAME)
                    installer.isInstalled() shouldBe true
                    fetched.exists() shouldBe true
                    installed.exists() shouldBe true
                    UtPackage.read(installed).guid shouldBe PACKAGE_GUID
                } finally {
                    installer.uninstall()
                }

                installed.exists() shouldBe false
            }
        }
    })
