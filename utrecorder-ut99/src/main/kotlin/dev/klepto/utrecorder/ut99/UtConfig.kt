package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path

/**
 * Everything the Unreal Tournament module needs from its environment.
 *
 * Use [DEFAULT] for the configuration the current process works against, or [fromProperties] to build one from a given
 * [PropertySource].
 *
 * @property directories The directories the installation is materialized under.
 * @property gameDirectory The directory where the game is installed. Named separately from [directories], so that
 * callers that cannot share one installation can each name their own.
 * @property isoUrl The URL of the original UT ISO file.
 * @property isoMd5 The MD5 hash of the original UT ISO file.
 * @property patchUrl The URL of the UT patch releases.
 * @property patchVersion The version of the UT patch to use.
 * @property redirectUrls The base URLs a package a demo names is fetched from when neither the installation nor the
 * packages directory holds it. They serve plain HTTP, and no redirect in the UT ecosystem serves TLS, so a package is
 * fetched over an unauthenticated connection. A `.u` package is UnrealScript bytecode the game executes and the GUID
 * check reads a field the downloaded file itself supplies, so whoever can answer the request can serve code.
 */
data class UtConfig(
    val directories: Directories = Directories.DEFAULT,
    val gameDirectory: Path = directories.installs[GAME_DIRECTORY],
    val isoUrl: String = DEFAULT_ISO_URL,
    val isoMd5: String = DEFAULT_ISO_MD5,
    val patchUrl: String = DEFAULT_PATCH_URL,
    val patchVersion: String = DEFAULT_PATCH_VERSION,
    val redirectUrls: List<String> = DEFAULT_REDIRECT_URLS,
) {
    /**
     * The directory where game packages are stored.
     */
    val packagesDirectory: Path = directories.packages

    /**
     * The directory where downloads are cached.
     */
    val cacheDirectory: Path = directories.cache

    /**
     * The name of the patch file this configuration installs on the current platform.
     */
    val patchFileName: String = UT_PLATFORM.patchFileName(patchVersion)

    companion object {
        /**
         * The name the installation takes within the installs directory.
         */
        private const val GAME_DIRECTORY = "ut99"

        /**
         * The URL the original UT ISO is fetched from by default.
         */
        private const val DEFAULT_ISO_URL = "https://archive.org/download/ut-goty/UT_GOTY_CD1.iso"

        /**
         * The MD5 hash the fetched ISO is verified against by default.
         */
        private const val DEFAULT_ISO_MD5 = "e5127537f44086f5ed36a9d29f992c00"

        /**
         * The URL the patch releases are fetched from by default.
         */
        private const val DEFAULT_PATCH_URL = "https://github.com/OldUnreal/UnrealTournamentPatches/releases/download"

        /**
         * The patch version an installation is materialized with by default.
         */
        private const val DEFAULT_PATCH_VERSION = "469e"

        /**
         * The redirects a package is fetched from by default.
         */
        private val DEFAULT_REDIRECT_URLS = listOf("http://api.utbt.net/redirect", "http://uz.ut-files.com")

        /**
         * The configuration a caller that names none works against. Resolved on first use rather than at class load,
         * so that the order in which classes happen to load cannot decide what a process installs.
         */
        val DEFAULT: UtConfig by lazy { fromProperties() }

        /**
         * Builds the configuration [properties] holds, against the given [directories].
         */
        fun fromProperties(
            properties: PropertySource = PropertySource.DEFAULT,
            directories: Directories = Directories.DEFAULT,
        ): UtConfig =
            UtConfig(
                directories = directories,
                isoUrl = properties["iso_url"] ?: DEFAULT_ISO_URL,
                isoMd5 = properties["iso_md5"] ?: DEFAULT_ISO_MD5,
                patchUrl = properties["patch_url"] ?: DEFAULT_PATCH_URL,
                patchVersion = properties["patch_version"] ?: DEFAULT_PATCH_VERSION,
                redirectUrls = properties.list("redirect_urls") ?: DEFAULT_REDIRECT_URLS,
            )
    }
}
