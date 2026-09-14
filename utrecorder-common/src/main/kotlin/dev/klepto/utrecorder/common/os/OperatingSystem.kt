package dev.klepto.utrecorder.common.os

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path

/**
 * An operating system family, and the conventions a program follows on it.
 *
 * Use [OperatingSystem.current] for the operating system a [PropertySource] names.
 */
enum class OperatingSystem {
    WINDOWS {
        override val executableSuffix: String = ".exe"

        override val libraryPathVariable: String? = null

        override fun dataDirectory(properties: PropertySource): Path =
            properties.environmentVariable("LOCALAPPDATA")?.toAbsolutePath()
                ?: homeDirectory(properties)["AppData"]["Local"]
    },
    LINUX {
        override val executableSuffix: String = ""

        override val libraryPathVariable: String = "LD_LIBRARY_PATH"

        override fun dataDirectory(properties: PropertySource): Path =
            properties.environmentVariable("XDG_DATA_HOME")?.toAbsolutePath()
                ?: homeDirectory(properties)[".local"]["share"]
    },
    UNKNOWN {
        override val executableSuffix: String = LINUX.executableSuffix

        override val libraryPathVariable: String? = LINUX.libraryPathVariable

        override fun dataDirectory(properties: PropertySource): Path = LINUX.dataDirectory(properties)
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
     * The directory this operating system holds per-application data under, read from [properties].
     */
    abstract fun dataDirectory(properties: PropertySource = PropertySource.DEFAULT): Path

    companion object {
        /**
         * The operating system named by the `os.name` property [properties] holds.
         */
        fun current(properties: PropertySource = PropertySource.DEFAULT): OperatingSystem {
            val name = properties.systemProperty("os.name").orEmpty().lowercase()
            return when {
                name.contains("windows") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }

        /**
         * The home directory of the user, read from the `user.home` property [properties] holds.
         *
         * @throws IllegalStateException if [properties] holds no `user.home`.
         */
        private fun homeDirectory(properties: PropertySource): Path =
            checkNotNull(properties.systemProperty("user.home")) { "No user.home system property" }.toAbsolutePath()
    }
}
