package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.OperatingSystem
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
 * @property client The client class the game draws through, which also names its section in the game configuration.
 * @property echoesLog `true` when the game also writes every line of its log to its standard output.
 */
enum class UtPlatform(
    val operatingSystem: OperatingSystem,
    private val patchSuffix: String,
    val compilerExecutable: String,
    val gameExecutable: String,
    val homeArguments: List<String>,
    val client: String,
    val echoesLog: Boolean,
) {
    WINDOWS(
        OperatingSystem.WINDOWS,
        "Windows-x86.zip",
        "UCC.exe",
        "UnrealTournament.exe",
        emptyList(),
        "WinDrv.WindowsClient",
        false,
    ),
    LINUX(
        OperatingSystem.LINUX,
        "Linux-x86.tar.bz2",
        "ucc-bin",
        "ut-bin",
        listOf("-nohomedir"),
        "SDLDrv.SDLClient",
        true,
    ),
    ;

    /**
     * Returns the name of the OldUnreal patch file holding [patchVersion] for this platform.
     */
    fun patchFileName(patchVersion: String): String = "OldUnreal-UTPatch$patchVersion-$patchSuffix"

    companion object {
        /**
         * The charset Unreal Tournament reads and writes its text in.
         */
        val CHARSET: Charset = Charsets.ISO_8859_1

        /**
         * Returns the platform that runs on [operatingSystem].
         *
         * If [operatingSystem] is not provided, uses the operating system of the current process instead.
         */
        fun current(operatingSystem: OperatingSystem = OperatingSystem.current()): UtPlatform =
            when (operatingSystem) {
                OperatingSystem.WINDOWS -> WINDOWS
                OperatingSystem.LINUX -> LINUX
            }
    }
}
