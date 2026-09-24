package dev.klepto.utrecorder.build.native

import dev.klepto.utrecorder.build.native.NativeBinaryType.EXECUTABLE
import dev.klepto.utrecorder.build.native.NativeBinaryType.SHARED_LIBRARY

/**
 * Kind of file a native binary compiles to.
 */
enum class NativeBinaryType {
    SHARED_LIBRARY,
    EXECUTABLE,
}

/**
 * Platform that native binaries are built for. Every platform is 32-bit x86.
 *
 * Use [current] for the platform the build runs on.
 *
 * @property id Name of the platform as a jar classifier.
 * @property sharedLibrarySuffix File extension of a shared library on this platform.
 * @property executableSuffix File extension of an executable on this platform.
 */
enum class NativePlatform(
    val id: String,
    private val sharedLibrarySuffix: String,
    private val executableSuffix: String,
) {
    WINDOWS_X86("windows-x86", ".dll", ".exe"),
    LINUX_X86("linux-x86", ".so", ""),
    ;

    /**
     * `true` if this platform is Windows.
     */
    val isWindows: Boolean get() = this == WINDOWS_X86

    /**
     * Returns the file name of a binary of the given [name] and [type] on this platform.
     */
    fun fileName(
        name: String,
        type: NativeBinaryType,
    ): String =
        name +
            when (type) {
                SHARED_LIBRARY -> sharedLibrarySuffix
                EXECUTABLE -> executableSuffix
            }

    companion object {
        /**
         * Platform the build runs on, and the only platform it can produce binaries for.
         *
         * @throws IllegalStateException if the operating system is neither Windows nor Linux.
         */
        val current: NativePlatform
            get() {
                val name = System.getProperty("os.name").orEmpty()
                return when {
                    name.contains("windows", ignoreCase = true) -> WINDOWS_X86
                    name.contains("linux", ignoreCase = true) -> LINUX_X86
                    else -> error("Unsupported operating system '$name', native binaries build on Windows and Linux")
                }
            }
    }
}
