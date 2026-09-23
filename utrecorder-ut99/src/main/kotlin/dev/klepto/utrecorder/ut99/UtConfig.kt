package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.ConfigReader
import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.config.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path

/**
 * Configuration of the Unreal Tournament module.
 *
 * Use the constructor for the configuration of literal defaults, or [fromProperties] for the one the configuration
 * keys of a [ConfigReader] name.
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
    val directoriesConfig: DirectoriesConfig = DirectoriesConfig(),
    val gameDirectory: Path = directoriesConfig.installs["ut99"],
    val isoUrl: String = "https://archive.org/download/ut-goty/UT_GOTY_CD1.iso",
    val isoMd5: String = "e5127537f44086f5ed36a9d29f992c00",
    val patchUrl: String = "https://github.com/OldUnreal/UnrealTournamentPatches/releases/download",
    val patchVersion: String = "469e",
    val redirectUrls: List<String> = listOf("http://api.utbt.net/redirect", "http://uz.ut-files.com"),
    val patchFileName: String = UtPlatform.current().patchFileName(patchVersion),
) {
    companion object {
        /**
         * The configuration the keys of [reader] name, over the literal defaults, against [directoriesConfig].
         *
         * Reads `iso_url`, `iso_md5`, `patch_url`, `patch_version` and the comma-separated `redirect_urls`.
         */
        fun fromProperties(
            reader: ConfigReader = SystemConfigReader(),
            directoriesConfig: DirectoriesConfig = DirectoriesConfig(reader),
        ): UtConfig {
            val defaults = UtConfig(directoriesConfig)
            val patchVersion = reader["patch_version"] ?: defaults.patchVersion
            return defaults.copy(
                isoUrl = reader["iso_url"] ?: defaults.isoUrl,
                isoMd5 = reader["iso_md5"] ?: defaults.isoMd5,
                patchUrl = reader["patch_url"] ?: defaults.patchUrl,
                patchVersion = patchVersion,
                redirectUrls = reader.list("redirect_urls") ?: defaults.redirectUrls,
                patchFileName = UtPlatform.current().patchFileName(patchVersion),
            )
        }
    }
}
