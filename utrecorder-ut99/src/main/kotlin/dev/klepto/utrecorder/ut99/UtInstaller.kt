package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.install.Installer
import dev.klepto.utrecorder.common.io.Downloader
import dev.klepto.utrecorder.common.io.extract
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.stage
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.ut99.UtDirectory.System
import dev.klepto.utrecorder.ut99.UtDirectory.System400
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Downloads Unreal Tournament and the latest patch, and materializes them into the configured game directory.
 *
 * @property config The installation to provision and the sources it is provisioned from.
 */
@OptIn(ExperimentalPathApi::class)
class UtInstaller(
    private val config: UtConfig = UtConfig.DEFAULT,
) : Installer {
    /**
     * Logger for this class.
     */
    private val log = logger<UtInstaller>()

    /**
     * The directory the installation is materialized in.
     */
    private val directory: Path = config.gameDirectory

    /**
     * The version file of the current UT installation.
     */
    private val versionFile = directory[VERSION_FILE]

    /**
     * Runs UT iso installer, UT patch installer and creates a version file.
     */
    override fun install() {
        if (isInstalled()) {
            log.info { "UT installation detected, skipping..." }
            return
        }

        log.info { "Installing UT..." }
        directory.stage { staging ->
            installIso(staging)
            installPatch(staging)
            staging.move(directory)
            versionFile.writeText(config.patchFileName)
        }
        log.info { "UT installation complete" }
    }

    /**
     * Downloads the original UT iso and extracts it to the given [directory].
     */
    private fun installIso(directory: Path) {
        val isoPath = config.cacheDirectory[ISO_FILE]
        Downloader(config.isoUrl, config.isoMd5).download(isoPath)
        isoPath.extract(directory) { it.substringBefore('/') in ISO_ROOTS }

        val target = directory[System]
        val source = directory[System400].takeIf { it.isDirectory() } ?: return
        source.move(target)
    }

    /**
     * Downloads the latest patch and extracts it to the given [directory].
     */
    private fun installPatch(directory: Path) {
        val patchPath = config.cacheDirectory[config.patchFileName]
        val patchUrl = "${config.patchUrl}/v${config.patchVersion}/${config.patchFileName}"
        Downloader(patchUrl).download(patchPath)
        patchPath.extract(directory)
    }

    /**
     * Deletes the UT installation directory.
     */
    override fun uninstall() = directory.deleteRecursively()

    /**
     * Checks if the UT installation is complete by checking [versionFile] for the current patch version.
     */
    override fun isInstalled() = versionFile.exists() && versionFile.readText().trim() == config.patchFileName

    companion object {
        /**
         * The root directories of the original UT iso.
         */
        private val ISO_ROOTS = setOf("System", "System400", "Maps", "Music", "Sounds", "Textures", "Help", "Web")

        /**
         * The name of the file recording which patch an installation was materialized with.
         */
        private const val VERSION_FILE = ".utrecorder.version"

        /**
         * The name the original UT iso is cached under.
         */
        private const val ISO_FILE = "ut99.iso"
    }
}
