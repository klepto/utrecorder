package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.os.OS
import dev.klepto.utrecorder.common.os.OperatingSystem.LINUX
import dev.klepto.utrecorder.common.os.OperatingSystem.WINDOWS

/**
 * Lists platforms with their associated platform-specific Unreal Tournament files.
 *
 * @author Augustinas R. <http://github.com/klepto>
 */
enum class UtPlatform(
    /**
     * The part of the OldUnreal patch file name that names this platform.
     */
    private val patchSuffix: String,
    /**
     * Compiler executable name.
     */
    val compilerExecutable: String,
    /**
     * Unreal Tournament executable name.
     */
    val gameExecutable: String,
) {
    WINDOWS("Windows-x86.zip", "UCC.exe", "UnrealTournament.exe"),
    LINUX("Linux-x86.tar.bz2", "ucc-bin", "ut-bin"),
    ;

    /**
     * Returns the name of the patch file holding the given [patchVersion] for this platform. A function of the
     * version rather than a constant, so that the enum does not read a configuration to build itself.
     */
    fun patchFileName(patchVersion: String): String = "$PATCH_FILE_PREFIX$patchVersion-$patchSuffix"

    companion object {
        /**
         * The prefix every OldUnreal patch file name takes.
         */
        private const val PATCH_FILE_PREFIX = "OldUnreal-UTPatch"
    }
}

/**
 * The current platform.
 */
val UT_PLATFORM: UtPlatform =
    when (OS) {
        WINDOWS -> UtPlatform.WINDOWS
        LINUX -> UtPlatform.LINUX
        else -> error("Unsupported operating system")
    }
