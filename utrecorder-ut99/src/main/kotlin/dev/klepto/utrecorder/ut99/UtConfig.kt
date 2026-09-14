package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path

/**
 * Configuration of the Unreal Tournament module.
 *
 * Use [DEFAULT] for the configuration of the current process, or [fromProperties] to build one from a
 * [PropertySource].
 *
 * @property directories The directories the installation, the packages and the downloads live under.
 * @property gameDirectory The directory the game is installed in.
 * @property isoUrl The URL of the original UT ISO file.
 * @property isoMd5 The MD5 hash of the original UT ISO file.
 * @property patchUrl The URL of the UT patch releases.
 * @property patchVersion The version of the UT patch to use.
 * @property redirectUrls The base URLs a package missing from the installation and the packages directory is fetched
 * from.
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
     * The name of the patch file this configuration installs on the current platform.
     */
    val patchFileName: String = UtPlatform.current().patchFileName(patchVersion)

    companion object {
        /**
         * The name the installation takes within the installs directory.
         */
        const val GAME_DIRECTORY = "ut99"

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
         * The configuration a caller that names none works against, resolved on first use.
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
