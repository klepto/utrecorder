package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.install.Installer
import dev.klepto.utrecorder.common.io.Downloader
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.encodeUrlPathSegment
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.headCatching
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.log.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension

/**
 * Provisioner of the packages a demo names into an installation.
 *
 * Use [install] to fetch the packages the installation is missing, and [installed] to read what it fetched.
 *
 * @property packages The packages the demo names, at the GUIDs it recorded.
 * @property installation The installation the packages are provisioned into.
 * @property config The packages directory the packages are copied from and the redirects they are fetched from.
 */
class UtPackageInstaller(
    private val packages: List<UtPackage>,
    private val installation: UtInstallation,
    private val config: UtConfig = UtConfig(),
) : Installer {
    /**
     * The packages fetched so far, in the order they were fetched.
     */
    private val fetched = mutableListOf<UtPackage>()

    /**
     * The sources a package is looked for in, in the order they are tried, as the failure names them.
     */
    private val sources: List<String>
        get() = listOf("${installation.directory}", "${config.directoriesConfig.packages}") + config.redirectUrls

    /**
     * The packages this installer fetched, in the order it fetched them. Empty before [install] runs, and empty after
     * an install that found every package already in place.
     */
    val installed: List<UtPackage> get() = fetched.toList()

    /**
     * Fetches every package the installation does not already hold at the GUID the demo names, copying it from the
     * packages directory first and downloading it from the redirects straight into the installation after. The
     * packages directory is only read. A source serving the package at another GUID is passed over, and a package
     * downloaded at another GUID is discarded. A package fetched before a later one fails is kept, on disk and in
     * [installed].
     *
     * @throws UtPackageException when no source holds one of them at the GUID the demo names.
     */
    override suspend fun install() {
        for (required in packages) {
            if (installedPackage(required) != null) continue
            fetched += copiedPackage(required) ?: fetchedPackage(required) ?: throw UtPackageException(required.name, sources)
        }
    }

    /**
     * Deletes the packages this installer fetched from the installation, leaving the ones it already held.
     */
    override suspend fun uninstall() {
        withContext(Dispatchers.IO) {
            fetched.forEach { it.path?.deleteIfExists() }
        }
        fetched.clear()
    }

    /**
     * Returns `true` when the installation holds every named package at the GUID the demo names.
     */
    override suspend fun isInstalled(): Boolean = packages.all { installedPackage(it) != null }

    /**
     * Returns the package the installation holds satisfying [required], or `null` when it holds none.
     */
    private suspend fun installedPackage(required: UtPackage): UtPackage? =
        UtDirectory.PACKAGES.firstNotNullOfOrNull { directory ->
            val candidates = installation.packages(directory)
            withContext(Dispatchers.IO) { candidates.firstOrNull { it.satisfies(required) } }
        }

    /**
     * Returns the package satisfying [required] copied from the packages directory into the installation, or `null`
     * when the packages directory holds none.
     */
    private suspend fun copiedPackage(required: UtPackage): UtPackage? = localPackage(required)?.let { copyIntoInstallation(it) }

    /**
     * Returns the package the packages directory holds satisfying [required], or `null` when it holds none.
     */
    private suspend fun localPackage(required: UtPackage): UtPackage? {
        val candidates = UtPackage.list(config.directoriesConfig.packages, UtDirectory.PACKAGE_EXTENSIONS)
        return withContext(Dispatchers.IO) { candidates.firstOrNull { it.satisfies(required) } }
    }

    /**
     * Copies [source] into the directory of the installation its extension maps to, replacing whatever the
     * installation holds under that name, and returns the package the installation now holds.
     */
    private suspend fun copyIntoInstallation(source: UtPackage): UtPackage {
        val path = checkNotNull(source.path) { "No file holds the package ${source.name}" }
        return UtPackage.read(path.copy(installationPath(path), overwrite = true))
    }

    /**
     * Returns the package [required] fetched from the first redirect serving it into the installation, or `null`
     * when no redirect serves one at the GUID the demo recorded.
     */
    private suspend fun fetchedPackage(required: UtPackage): UtPackage? = config.redirectUrls.firstNotNullOfOrNull { fetch(it, required) }

    /**
     * Returns the package [required] fetched from the redirect [base] into the installation under the first package
     * extension the redirect serves, or `null` when it serves none, or serves one at another GUID than the demo
     * recorded and the fetched file is discarded.
     */
    private suspend fun fetch(
        base: String,
        required: UtPackage,
    ): UtPackage? {
        val (extension, url) =
            UtDirectory.PACKAGE_EXTENSIONS
                .asSequence()
                .map { it to redirectUrl(base, required.name, it) }
                .firstOrNull { (_, url) -> headCatching(url).isSuccess } ?: return null
        val fileName = "${required.name}.$extension"
        log.info { "Fetching ${required.name} from $url" }

        return workspace("uz-installer", config = config.directoriesConfig) { workspace ->
            val archive = workspace["$fileName.uz"]
            Downloader(url, config = config.directoriesConfig).download(archive)
            val extracted = installation.compiler.extract(archive, workspace[fileName])
            archive.deleteIfExists()
            moveIntoInstallation(UtPackage.read(extracted), required)
        }
    }

    /**
     * Moves the downloaded package [fetched] into the installation and returns the package the installation now
     * holds, or deletes it and returns `null` when it does not satisfy [required].
     */
    private suspend fun moveIntoInstallation(
        fetched: UtPackage,
        required: UtPackage,
    ): UtPackage? {
        val path = checkNotNull(fetched.path) { "No file holds the package ${fetched.name}" }
        if (withContext(Dispatchers.IO) { fetched.satisfies(required) }) return UtPackage.read(path.move(installationPath(path)))

        log.warn { "Discarding ${path.fileName}, its GUID ${fetched.guid} does not match the recorded ${required.guid}" }
        withContext(Dispatchers.IO) { path.deleteIfExists() }
        return null
    }

    /**
     * Returns the path the package file [path] takes in the installation, in the directory its extension maps to.
     */
    private fun installationPath(path: Path): Path {
        val directory = checkNotNull(UtDirectory.of(path.extension)) { "No directory of an installation holds $path" }
        return installation.directory[directory]["${path.fileName}"]
    }

    companion object {
        private val log = logger<UtPackageInstaller>()

        /**
         * Returns the URL the package [name] of [extension] is fetched from at the redirect [base].
         */
        private fun redirectUrl(
            base: String,
            name: String,
            extension: String,
        ): String = "$base/${name.encodeUrlPathSegment()}.$extension.uz"
    }
}

/**
 * Thrown when no source holds [packageName] at the GUID the demo recorded.
 *
 * @property packageName The name of the package that resolved nowhere.
 * @property sources The sources that were tried, in the order they were tried.
 */
class UtPackageException(
    val packageName: String,
    val sources: List<String>,
) : IOException("No source holds $packageName, tried: ${sources.joinToString()}")
