package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.readLinesOrEmpty
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.log.mergeAndThrow
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableProcess
import dev.klepto.utrecorder.ut99.UtInstance.Companion.KILLED_EXIT_CODE
import dev.klepto.utrecorder.ut99.UtInstance.Companion.KILL_TIMEOUT
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeLines
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One run of an installation: the files it adds to the installation's `System` directory, named by the run's
 * [Workspace] as `<name>-<id>-<file>`, and, once started, the process reading them.
 *
 * Built by [UtInstallation.instance]. [start] writes the files and starts the process; [await] waits for it, reads the
 * files back into a [UtInstanceResult] and removes them. A run that throws keeps its files, except the executable copy,
 * in `<home>/workspace/<name>-<id>/`. A run started and never awaited leaves its files in `System` until a later run of
 * the same name prunes them after 7 days.
 *
 * @property name The name the run is logged under and its files are named after.
 */
class UtInstance private constructor(
    val name: String,
    private val installation: UtInstallation,
    private val settings: Settings,
    directories: Directories,
) {
    private val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

    private val workspace: Workspace = Workspace(name, systemDirectory, directories)

    /**
     * The copy of the game executable this run launches, whether the run starts it directly or through
     * [Builder.executable]: the run's file in `System` named after the installation's executable.
     */
    val executablePath: Path = workspace.file(installation.executablePath.name)

    /**
     * The copy of the game configuration this run reads: the run's `UnrealTournament.ini` in `System`.
     */
    val gameIniPath: Path = workspace.file(installation.gameIniPath.name)

    /**
     * The copy of the user configuration this run reads: the run's `User.ini` in `System`.
     */
    val userIniPath: Path = workspace.file(installation.userIniPath.name)

    /**
     * The exec script this run plays: the run's `exec.txt` in `System`.
     */
    val execPath: Path = workspace.file("exec.txt")

    /**
     * The log this run writes: the run's `UnrealTournament.log` in `System`.
     */
    val logPath: Path = workspace.file("UnrealTournament.log")

    /**
     * The command line this run starts: `<program> <arguments> <executable copy> <game arguments>` when the run
     * overrides its program, otherwise the executable copy followed by the game arguments.
     */
    val executable: Executable =
        Executable.build(settings.program ?: executablePath) {
            workingDirectory = systemDirectory
            arguments += wrapperArguments()
            arguments += settings.gameArguments
            arguments += "-ini=${gameIniPath.absolutePathString()}"
            arguments += "-userini=${userIniPath.absolutePathString()}"
            arguments += execArguments()
            arguments += "-log=${logPath.name}"
            arguments += installation.platform.homeArguments
            environment += settings.environment
            prependLibraryPath(systemDirectory)
        }

    private fun wrapperArguments(): List<String> =
        when (settings.program) {
            null -> emptyList()
            else -> settings.arguments + executablePath.absolutePathString()
        }

    private fun execArguments(): List<String> =
        when {
            settings.exec.isEmpty() -> emptyList()
            else -> listOf("-EXEC=${execPath.absolutePathString()}")
        }

    private var process: ExecutableProcess? = null

    private var outcome: Result<UtInstanceResult>? = null

    private val outcomeLock = Any()

    /**
     * `true` from [start] until the process ends.
     */
    val isRunning: Boolean get() = process?.isRunning == true

    /**
     * Writes the files of this run into the installation, starts the process and returns this instance. When writing
     * the files or starting the process fails, the executable copy is deleted, the other files are moved into
     * `<home>/workspace/<name>-<id>/`, and the failure is rethrown with any failure of that clean-up suppressed.
     *
     * @throws IllegalStateException if the instance is already started.
     */
    fun start(): UtInstance =
        apply {
            check(process == null) { "Instance $name is already started" }
            process =
                runCatching {
                    writeFiles()
                    executable.start()
                }.onFailure { failure -> deleteFiles(failure) }
                    .getOrThrow()
        }

    /**
     * Writes the configuration copies, the exec script and the executable copy of this run.
     */
    private fun writeFiles() {
        copyIni(installation.gameIniPath, gameIniPath, settings.gameIniEdits)
        copyIni(installation.userIniPath, userIniPath, settings.userIniEdits)
        installation.systemDirectory["Running.ini"].deleteIfExists()
        writeExec()
        installation.executablePath.copy(executablePath) { "UT executable not found at ${installation.executablePath}" }
        executablePath.toFile().setExecutable(true)
    }

    private fun copyIni(
        source: Path,
        target: Path,
        edits: List<UtIni.() -> Unit>,
    ) {
        source.copy(target) { "UT configuration not found at $source" }
        if (edits.isEmpty()) return

        val ini = UtIni.read(target)
        edits.forEach { edit -> ini.edit() }
        ini.write(target)
    }

    private fun writeExec() {
        if (settings.exec.isEmpty()) return
        execPath.writeLines(settings.exec, UtPlatform.CHARSET)
    }

    /**
     * Registers [consumer] for every line the run prints, replaying the lines already printed, and returns this
     * instance.
     *
     * @throws IllegalStateException if the instance is not started.
     */
    fun drain(consumer: (String) -> Unit): UtInstance = apply { startedProcess().drain(consumer) }

    /**
     * The exit code of the run and the contents of every file it wrote, waiting up to [timeout] for the process to end
     * and killing it once the timeout expires. A result, whatever its exit code and including a killed run, deletes
     * the executable copy and removes the other files of the run. When waiting or reading fails, the executable copy
     * is deleted, the other files are moved into `<home>/workspace/<name>-<id>/`, and the failure is rethrown with any
     * failure of that clean-up suppressed. Every call after the first returns or throws what the first one did.
     *
     * @throws IllegalStateException if the instance is not started.
     * @throws IOException if the executable copy or the other files of a run that returned a result cannot be removed,
     * the first failure thrown with the other suppressed.
     */
    fun await(timeout: Duration = Duration.INFINITE): UtInstanceResult =
        synchronized(outcomeLock) { outcome ?: captureOutcome(timeout) }.getOrThrow()

    private fun captureOutcome(timeout: Duration): Result<UtInstanceResult> = runCatching { captureResult(timeout) }.also { outcome = it }

    private fun captureResult(timeout: Duration): UtInstanceResult {
        val process = startedProcess()
        val result = runCatching { process.use { readResult(exitCode(it, timeout)) } }
        deleteFiles(result.exceptionOrNull())
        return result.getOrThrow()
    }

    /**
     * The result of this run exiting with [exitCode], holding the contents of the files it wrote.
     */
    private fun readResult(exitCode: Int): UtInstanceResult =
        UtInstanceResult(
            exitCode = exitCode,
            log = logPath.readLinesOrEmpty(UtPlatform.CHARSET),
            gameIni = gameIniPath.readTextOrEmpty(UtPlatform.CHARSET),
            userIni = userIniPath.readTextOrEmpty(UtPlatform.CHARSET),
            exec = execPath.readLinesOrEmpty(UtPlatform.CHARSET),
        )

    /**
     * Ends the files of this run: deletes the executable copy, then removes the other files when there is no
     * [failure], or moves them into `<home>/workspace/<name>-<id>/` and adds every failure of that clean-up to
     * [failure] as suppressed.
     *
     * @throws IOException if there is no [failure] and the files cannot be removed, the first failure thrown with the
     * other suppressed.
     */
    private fun deleteFiles(failure: Throwable?) {
        when (failure) {
            null -> removeFiles()
            else -> keepFiles().forEach(failure::addSuppressed)
        }
    }

    /**
     * Deletes the executable copy, then removes every other file of the run, trying both.
     *
     * @throws IOException if either cannot be removed, the first failure thrown with the other suppressed.
     */
    private fun removeFiles() {
        listOf(deleteExecutableCatching(), runCatching { workspace.delete() })
            .mapNotNull { it.exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Deletes the executable copy, then moves every other file of the run into `<home>/workspace/<name>-<id>/`, trying
     * both, and returns the failures of the two.
     */
    private fun keepFiles(): List<Throwable> =
        listOf(deleteExecutableCatching(), runCatching { workspace.persistShared() })
            .mapNotNull { it.exceptionOrNull() }

    private fun deleteExecutableCatching(): Result<Unit> = executablePath.deleteCatching(attempts = 10, delay = 100.milliseconds)

    private fun exitCode(
        process: ExecutableProcess,
        timeout: Duration,
    ): Int =
        runCatching { process.await(timeout).exitCode }
            .recoverCatching { cause -> killExitCode(process, cause) }
            .getOrThrow()

    /**
     * The exit code of a run whose wait failed with [cause]: the code the kill produced when [cause] is a timeout, or
     * [KILLED_EXIT_CODE] when the killed process reports none within [KILL_TIMEOUT].
     */
    private fun killExitCode(
        process: ExecutableProcess,
        cause: Throwable,
    ): Int {
        if (cause !is TimeoutException) throw cause

        log.warn { "Instance $name outlived its timeout and was killed" }
        return runCatching { process.await(KILL_TIMEOUT).exitCode }
            .recover { failure ->
                when (failure) {
                    is TimeoutException -> KILLED_EXIT_CODE
                    else -> throw failure
                }
            }.getOrThrow()
    }

    private fun startedProcess(): ExecutableProcess = checkNotNull(process) { "Instance $name is not started" }

    /**
     * A mutable description of one run of [installation], built into a [UtInstance] by [build].
     *
     * Reached through the configuration block of [UtInstallation.instance].
     *
     * @property installation The installation the run is made against.
     * @property name The name the run is logged under and its files are named after.
     * @property directories The directories the run's workspace keeps the files of a failed run under.
     */
    class Builder(
        private val installation: UtInstallation,
        private val name: String,
        private val directories: Directories,
    ) {
        /**
         * The program the run starts in front of the game executable copy, or `null` to start the copy itself.
         */
        var executable: Path? = null

        /**
         * The arguments [executable] takes ahead of the path of the game executable copy.
         */
        val arguments = mutableListOf<String>()

        /**
         * The map or demo the run opens, or `null` to open the game's default.
         */
        var map: String? = null

        /**
         * The lines of the exec script the run plays, none by default.
         */
        var exec: List<String> = emptyList()

        /**
         * `true` when the run plays sound.
         */
        var sound: Boolean = true

        /**
         * `true` when the run draws into a window, `false` when it draws over the whole screen.
         */
        var windowed: Boolean = false

        /**
         * `true` when the run reads a joystick through the installation's configuration.
         */
        var joystick: Boolean = false

        /**
         * The environment variables the run starts with, over those of the current process.
         */
        val environment = mutableMapOf<String, String>()

        private val gameIniEdits = mutableListOf<UtIni.() -> Unit>()

        private val userIniEdits = mutableListOf<UtIni.() -> Unit>()

        /**
         * Registers [edit] against the run's copy of the installation's game configuration.
         */
        fun gameIni(edit: UtIni.() -> Unit) {
            gameIniEdits += edit
        }

        /**
         * Registers [edit] against the run's copy of the installation's user configuration.
         */
        fun userIni(edit: UtIni.() -> Unit) {
            userIniEdits += edit
        }

        /**
         * An unstarted instance of the run this builder describes. Building one prunes the expired leftovers of earlier
         * runs of the same name and writes nothing into the installation.
         *
         * @throws IllegalArgumentException if the name does not match `[A-Za-z0-9._-]+`, or [arguments] are set without
         * an [executable].
         */
        fun build(): UtInstance {
            require(NAME_PATTERN.matches(name)) { "Instance name '$name' does not match ${NAME_PATTERN.pattern}" }
            require(arguments.isEmpty() || executable != null) {
                "Instance $name takes arguments ahead of the game executable but starts no program to receive them"
            }
            return UtInstance(name, installation, settings(), directories)
        }

        private fun settings(): Settings =
            Settings(
                program = executable,
                arguments = arguments.toList(),
                gameArguments = gameArguments(),
                exec = exec.toList(),
                environment = environment.toMap(),
                gameIniEdits = gameIniEdits.toList(),
                userIniEdits = userIniEdits.toList(),
            )

        private fun gameArguments(): List<String> =
            listOfNotNull(
                map,
                "-nosound".takeUnless { sound },
                "-nojoy".takeUnless { joystick },
            ) + displayArgument()

        private fun displayArgument(): String =
            when {
                windowed -> "-windowed"
                else -> "-fullscreen"
            }
    }

    /**
     * An immutable snapshot of a [Builder].
     */
    private class Settings(
        val program: Path?,
        val arguments: List<String>,
        val gameArguments: List<String>,
        val exec: List<String>,
        val environment: Map<String, String>,
        val gameIniEdits: List<UtIni.() -> Unit>,
        val userIniEdits: List<UtIni.() -> Unit>,
    )

    companion object {
        /**
         * The time a killed run is given to report its exit code.
         */
        private val KILL_TIMEOUT = 5.seconds

        private const val KILLED_EXIT_CODE = -1

        /**
         * The pattern the name of an instance matches, so that it can be part of a file name.
         */
        private val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

        private val log = logger<UtInstance>()
    }
}

/**
 * The exit code of a finished [UtInstance] and the contents of the files it wrote, read before they were removed.
 *
 * @property exitCode The code the process exited with, or `-1` for a killed run that reported none.
 * @property log The lines of the run's log.
 * @property gameIni The game configuration as the run left it.
 * @property userIni The user configuration as the run left it.
 * @property exec The lines of the exec script the run played.
 */
data class UtInstanceResult(
    val exitCode: Int,
    val log: List<String>,
    val gameIni: String,
    val userIni: String,
    val exec: List<String>,
)
