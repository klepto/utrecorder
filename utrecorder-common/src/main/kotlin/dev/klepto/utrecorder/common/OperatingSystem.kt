package dev.klepto.utrecorder.common

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path

/**
 * An operating system family.
 *
 * Use [OperatingSystem.current] for the operating system the process runs on.
 *
 * @property executableSuffix The extension an executable takes on this operating system, empty where it takes none.
 * @property libraryPathVariable The environment variable this operating system resolves shared libraries through, or
 *   `null` for one that resolves them without one.
 */
enum class OperatingSystem(
    val executableSuffix: String,
    val libraryPathVariable: String?,
) {
    WINDOWS(executableSuffix = ".exe", libraryPathVariable = null) {
        override fun dataDirectory(reader: ConfigReader): Path =
            reader.environmentVariable("LOCALAPPDATA")?.toAbsolutePath() ?: homeDirectory(reader)["AppData"]["Local"]
    },
    LINUX(executableSuffix = "", libraryPathVariable = "LD_LIBRARY_PATH") {
        override fun dataDirectory(reader: ConfigReader): Path =
            reader.environmentVariable("XDG_DATA_HOME")?.toAbsolutePath() ?: homeDirectory(reader)[".local"]["share"]
    },
    ;

    /**
     * Returns the directory this operating system holds per-application data under, read through [reader].
     *
     * On Windows, this is `%LOCALAPPDATA%`, else `AppData\Local` under the home directory. On Linux, this is
     * `$XDG_DATA_HOME`, else `.local/share` under the home directory. An empty environment variable counts as not set.
     *
     * @throws IllegalStateException if the environment variable is not set and no `user.home` is set either.
     */
    abstract fun dataDirectory(reader: ConfigReader = SystemConfigReader()): Path

    companion object {
        /**
         * Returns the operating system named by the `os.name` system property.
         *
         * @throws NotImplementedError if `os.name` names neither Windows nor Linux.
         */
        fun current(): OperatingSystem {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.contains("windows") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> throw NotImplementedError("Operating system $name")
            }
        }

        /**
         * Returns the home directory of the user, read from the `user.home` system property through [reader].
         *
         * @throws IllegalStateException if no `user.home` is set.
         */
        private fun homeDirectory(reader: ConfigReader): Path =
            checkNotNull(reader.systemProperty("user.home")) { "No user.home system property" }.toAbsolutePath()
    }
}
