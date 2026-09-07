package dev.klepto.utrecorder.build.native

/**
 * The kind of binary a native unit compiles to. The compiler flags and the file name both follow from it.
 */
enum class NativeBinaryType {
    SHARED_LIBRARY,
    EXECUTABLE,
}

/**
 * A platform the native binaries are built for, both of them 32-bit.
 *
 * Use [current] for the platform the build runs on, which is the only one it can produce binaries for.
 *
 * @property id The name the platform takes as a jar classifier.
 * @property sharedLibrarySuffix The extension a shared library takes on this platform.
 * @property executableSuffix The extension an executable takes on this platform.
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
     * Whether this platform is Windows, which decides the toolchain and the compiler flags.
     */
    val isWindows: Boolean get() = this == WINDOWS_X86

    /**
     * Returns the file name a binary of the given [name] and [type] takes on this platform.
     */
    fun fileName(
        name: String,
        type: NativeBinaryType,
    ): String =
        name +
            when (type) {
                NativeBinaryType.SHARED_LIBRARY -> sharedLibrarySuffix
                NativeBinaryType.EXECUTABLE -> executableSuffix
            }

    companion object {
        /**
         * The platform the build runs on, and therefore the only one it can produce binaries for. Throws on anything
         * that is neither Windows nor Linux.
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
