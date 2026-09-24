package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.Installer
import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.http.encodeUrlPathSegment
import dev.klepto.utrecorder.common.http.headCatching
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension

/**
 * Provisioner of the packages a demo names into an installation.
 *
 * [install] fetches every package the installation does not already hold at the GUID the demo names. It copies the
 * package from the packages directory first, and downloads it from the redirects straight into the installation after.
 * The packages directory is only read. A source serving the package at another GUID is passed over, and a package
 * downloaded at another GUID is discarded. When no source holds a package at the GUID the demo names, [install] throws
 * [UtPackageException]. A package fetched before a later one fails is kept, on disk and in [installed].
 *
 * [uninstall] deletes only the packages this installer fetched, and leaves the ones the installation already held.
 *
 * @property packages The packages the demo names, at the GUIDs it recorded.
 * @property installation The installation the packages are provisioned into.
 * @property config The packages directory the packages are copied from and the redirects they are downloaded from.
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
     * The sources a package is looked for in, in the order they are tried, as [UtPackageException] names them.
     */
    private val sources: List<String>
        get() = listOf("${installation.directory}", "${config.directoriesConfig.packages}") + config.redirectUrls

    /**
     * The packages this installer fetched, in the order it fetched them. Empty before [install] runs, and empty after
     * an install that found every package already in place.
     */
    val installed: List<UtPackage> get() = fetched.toList()

    override suspend fun install() {
        for (required in packages) {
            if (installedPackage(required) != null) continue
            fetched += copiedPackage(required) ?: downloadedPackage(required) ?: throw UtPackageException(required.name, sources)
        }
    }

    override suspend fun uninstall() {
        withContext(Dispatchers.IO) {
            fetched.forEach { it.path?.deleteIfExists() }
        }
        fetched.clear()
    }

    override suspend fun isInstalled(): Boolean = packages.all { installedPackage(it) != null }

    /**
     * Returns the package the installation holds satisfying [required]. If the installation holds none, returns `null`
     * instead.
     */
    private suspend fun installedPackage(required: UtPackage): UtPackage? =
        UtDirectory.PACKAGES.firstNotNullOfOrNull { firstSatisfying(installation.packages(it), required) }

    /**
     * Returns the package satisfying [required] copied from the packages directory into the installation. If the
     * packages directory holds none, returns `null` instead.
     */
    private suspend fun copiedPackage(required: UtPackage): UtPackage? = localPackage(required)?.let { copyIntoInstallation(it) }

    /**
     * Returns the package the packages directory holds satisfying [required]. If the packages directory holds none,
     * returns `null` instead.
     */
    private suspend fun localPackage(required: UtPackage): UtPackage? =
        firstSatisfying(UtPackage.list(config.directoriesConfig.packages, UtDirectory.PACKAGE_EXTENSIONS), required)

    /**
     * Copies [source] into the directory of the installation its extension maps to, replacing whatever the
     * installation holds under that name, and returns the package the installation now holds.
     */
    private suspend fun copyIntoInstallation(source: UtPackage): UtPackage {
        val path = checkNotNull(source.path) { "No file holds the package ${source.name}" }
        return UtPackage.read(path.copy(installationPath(path), overwrite = true))
    }

    /**
     * Returns the package [required] downloaded from the first redirect serving it into the installation. If no
     * redirect serves one at the GUID the demo recorded, returns `null` instead.
     */
    private suspend fun downloadedPackage(required: UtPackage): UtPackage? =
        config.redirectUrls.firstNotNullOfOrNull { download(it, required) }

    /**
     * Returns the package [required] downloaded from the redirect [base] into the installation, under the first
     * package extension the redirect serves. If the redirect serves none, returns `null` instead. If it serves one at
     * another GUID than the demo recorded, discards the downloaded file and returns `null`.
     */
    private suspend fun download(
        base: String,
        required: UtPackage,
    ): UtPackage? {
        val extension = servedExtension(base, required.name) ?: return null
        val url = redirectUrl(base, required.name, extension)
        val fileName = "${required.name}.$extension"
        log.info { "Fetching ${required.name} from $url" }

        return workspace("uz-installer", config = config.directoriesConfig) { workspace ->
            val archive = workspace["$fileName.uz"]
            Downloader(url, directories = config.directoriesConfig).download(archive)
            val extracted = installation.compiler.extract(archive, workspace[fileName])
            archive.deleteIfExists()
            moveIntoInstallation(UtPackage.read(extracted), required)
        }
    }

    /**
     * Moves the downloaded package [downloaded] into the installation and returns the package the installation now
     * holds. If [downloaded] does not satisfy [required], deletes it and returns `null` instead.
     */
    private suspend fun moveIntoInstallation(
        downloaded: UtPackage,
        required: UtPackage,
    ): UtPackage? {
        val path = checkNotNull(downloaded.path) { "No file holds the package ${downloaded.name}" }
        if (withContext(Dispatchers.IO) { downloaded.satisfies(required) }) return UtPackage.read(path.move(installationPath(path)))

        log.warn { "Discarding ${path.fileName}, its GUID ${downloaded.guid} does not match the recorded ${required.guid}" }
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
         * Returns the first of [candidates] that satisfies [required]. If none does, returns `null` instead.
         */
        private suspend fun firstSatisfying(
            candidates: List<UtPackage>,
            required: UtPackage,
        ): UtPackage? = withContext(Dispatchers.IO) { candidates.firstOrNull { it.satisfies(required) } }

        /**
         * Returns the first package extension the redirect [base] serves the package [name] under. If it serves none,
         * returns `null` instead.
         */
        private suspend fun servedExtension(
            base: String,
            name: String,
        ): String? = UtDirectory.PACKAGE_EXTENSIONS.firstOrNull { headCatching(redirectUrl(base, name, it)).isSuccess }

        /**
         * Returns the URL the package [name] of [extension] is downloaded from at the redirect [base].
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
