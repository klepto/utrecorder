package dev.klepto.utrecorder.common.os

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.homeDirectory
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path

/**
 * The supported operating systems.
 *
 * Use [OS] for the operating system this process runs on, or [OperatingSystem.current] to read it from a given
 * [PropertySource].
 */
enum class OperatingSystem {
    WINDOWS {
        override val libraryPathVariable: String? = null

        override fun dataDirectory(properties: PropertySource): Path =
            properties.environment("LOCALAPPDATA")?.toAbsolutePath()
                ?: homeDirectory(properties)["AppData"]["Local"]
    },
    LINUX {
        override val libraryPathVariable: String = "LD_LIBRARY_PATH"

        override fun dataDirectory(properties: PropertySource): Path =
            properties.environment("XDG_DATA_HOME")?.toAbsolutePath()
                ?: homeDirectory(properties)[".local"]["share"]
    },
    UNKNOWN {
        override val libraryPathVariable: String? = LINUX.libraryPathVariable

        override fun dataDirectory(properties: PropertySource): Path = LINUX.dataDirectory(properties)
    },
    ;

    /**
     * The environment variable this operating system resolves shared libraries through, or `null` for one that
     * resolves them without one.
     */
    abstract val libraryPathVariable: String?

    /**
     * Returns the directory this operating system holds per-application data under, read from [properties].
     */
    abstract fun dataDirectory(properties: PropertySource = PropertySource.DEFAULT): Path

    companion object {
        /**
         * Returns the operating system named by the `os.name` system property [properties] holds.
         */
        fun current(properties: PropertySource = PropertySource.DEFAULT): OperatingSystem {
            val name = properties.property("os.name").orEmpty().lowercase()
            return when {
                name.contains("windows") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

/**
 * The operating system this process runs on.
 */
val OS = OperatingSystem.current()
