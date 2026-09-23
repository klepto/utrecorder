package dev.klepto.utrecorder.common.config

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.common.io.workingDirectory
import dev.klepto.utrecorder.common.os.OperatingSystem
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A set of application directories, each resolved against a common [home] directory.
 *
 * Use [DEFAULT] for the directories the current process works against, or [fromProperties] to build a set from a given
 * [ConfigReader].
 *
 */
data class DirectoriesConfig(
    val home: Path,
    val cache: Path = home["cache"],
    val installs: Path = home["installs"],
    val workspace: Path = home["workspace"],
    val packages: Path = home["packages"],
) {
    constructor(reader: ConfigReader = SystemConfigReader()) : this(resolveHome(reader))

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
         * Returns the home directory, preferring the one [reader] configures, then the one held by the source
         * checkout this process runs from, then the one under the operating system data directory.
         */
        private fun resolveHome(reader: ConfigReader): Path = configuredHome(reader) ?: developmentHome() ?: systemHome()

        /**
         * Returns the home directory [reader] configures, or `null` if it configures none.
         */
        private fun configuredHome(reader: ConfigReader): Path? = reader.get("home") { it.toAbsolutePath() }

        /**
         * Returns the home directory held by the source checkout containing the working directory [reader] names,
         * or `null` if no checkout contains it.
         */
        private fun developmentHome(): Path? =
            generateSequence(workingDirectory()) { it.parent }
                .firstOrNull { it[REPOSITORY_MARKER].exists() }
                ?.get(DEVELOPMENT_DIRECTORY)

        /**
         * Returns the home directory under the data directory of the operating system [reader] names.
         */
        private fun systemHome(): Path = OperatingSystem.current().dataDirectory[APPLICATION_DIRECTORY]
    }
}
