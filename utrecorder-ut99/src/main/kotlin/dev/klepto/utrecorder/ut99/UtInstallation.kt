package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A materialized Unreal Tournament installation, reached through the directories, the executable and the compiler it
 * holds.
 *
 * Use [UtInstaller] to materialize one, [instance] to run it, and [compiler] to compile against it.
 *
 * @property directory The root of the installation.
 * @property directories The directories holding the workspaces anything the installation runs works in.
 * @throws IllegalStateException if [directory] or the game executable it holds does not exist.
 */
class UtInstallation(
    val directory: Path,
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * The platform the installation runs on.
     */
    val platform = UtPlatform.current()

    /**
     * The directory holding the executables, the configuration and the compiled packages.
     */
    val systemDirectory = directory[UtDirectory.System]

    /**
     * The game executable.
     */
    val executablePath = systemDirectory[platform.gameExecutable]

    /**
     * The game configuration an instance seeds its own copy from.
     */
    val gameIniPath = systemDirectory[GAME_INI]

    /**
     * The user configuration an instance seeds its own copy from.
     */
    val userIniPath = systemDirectory[USER_INI]

    /**
     * The default configuration the compiler seeds a compile from.
     */
    val defaultIniPath: Path = systemDirectory[DEFAULT_INI]

    /**
     * The UCC utility of this installation.
     */
    val compiler: UtCompiler by lazy { UtCompiler(this, directories) }

    init {
        check(directory.exists()) { "UT installation directory not found at $directory" }
        check(executablePath.exists()) { "UT executable not found at $executablePath" }
    }

    /**
     * Returns an unstarted run of this installation, named [name] and described by [configure]. Building one opens the
     * run's workspace, pruning the expired leftovers of earlier runs of that name, and writes nothing into the
     * installation: [play] adds the files the run needs and removes them again.
     *
     * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [configure] sets arguments
     * without an executable.
     */
    suspend fun instance(
        name: String,
        configure: UtInstance.Builder.() -> Unit = {},
    ): UtInstance = UtInstance.Builder(this, name, directories).apply(configure).build()

    /**
     * Returns the packages [packageDirectory] of this installation holds, matched by the extension it declares. Empty
     * for a directory that does not exist or declares no extension.
     */
    suspend fun packages(packageDirectory: UtDirectory): List<UtPackage> =
        withContext(Dispatchers.IO) {
            UtPackage.list(directory[packageDirectory], setOfNotNull(packageDirectory.extension))
        }

    companion object {
        /**
         * The name of the game configuration of an installation.
         */
        private const val GAME_INI = "UnrealTournament.ini"

        /**
         * The name of the user configuration of an installation.
         */
        private const val USER_INI = "User.ini"

        /**
         * The name of the default configuration of an installation.
         */
        private const val DEFAULT_INI = "Default.ini"
    }
}
