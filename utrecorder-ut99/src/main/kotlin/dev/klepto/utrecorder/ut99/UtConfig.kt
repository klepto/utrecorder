package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path

/**
 * Everything the Unreal Tournament module needs from its environment.
 *
 * A value rather than a global, so that callers that cannot share one installation - concurrent builds, a server
 * running several recordings - each hold a configuration of their own, and so that a class reaching for a URL or a
 * directory has to say so in its constructor.
 *
 * @author Augustinas R. <http://github.com/klepto>
 */
data class UtConfig(
    /**
     * The directories the installation is materialized under.
     */
    val directories: Directories = Directories.DEFAULT,
    /**
     * The directory where the game is installed. Named separately from [directories], so that callers that cannot
     * share one installation can each name their own.
     */
    val gameDirectory: Path = directories.installs[GAME_DIRECTORY],
    /**
     * The URL of the original UT ISO file.
     */
    val isoUrl: String = DEFAULT_ISO_URL,
    /**
     * The MD5 hash of the original UT ISO file.
     */
    val isoMd5: String = DEFAULT_ISO_MD5,
    /**
     * The URL of the UT patch releases.
     */
    val patchUrl: String = DEFAULT_PATCH_URL,
    /**
     * The version of the UT patch to use.
     */
    val patchVersion: String = DEFAULT_PATCH_VERSION,
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
                isoUrl = properties.get("iso_url") ?: DEFAULT_ISO_URL,
                isoMd5 = properties.get("iso_md5") ?: DEFAULT_ISO_MD5,
                patchUrl = properties.get("patch_url") ?: DEFAULT_PATCH_URL,
                patchVersion = properties.get("patch_version") ?: DEFAULT_PATCH_VERSION,
            )

        /**
         * The name the installation takes within the installs directory.
         */
        private const val GAME_DIRECTORY = "ut99"

        private const val DEFAULT_ISO_URL = "https://archive.org/download/ut-goty/UT_GOTY_CD1.iso"

        private const val DEFAULT_ISO_MD5 = "e5127537f44086f5ed36a9d29f992c00"

        private const val DEFAULT_PATCH_URL = "https://github.com/OldUnreal/UnrealTournamentPatches/releases/download"

        private const val DEFAULT_PATCH_VERSION = "469e"
    }
}
