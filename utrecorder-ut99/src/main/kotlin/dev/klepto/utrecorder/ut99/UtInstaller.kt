package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.install.Installer
import dev.klepto.utrecorder.common.io.Downloader
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.extract
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.ut99.UtDirectory.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Provisioner of Unreal Tournament and its OldUnreal patch into the game directory of a configuration.
 *
 * Use [install] to materialize the installation, then [UtInstallation] to reach it.
 *
 * @property config The installation to provision and the sources it is provisioned from.
 */
@OptIn(ExperimentalPathApi::class)
class UtInstaller(
    private val config: UtConfig,
) : Installer {
    /**
     * The directory where cached files are stored.
     */
    private val cacheDirectory: Path = config.directoriesConfig.cache

    /**
     * The directory the installation is materialized in.
     */
    private val gameDirectory: Path = config.gameDirectory

    /**
     * The version file of the current UT installation.
     */
    private val versionFile: Path = gameDirectory[VERSION_FILE]

    /**
     * Runs UT iso installer, UT patch installer and creates a version file.
     */
    override suspend fun install() {
        if (isInstalled()) {
            log.info { "UT installation detected, skipping..." }
            return
        }

        log.info { "Installing UT..." }
        workspace("ut-installer") {
            installIso()
            installPatch()
            move(gameDirectory)
            versionFile.writeText(config.patchFileName)
        }
        log.info { "UT installation complete" }
    }

    /**
     * Downloads the original UT iso and extracts it to the given [directory].
     */
    private suspend fun Workspace.installIso() {
        val isoPath = cacheDirectory[ISO_FILE]
        Downloader(config.isoUrl, config.isoMd5, config = config.directoriesConfig).download(isoPath)

        log.info { "Extracting UT iso..." }
        isoPath.extract(directory) { it.substringBefore('/') in ISO_ROOTS }.forEach(::track)

        val target = directory[System]
        val source = directory[System400].takeIf { it.isDirectory() } ?: return
        source.move(target)
    }

    /**
     * Downloads the latest patch and extracts it to the given [directory].
     */
    private suspend fun Workspace.installPatch() {
        val patchPath = cacheDirectory[config.patchFileName]
        val patchUrl = "${config.patchUrl}/v${config.patchVersion}/${config.patchFileName}"
        Downloader(patchUrl, config = config.directoriesConfig).download(patchPath)

        log.info { "Extracting UT patch..." }
        patchPath.extract(directory).forEach(::track)
    }

    /**
     * Deletes the UT installation directory.
     */
    override suspend fun uninstall() = withContext(Dispatchers.IO) { gameDirectory.deleteRecursively() }

    /**
     * Checks if the UT installation is complete by checking [versionFile] for the current patch version.
     */
    override suspend fun isInstalled() =
        withContext(Dispatchers.IO) { versionFile.exists() && versionFile.readText().trim() == config.patchFileName }

    companion object {
        /**
         * The root directories of the original UT iso.
         */
        private val ISO_ROOTS =
            listOf(System, System400, Maps, Music, Sounds, Textures, Help, Web)
                .map { it.pathName }
                .toSet()

        /**
         * The name of the file recording which patch an installation was materialized with.
         */
        private const val VERSION_FILE = ".utrecorder.version"

        /**
         * The name the original UT iso is cached under.
         */
        private const val ISO_FILE = "ut99.iso"

        private val log = logger<UtInstaller>()
    }
}
