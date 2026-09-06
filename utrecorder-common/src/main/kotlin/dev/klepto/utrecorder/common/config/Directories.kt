package dev.klepto.utrecorder.common.config

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.common.os.OS
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A set of application directories, each resolved against a common [home] directory.
 *
 * Use [DEFAULT] for the directories the current process works against, or [fromProperties] to build a set from a given
 * [PropertySource].
 *
 * @property home The root directory every other directory is resolved against.
 */
data class Directories(
    val home: Path,
) {
    /**
     * The directory cached files are stored in.
     */
    val cache: Path = home["cache"]

    /**
     * The directory provisioned dependencies are installed in.
     */
    val installs: Path = home["installs"]

    /**
     * The directory the working directories of tasks are held in.
     */
    val tasks: Path = home["tasks"]

    /**
     * The directory fetched game packages are stored in.
     */
    val packages: Path = home["packages"]

    /**
     * The directory runtime outputs are stored in.
     */
    val output: Path = home["output"]

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
         * The directories a caller that names none works against.
         */
        val DEFAULT: Directories by lazy { fromProperties() }

        /**
         * Returns the directories built around the home directory [properties] configures.
         */
        fun fromProperties(properties: PropertySource = PropertySource.DEFAULT): Directories = Directories(resolveHome(properties))

        /**
         * Returns the home directory, preferring the one [properties] configures, then the one held by the source
         * checkout this process runs from, then the one under the operating system data directory.
         */
        private fun resolveHome(properties: PropertySource): Path =
            configuredHome(properties) ?: developmentHome() ?: systemHome(properties)

        /**
         * Returns the home directory [properties] configures, or `null` if it configures none.
         */
        private fun configuredHome(properties: PropertySource): Path? = properties.get("home") { it.toAbsolutePath() }

        /**
         * Returns the home directory held by the source checkout this process runs from, or `null` if it runs from
         * none. The checkout is found by walking up from the working directory.
         */
        private fun developmentHome(): Path? =
            generateSequence("".toAbsolutePath()) { it.parent }
                .firstOrNull { it[REPOSITORY_MARKER].exists() }
                ?.get(DEVELOPMENT_DIRECTORY)

        /**
         * Returns the home directory under the operating system data directory.
         */
        private fun systemHome(properties: PropertySource): Path = OS.dataDirectory(properties)[APPLICATION_DIRECTORY]
    }
}
