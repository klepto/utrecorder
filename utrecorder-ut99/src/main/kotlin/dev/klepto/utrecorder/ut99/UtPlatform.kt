package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.os.OS
import dev.klepto.utrecorder.common.os.OperatingSystem.LINUX
import dev.klepto.utrecorder.common.os.OperatingSystem.WINDOWS

/**
 * The platforms an installation is provisioned for, each naming the files Unreal Tournament ships under it.
 *
 * Use [UT_PLATFORM] for the platform the current process runs on.
 *
 * @property patchSuffix The part of the OldUnreal patch file name that names this platform.
 * @property compilerExecutable Compiler executable name.
 * @property gameExecutable Unreal Tournament executable name.
 * @property executableSuffix The extension an executable takes on this platform, empty where it takes none.
 * @property homeArguments The arguments that keep the game and the compiler out of the home directory of the user.
 */
enum class UtPlatform(
    private val patchSuffix: String,
    val compilerExecutable: String,
    val gameExecutable: String,
    val executableSuffix: String,
    val homeArguments: List<String>,
) {
    WINDOWS("Windows-x86.zip", "UCC.exe", "UnrealTournament.exe", ".exe", emptyList()),
    LINUX("Linux-x86.tar.bz2", "ucc-bin", "ut-bin", "", listOf("-nohomedir")),
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
