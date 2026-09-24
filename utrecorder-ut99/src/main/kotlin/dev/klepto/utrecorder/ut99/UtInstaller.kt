package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.Installer
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.asDownloadListener
import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.extract
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.part
import dev.klepto.utrecorder.ut99.UtDirectory.Help
import dev.klepto.utrecorder.ut99.UtDirectory.Maps
import dev.klepto.utrecorder.ut99.UtDirectory.Music
import dev.klepto.utrecorder.ut99.UtDirectory.Sounds
import dev.klepto.utrecorder.ut99.UtDirectory.System
import dev.klepto.utrecorder.ut99.UtDirectory.System400
import dev.klepto.utrecorder.ut99.UtDirectory.Textures
import dev.klepto.utrecorder.ut99.UtDirectory.Web
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
 * @property config The installation to provision and the sources it is provisioned from.
 */
@OptIn(ExperimentalPathApi::class)
class UtInstaller(
    private val config: UtConfig,
) : Installer {
    /**
     * File in the game directory that holds the name of the patch the installation was materialized with.
     */
    private val versionFile: Path = config.gameDirectory[VERSION_FILE]

    /**
     * Downloads and extracts the iso, then the patch. The iso download counts for most of the progress reported to
     * [onProgress], and each extraction is reported once it finishes.
     */
    override suspend fun install(onProgress: ProgressListener) {
        if (isInstalled()) {
            log.info { "UT installation detected, skipping..." }
            onProgress(1.0)
            return
        }

        log.info { "Installing UT..." }
        workspace("ut-installer") {
            installIso(onProgress.part(0.0, 0.8))
            installPatch(onProgress.part(0.8, 1.0))
            move(config.gameDirectory)
            withContext(Dispatchers.IO) { versionFile.writeText(config.patchFileName) }
        }
        onProgress(1.0)
        log.info { "UT installation complete" }
    }

    /**
     * Downloads the original Unreal Tournament iso and extracts its game directories into this workspace, reporting
     * progress to [onProgress].
     *
     * The contents of `System400` are merged into `System`.
     */
    private suspend fun Workspace.installIso(onProgress: ProgressListener) {
        val isoPath = config.directoriesConfig.cache[ISO_FILE]
        val downloader = Downloader(config.isoUrl, config.isoMd5, directories = config.directoriesConfig)
        downloader.download(isoPath, onProgress.part(0.0, 0.75).asDownloadListener())

        log.info { "Extracting UT iso..." }
        isoPath.extract(directory) { it.substringBefore('/') in ISO_ROOTS }.forEach(::track)

        val system400 = directory[System400]
        if (withContext(Dispatchers.IO) { system400.isDirectory() }) system400.move(directory[System])
        onProgress(1.0)
    }

    /**
     * Downloads the configured OldUnreal patch and extracts it into this workspace, reporting progress to [onProgress].
     */
    private suspend fun Workspace.installPatch(onProgress: ProgressListener) {
        val patchPath = config.directoriesConfig.cache[config.patchFileName]
        val patchUrl = "${config.patchUrl}/v${config.patchVersion}/${config.patchFileName}"
        val downloader = Downloader(patchUrl, directories = config.directoriesConfig)
        downloader.download(patchPath, onProgress.part(0.0, 0.5).asDownloadListener())

        log.info { "Extracting UT patch..." }
        patchPath.extract(directory).forEach(::track)
        onProgress(1.0)
    }

    override suspend fun uninstall() = withContext(Dispatchers.IO) { config.gameDirectory.deleteRecursively() }

    override suspend fun isInstalled() =
        withContext(Dispatchers.IO) { versionFile.exists() && versionFile.readText().trim() == config.patchFileName }

    companion object {
        /**
         * Name of the file that records which patch an installation was materialized with.
         */
        private const val VERSION_FILE = ".utrecorder.version"

        /**
         * Name the original Unreal Tournament iso is cached under.
         */
        private const val ISO_FILE = "ut99.iso"

        /**
         * Names of the top-level directories extracted from the original Unreal Tournament iso.
         */
        private val ISO_ROOTS: Set<String> =
            listOf(System, System400, Maps, Music, Sounds, Textures, Help, Web)
                .map { it.pathName }
                .toSet()

        private val log = logger<UtInstaller>()
    }
}
