package dev.klepto.utrecorder.common

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Application directories, each resolved against a common [home] directory.
 *
 * Use the constructor. Without arguments, it resolves the directories of the current process.
 *
 * @param reader The source of the `home`, `cache`, `installs`, `workspace` and `packages` keys, and of the system
 * properties and environment variables the fallback homes are found from.
 * @property home The directory every other directory defaults to a subdirectory of. If not provided, uses the home that
 * [reader] configures, else the home held by the source checkout that contains the `user.dir` of [reader], else the
 * one under the operating system data directory that [reader] holds.
 * @property cache The directory of downloads and other files that can be fetched again.
 * @property installs The directory of provisioned dependencies, one subdirectory each.
 * @property workspace The directory of per-run workspaces.
 * @property packages The directory of static game packages.
 * @throws IllegalStateException if [home] is not provided and [reader] holds no home, no `user.dir` within a source
 * checkout and no data directory, as [ConfigReader.EMPTY] does.
 *
 * If not provided and [reader] configures no key of the same name, every directory other than [home] is the
 * subdirectory of [home] named after it.
 */
class DirectoriesConfig(
    reader: ConfigReader = SystemConfigReader(),
    val home: Path = configuredHome(reader) ?: developmentHome(reader) ?: systemHome(reader),
    val cache: Path = reader.get("cache", home["cache"], String::toAbsolutePath),
    val installs: Path = reader.get("installs", home["installs"], String::toAbsolutePath),
    val workspace: Path = reader.get("workspace", home["workspace"], String::toAbsolutePath),
    val packages: Path = reader.get("packages", home["packages"], String::toAbsolutePath),
) {
    companion object {
        /**
         * The file that marks the root of a source checkout.
         */
        private const val REPOSITORY_MARKER = "settings.gradle.kts"

        /**
         * The name the home directory takes within a source checkout.
         */
        private const val DEVELOPMENT_DIRECTORY = ".utrecorder"

        /**
         * The name the home directory takes within the operating system data directory.
         */
        private const val APPLICATION_DIRECTORY = "utrecorder"

        /**
         * Returns the home directory [reader] configures, or `null` if it configures none.
         */
        private fun configuredHome(reader: ConfigReader): Path? = reader.get("home", String::toAbsolutePath)

        /**
         * Returns the home directory held by the source checkout that contains the `user.dir` of [reader]. If no
         * checkout contains it, or [reader] holds no `user.dir`, returns `null` instead.
         */
        private fun developmentHome(reader: ConfigReader): Path? =
            generateSequence(reader.systemProperty("user.dir")?.toAbsolutePath()) { it.parent }
                .firstOrNull { it[REPOSITORY_MARKER].exists() }
                ?.get(DEVELOPMENT_DIRECTORY)

        /**
         * Returns the home directory under the data directory of the current operating system, read through [reader].
         */
        private fun systemHome(reader: ConfigReader): Path = OperatingSystem.current().dataDirectory(reader)[APPLICATION_DIRECTORY]
    }
}
