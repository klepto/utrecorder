package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Location of an Unreal Tournament installation and the sources its game, patch and packages are downloaded from.
 *
 * Use the constructor. A property that is not provided takes the value its key configures, or else its literal
 * default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `game_directory`, `iso_url`, `iso_md5`, `patch_url`, `patch_version`, comma-separated
 * `redirect_urls` and `decompress_timeout` keys.
 * @property directoriesConfig The directories the installation, the packages and the downloads live under. If not
 * provided, uses the directories that [reader] names.
 * @property gameDirectory The directory the game is installed in. If not provided and [reader] configures no
 * `game_directory`, uses the `ut99` directory under the installs directory of [directoriesConfig].
 * @property isoUrl The URL of the original Unreal Tournament ISO file.
 * @property isoMd5 The MD5 hash of the original Unreal Tournament ISO file.
 * @property patchUrl The URL of the Unreal Tournament patch releases.
 * @property patchVersion The version of the Unreal Tournament patch to use.
 * @property redirectUrls The base URLs a package missing from the installation and the packages directory is fetched
 * from.
 * @property decompressTimeout The time one run of the compiler decompressing a `.uz` archive is given to finish.
 * @property patchFileName The file name of the patch release. If not provided, uses the name of the [patchVersion]
 * release for the current platform.
 */
class UtConfig(
    reader: ConfigReader = SystemConfigReader(),
    val directoriesConfig: DirectoriesConfig = DirectoriesConfig(reader),
    val gameDirectory: Path = reader.get("game_directory", directoriesConfig.installs["ut99"], String::toAbsolutePath),
    val isoUrl: String = reader.get("iso_url", "https://archive.org/download/ut-goty/UT_GOTY_CD1.iso"),
    val isoMd5: String = reader.get("iso_md5", "e5127537f44086f5ed36a9d29f992c00"),
    val patchUrl: String = reader.get("patch_url", "https://github.com/OldUnreal/UnrealTournamentPatches/releases/download"),
    val patchVersion: String = reader.get("patch_version", "469e"),
    val redirectUrls: List<String> = reader.list("redirect_urls", listOf("http://api.utbt.net/redirect", "http://uz.ut-files.com")),
    val decompressTimeout: Duration = reader.duration("decompress_timeout", 5.minutes),
    val patchFileName: String = UtPlatform.current().patchFileName(patchVersion),
)
