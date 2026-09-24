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
        override val dataDirectory: Path
            get() = System.getenv("LOCALAPPDATA")?.toAbsolutePath() ?: homeDirectory()["AppData"]["Local"]
    },
    LINUX(executableSuffix = "", libraryPathVariable = "LD_LIBRARY_PATH") {
        override val dataDirectory: Path
            get() = System.getenv("XDG_DATA_HOME")?.toAbsolutePath() ?: homeDirectory()[".local"]["share"]
    },
    ;

    /**
     * The directory this operating system holds per-application data under.
     *
     * On Windows, this is `%LOCALAPPDATA%`, else `AppData\Local` under the home directory. On Linux, this is
     * `$XDG_DATA_HOME`, else `.local/share` under the home directory. The value is read again on every access.
     *
     * @throws IllegalStateException if the environment variable is not set and no `user.home` is set either.
     */
    abstract val dataDirectory: Path

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
         * Returns the home directory of the user, read from the `user.home` system property.
         *
         * @throws IllegalStateException if no `user.home` is set.
         */
        private fun homeDirectory(): Path =
            checkNotNull(System.getProperty("user.home")) { "No user.home system property" }.toAbsolutePath()
    }
}
