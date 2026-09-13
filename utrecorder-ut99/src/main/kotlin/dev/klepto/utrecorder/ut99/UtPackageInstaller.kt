package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.install.Installer
import dev.klepto.utrecorder.common.io.Downloader
import dev.klepto.utrecorder.common.io.createParentDirectory
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.stage
import dev.klepto.utrecorder.common.log.logger
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension

/**
 * Fetches the packages a demo names into an installation, from the installation itself, from the packages directory
 * and from the configured redirects, in that order.
 *
 * @property packages The packages the demo names, at the GUIDs it recorded.
 * @property installation The installation the packages are provisioned into.
 * @property config The packages directory and the redirects the packages are fetched from.
 */
class UtPackageInstaller(
    private val packages: List<UtPackage>,
    private val installation: UtInstallation,
    private val config: UtConfig = UtConfig.DEFAULT,
) : Installer {
    /**
     * Logger for this class.
     */
    private val log = logger<UtPackageInstaller>()

    /**
     * The packages fetched so far, in the order they were fetched.
     */
    private val fetched = mutableListOf<UtPackage>()

    /**
     * The extensions a package is probed for at a redirect, in the order they are probed.
     */
    private val packageExtensions: List<String>
        get() = UtDirectory.packages.mapNotNull { it.extension }

    /**
     * The sources a package is looked for in, in the order they are tried, as the failure names them.
     */
    private val sources: List<String>
        get() = listOf("${installation.directory}", "${config.packagesDirectory}") + config.redirectUrls

    /**
     * The packages this installer fetched, in the order it fetched them. Empty before [install] runs, and empty after
     * an install that found every package already in place.
     */
    val installed: List<UtPackage> get() = fetched.toList()

    /**
     * Fetches every package the installation does not already hold at the GUID the demo names. A package fetched
     * before a later one fails is kept, on disk and in [installed].
     *
     * @throws UtPackageException when no source holds one of them.
     */
    override fun install() {
        for (required in packages) {
            if (installedPackage(required) != null) continue
            val source = localPackage(required) ?: fetchedPackage(required) ?: throw UtPackageException(required.name, sources)
            fetched += copyIntoInstallation(source)
        }
    }

    /**
     * Deletes the packages this installer fetched from the installation. A package the installation already held is
     * not this installer's to remove, so [uninstall] before an [install] deletes nothing.
     */
    override fun uninstall() {
        fetched.forEach { it.path?.deleteIfExists() }
        fetched.clear()
    }

    /**
     * Returns `true` when the installation holds every named package at the GUID the demo names.
     */
    override fun isInstalled(): Boolean = packages.all { installedPackage(it) != null }

    /**
     * Returns the package the installation holds satisfying [required], or `null` when it holds none.
     */
    private fun installedPackage(required: UtPackage): UtPackage? =
        UtDirectory.packages.firstNotNullOfOrNull { directory ->
            installation.packages(directory).firstOrNull { it.satisfies(required) }
        }

    /**
     * Returns the package the packages directory holds satisfying [required], or `null` when it holds none.
     */
    private fun localPackage(required: UtPackage): UtPackage? = packagesIn(config.packagesDirectory).firstOrNull { it.satisfies(required) }

    /**
     * Returns the package [required] fetched into the packages directory from the first redirect serving it, or
     * `null` when no redirect does.
     */
    private fun fetchedPackage(required: UtPackage): UtPackage? = config.redirectUrls.firstNotNullOfOrNull { fetch(it, required) }

    /**
     * Returns the package [required] fetched from the redirect [base] into the packages directory, or `null` when the
     * redirect serves it under no package extension.
     *
     * The redirect names a file rather than a package, so every package extension is probed in turn and the first one
     * it answers is the one that is fetched. The archive is decompressed in a staging directory beside the file it
     * becomes, so that a failed decode leaves no half-written package behind.
     */
    private fun fetch(
        base: String,
        required: UtPackage,
    ): UtPackage? {
        val extension = packageExtensions.firstOrNull { Downloader(redirectUrl(base, required.name, it)).exists() } ?: return null
        val url = redirectUrl(base, required.name, extension)
        val target = config.packagesDirectory["${required.name}.$extension"]
        log.info { "Fetching ${required.name} from $url" }

        target.stage { staging ->
            val archive = staging["${target.fileName}.uz"]
            Downloader(url).download(archive)
            val extracted = installation.compiler.extract(archive, staging["${target.fileName}"])
            archive.deleteIfExists()
            extracted.move(target)
        }
        return UtPackage.read(target)
    }

    /**
     * Copies [source] into the directory of the installation its extension maps to, replacing whatever the
     * installation holds under that name, and returns the package the installation now holds.
     */
    private fun copyIntoInstallation(source: UtPackage): UtPackage {
        val path = checkNotNull(source.path) { "No file holds the package ${source.name}" }
        val target = installationPath(path)
        target.createParentDirectory()
        path.copyTo(target, overwrite = true)
        return UtPackage.read(target)
    }

    /**
     * Returns the path the package file [path] takes in the installation, in the directory its extension maps to.
     */
    private fun installationPath(path: Path): Path {
        val directory = checkNotNull(UtDirectory.of(path.extension)) { "No directory of an installation holds $path" }
        return installation.directory[directory]["${path.fileName}"]
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

/**
 * Returns the URL a package named [name] with the extension [extension] would be fetched from at the redirect [base].
 * The name is URL-encoded, with `+` rewritten to `%20`, because a redirect serves a file name rather than a form.
 */
internal fun redirectUrl(
    base: String,
    name: String,
    extension: String,
): String {
    val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
    return "$base/$encoded.$extension.uz"
}
