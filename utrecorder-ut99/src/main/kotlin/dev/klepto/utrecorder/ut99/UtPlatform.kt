package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.os.OperatingSystem
import java.nio.charset.Charset

/**
 * An operating system Unreal Tournament runs on, and the file names the game ships under it.
 *
 * Use [current] for the platform of the current process.
 *
 * @property operatingSystem The operating system this platform runs on.
 * @property patchSuffix The part of the OldUnreal patch file name that names this platform.
 * @property compilerExecutable The file name of the UCC executable.
 * @property gameExecutable The file name of the game executable.
 * @property homeArguments The arguments that keep the game and the compiler out of the home directory of the user.
 */
enum class UtPlatform(
    val operatingSystem: OperatingSystem,
    private val patchSuffix: String,
    val compilerExecutable: String,
    val gameExecutable: String,
    val homeArguments: List<String>,
) {
    WINDOWS(OperatingSystem.WINDOWS, "Windows-x86.zip", "UCC.exe", "UnrealTournament.exe", emptyList()),
    LINUX(OperatingSystem.LINUX, "Linux-x86.tar.bz2", "ucc-bin", "ut-bin", listOf("-nohomedir")),
    ;

    /**
     * Returns the name of the OldUnreal patch file holding [patchVersion] for this platform.
     */
    fun patchFileName(patchVersion: String): String = "$PATCH_FILE_PREFIX$patchVersion-$patchSuffix"

    companion object {
        /**
         * The prefix every OldUnreal patch file name takes.
         */
        private const val PATCH_FILE_PREFIX = "OldUnreal-UTPatch"

        /**
         * The charset Unreal Tournament reads and writes its text in.
         */
        val CHARSET: Charset = Charsets.ISO_8859_1

        /**
         * Returns the platform that runs on [operatingSystem].
         *
         * @throws IllegalStateException if no platform runs on [operatingSystem].
         */
        fun current(operatingSystem: OperatingSystem = OperatingSystem.current()): UtPlatform =
            entries.firstOrNull { it.operatingSystem == operatingSystem } ?: error("Unsupported operating system")
    }
}
