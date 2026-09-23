package dev.klepto.utrecorder.common.os

import dev.klepto.utrecorder.common.config.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path

/**
 * An operating system family, and the conventions a program follows on it.
 *
 * Use [OperatingSystem.current] for the operating system a [ConfigReader] names.
 */
enum class OperatingSystem {
    WINDOWS {
        override val executableSuffix: String = ".exe"

        override val libraryPathVariable: String? = null

        override val dataDirectory: Path by lazy {
            System.getenv("LOCALAPPDATA")?.toAbsolutePath()
                ?: homeDirectory["AppData"]["Local"]
        }
    },
    LINUX {
        override val executableSuffix: String = ""

        override val libraryPathVariable: String = "LD_LIBRARY_PATH"

        override val dataDirectory: Path by lazy {
            System.getenv("XDG_DATA_HOME")?.toAbsolutePath()
                ?: homeDirectory[".local"]["share"]
        }
    },
    UNKNOWN {
        override val executableSuffix: String = LINUX.executableSuffix

        override val libraryPathVariable: String? = LINUX.libraryPathVariable

        override val dataDirectory: Path by lazy { LINUX.dataDirectory }
    },
    ;

    /**
     * The extension an executable takes on this operating system, empty where it takes none.
     */
    abstract val executableSuffix: String

    /**
     * The environment variable this operating system resolves shared libraries through, or `null` for one that
     * resolves them without one.
     */
    abstract val libraryPathVariable: String?

    /**
     * The directory this operating system holds per-application data under, read from [reader].
     */
    abstract val dataDirectory: Path

    companion object {
        /**
         * The operating system named by the `os.name` property [reader] holds.
         */
        fun current(): OperatingSystem {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.contains("windows") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }

        /**
         * The home directory of the user, read from the `user.home` property [reader] holds.
         *
         * @throws IllegalStateException if [reader] holds no `user.home`.
         */
        private val homeDirectory: Path by lazy {
            checkNotNull(System.getProperty("user.home")) { "No user.home system property" }.toAbsolutePath()
        }
    }
}
