package dev.klepto.utrecorder.build.native

/**
 * The kind of binary a native unit compiles to. The compiler flags and the file name both follow from it.
 */
enum class NativeBinaryType {
    SHARED_LIBRARY,
    EXECUTABLE,
}

/**
 * A platform the native binaries are built for.
 *
 * Only one of these can be produced by a build, because nothing here cross-compiles: the toolchain on the machine
 * targets the machine. The jar therefore carries the platform as its classifier rather than holding every platform's
 * binaries at once.
 *
 * Both targets are 32-bit, because Unreal Tournament is. On Windows the injector and the library it injects have to
 * match the bitness of the game, and `CreateRemoteThread` cannot cross it. On Linux the 469 patch ships x86 so that
 * one `System` directory serves both platforms, which means gcc needs `-m32` and the 32-bit development libraries.
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
         * that is neither Windows nor Linux, rather than guessing - Unreal Tournament runs on those two, so a build
         * anywhere else has nothing to target and should say so instead of failing later in the compiler.
         *
         * Detection matches `OperatingSystem` in `utrecorder-common`, which this cannot use: `build-scripts` is an
         * included build and cannot depend on a subproject of the build it configures.
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
