package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.Installer
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.extract
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.listFiles
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.replaceIfChanged
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
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Provisioner of Unreal Tournament and its OldUnreal patch into the game directory of a configuration.
 *
 * Next to the game files, the installer writes the base manifest `.utrecorder.base`: one line per file the iso and the
 * patch materialized, relative to the game directory with `/` as separator, in the case the file has on disk.
 *
 * @property config The installation to provision and the sources it is provisioned from.
 */
@OptIn(ExperimentalPathApi::class)
class UtInstaller(
    private val config: UtConfig = UtConfig(),
) : Installer {
    /**
     * File in the game directory that holds the name of the patch the installation was materialized with.
     */
    private val versionFile: Path = config.gameDirectory[VERSION_FILE]

    /**
     * File in the game directory that lists the core game files of the installation: the base manifest.
     */
    private val manifestFile: Path = config.gameDirectory[MANIFEST_FILE]

    /**
     * Downloads and extracts the iso, then the patch. The iso download counts for most of the progress reported to
     * [onProgress], and each extraction is reported once it finishes.
     *
     * Writes the base manifest of the installation as well. An installation that is installed already but has no
     * manifest gets one from the entry listings of the cached iso and patch, downloading those that the cache no longer
     * holds, and keeps every other file in place.
     */
    override suspend fun install(onProgress: ProgressListener<Unit>) {
        if (isInstalled()) {
            log.debug { "UT installation detected, skipping..." }
            if (!manifestFile.fileExists()) writeManifestFromArchives(onProgress)
            onProgress(Progress(1.0))
            return
        }

        log.debug { "Installing UT..." }
        workspace("ut-installer") {
            val isoFiles = installIso(onProgress.part(0.0, 0.8))
            val patchFiles = installPatch(onProgress.part(0.8, 1.0))
            move(config.gameDirectory)
            writeManifest(isoFiles + patchFiles)
            withContext(Dispatchers.IO) { versionFile.writeText(config.patchFileName) }
        }
        onProgress(Progress(1.0))
        log.debug { "UT installation complete" }
    }

    /**
     * Writes the base manifest of an installed game from the entry listings of the iso and the patch, reporting
     * progress to [onProgress]. Downloads each archive that the cache does not hold, and extracts nothing.
     */
    private suspend fun writeManifestFromArchives(onProgress: ProgressListener<Unit>) {
        log.debug { "Writing UT base manifest from the cached archives..." }
        val isoFiles = downloadIso(onProgress.part(0.0, 0.8)).listFiles(::isIsoEntry).map(::installedPath)
        val patchFiles = downloadPatch(onProgress.part(0.8, 1.0)).listFiles()
        writeManifest(isoFiles + patchFiles)
    }

    /**
     * Writes [files], each relative to the game directory with `/` as separator, as the base manifest of the
     * installation: one path per line, each once, in the case the file has on disk.
     */
    private suspend fun writeManifest(files: Collection<String>) {
        val names =
            withContext(Dispatchers.IO) {
                val root = config.gameDirectory.toRealPath()
                files.map { root.nameOnDisk(it) }
            }
        val content = names.distinct().sorted().joinToString("") { "$it\n" }
        manifestFile.replaceIfChanged(content.toByteArray(Charsets.UTF_8), config.directoriesConfig)
    }

    /**
     * Returns [file], a path relative to this directory with `/` as separator, in the case the file has on disk. A
     * filesystem that ignores case keeps the name a file was first created with, whatever the case of a later write to
     * it. If no file exists at [file], returns it as given.
     */
    private fun Path.nameOnDisk(file: String): String {
        val path = resolve(file)
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return file
        return relativize(path.toRealPath(LinkOption.NOFOLLOW_LINKS)).invariantSeparatorsPathString
    }

    /**
     * Downloads the original Unreal Tournament iso into the cache, reporting progress to [onProgress], and returns its
     * path. An iso the cache holds already is kept.
     */
    private suspend fun downloadIso(onProgress: ProgressListener<Unit>): Path {
        val isoPath = config.directoriesConfig.cache[ISO_FILE]
        Downloader(config.isoUrl, config.isoMd5, directories = config.directoriesConfig).download(isoPath, onProgress)
        return isoPath
    }

    /**
     * Downloads the configured OldUnreal patch into the cache, reporting progress to [onProgress], and returns its
     * path. A patch the cache holds already is kept.
     */
    private suspend fun downloadPatch(onProgress: ProgressListener<Unit>): Path {
        val patchPath = config.directoriesConfig.cache[config.patchFileName]
        val patchUrl = "${config.patchUrl}/v${config.patchVersion}/${config.patchFileName}"
        Downloader(patchUrl, directories = config.directoriesConfig).download(patchPath, onProgress)
        return patchPath
    }

    /**
     * Downloads the original Unreal Tournament iso and extracts its game directories into this workspace, reporting
     * progress to [onProgress]. Returns the extracted files at their final paths relative to the game directory.
     *
     * The contents of `System400` are merged into `System`.
     */
    private suspend fun Workspace.installIso(onProgress: ProgressListener<Unit>): List<String> {
        val isoPath = downloadIso(onProgress.part(0.0, 0.75))

        log.debug { "Extracting UT iso..." }
        val extracted = isoPath.extract(directory, ::isIsoEntry).onEach(::track)
        val files = relativeFiles(extracted).map(::installedPath)

        val system400 = directory[System400]
        if (withContext(Dispatchers.IO) { system400.isDirectory() }) system400.move(directory[System])
        onProgress(Progress(1.0))
        return files
    }

    /**
     * Downloads the configured OldUnreal patch and extracts it into this workspace, reporting progress to [onProgress].
     * Returns the extracted files relative to the game directory.
     */
    private suspend fun Workspace.installPatch(onProgress: ProgressListener<Unit>): List<String> {
        val patchPath = downloadPatch(onProgress.part(0.0, 0.5))

        log.debug { "Extracting UT patch..." }
        val extracted = patchPath.extract(directory).onEach(::track)
        onProgress(Progress(1.0))
        return relativeFiles(extracted)
    }

    /**
     * Returns the paths in [extracted] that are files, relative to the directory of this workspace with `/` as
     * separator.
     */
    private suspend fun Workspace.relativeFiles(extracted: Set<Path>): List<String> =
        withContext(Dispatchers.IO) {
            val root = directory.toAbsolutePath().normalize()
            extracted
                .filterNot { it.isDirectory() }
                .map { root.relativize(it.toAbsolutePath().normalize()).invariantSeparatorsPathString }
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
         * Name of the file that lists the core game files of an installation.
         */
        private const val MANIFEST_FILE = ".utrecorder.base"

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

        /**
         * Returns `true` if the iso entry [name] lies in one of the [ISO_ROOTS] directories.
         */
        private fun isIsoEntry(name: String): Boolean = name.substringBefore('/') in ISO_ROOTS

        /**
         * Returns the path that the iso entry [name] has once installed: an entry of `System400` lies in `System`.
         */
        private fun installedPath(name: String): String =
            when {
                name.startsWith("${System400.pathName}/") -> "${System.pathName}/${name.substringAfter('/')}"
                else -> name
            }
    }
}
