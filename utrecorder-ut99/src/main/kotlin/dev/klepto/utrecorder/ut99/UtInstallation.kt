package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A materialized Unreal Tournament installation, reached through the directories, the executable and the compiler it
 * holds.
 *
 * Use [UtInstaller] to materialize one, [process] to run it, and [compiler] to compile against it.
 *
 * @property directory The root of the installation.
 * @property config The configuration [compiler] runs with.
 * @throws IllegalStateException if [directory] or the game executable it holds does not exist.
 */
class UtInstallation(
    val directory: Path,
    val config: UtConfig = UtConfig(),
) {
    /**
     * The platform the installation runs on.
     */
    val platform: UtPlatform = UtPlatform.current()

    /**
     * The directory holding the executables, the configuration and the compiled packages.
     */
    val systemDirectory: Path = directory[UtDirectory.System]

    /**
     * The game executable.
     */
    val executablePath: Path = systemDirectory[platform.gameExecutable]

    /**
     * The game configuration, `UnrealTournament.ini`.
     */
    val gameIniPath: Path = systemDirectory["UnrealTournament.ini"]

    /**
     * The user configuration, `User.ini`.
     */
    val userIniPath: Path = systemDirectory["User.ini"]

    /**
     * The default configuration, `Default.ini`.
     */
    val defaultIniPath: Path = systemDirectory["Default.ini"]

    /**
     * The UCC utility of this installation.
     */
    val compiler: UtCompiler by lazy { UtCompiler(this, config) }

    init {
        check(directory.exists()) { "UT installation directory not found at $directory" }
        check(executablePath.exists()) { "UT executable not found at $executablePath" }
    }

    /**
     * Returns an unstarted run of this installation, named [name] and described by [configure]. Building one writes
     * nothing. [UtProcess.launch] opens the run's workspace and adds the files the run needs, which the workspace
     * keeps.
     *
     * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [configure] sets arguments
     * without a program.
     */
    fun process(
        name: String,
        configure: UtProcessBuilder.() -> Unit = {},
    ): UtProcess = UtProcess.build(this, name, configure)

    /**
     * Returns the packages [packageDirectory] of this installation holds, matched by the extension it declares. If the
     * directory does not exist or declares no extension, returns an empty list instead.
     */
    suspend fun packages(packageDirectory: UtDirectory): List<UtPackage> =
        withContext(Dispatchers.IO) {
            UtPackage.list(directory[packageDirectory], setOfNotNull(packageDirectory.extension))
        }
}
