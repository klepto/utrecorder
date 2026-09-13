package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A materialized Unreal Tournament installation, reached through the directories, the executable and the compiler it
 * holds.
 *
 * @property directory The root of the installation.
 * @property workspace The workspace anything the installation runs uses for its tasks.
 */
class UtInstallation(
    val directory: Path,
    private val workspace: Workspace = Workspace.DEFAULT,
) {
    /**
     * The UT installation platform.
     */
    val platform = UT_PLATFORM

    /**
     * The directory holding the executables, the configuration and the compiled packages.
     */
    val systemDirectory = directory[UtDirectory.System]

    /**
     * The UT installation executable. Sits in the system directory, next to the compiler, not at the root.
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
     * The UT compiler utility.
     */
    val compiler by lazy {
        UtCompiler(systemDirectory[platform.compilerExecutable], workspace)
    }

    init {
        check(directory.exists()) { "UT installation directory not found at $directory" }
        check(executablePath.exists()) { "UT executable not found at $executablePath" }
    }

    /**
     * Returns an unstarted run of this installation, named [name] and described by [configure]. Building one writes
     * nothing: [UtInstance.start] adds the files the run needs, and [UtInstance.await] removes them again.
     */
    fun instance(
        name: String,
        configure: UtInstanceBuilder.() -> Unit = {},
    ): UtInstance = UtInstanceBuilder(this, name).apply(configure).build()

    /**
     * Returns the packages [directory] holds: every regular file directly in it whose extension is the one the
     * directory declares, compared ignoring case. A directory that is not there, and one that declares no extension,
     * hold no packages. The GUID of each is read only when it is asked for.
     */
    fun packages(directory: UtDirectory): List<UtPackage> =
        directory.extension?.let { packagesIn(this.directory[directory], it) } ?: emptyList()

    companion object {
        /**
         * The name of the game configuration of an installation.
         */
        private const val GAME_INI = "UnrealTournament.ini"

        /**
         * The name of the user configuration of an installation.
         */
        private const val USER_INI = "User.ini"
    }
}
