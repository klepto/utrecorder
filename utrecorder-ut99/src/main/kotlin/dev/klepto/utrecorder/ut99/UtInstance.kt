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
import dev.klepto.utrecorder.common.process.execute
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeLines
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The exit code [play] reports for a run it killed after [Duration] ran out, because the process reported none.
 */
private const val KILLED_EXIT_CODE = -1

/**
 * The logger [play] warns through when a run outlives its timeout.
 */
private val log = logger<UtInstance>()

/**
 * One run of an installation: the files it adds to the installation's `System` directory, named by the run's
 * [Workspace] as `<name>-<id>-<file>`, and, while [play] runs it, the process reading them.
 *
 * Built by [UtInstallation.instance]. [play] writes the files, starts the process, runs a block against it, awaits it
 * and reads the files back into a [UtInstanceResult], removing them. A run that throws keeps its files, except the
 * executable copy, in `<home>/workspace/<name>-<id>/`. A run that returns or is cancelled has every file removed.
 *
 * @property name The name the run is logged under and its files are named after.
 */
class UtInstance private constructor(
    val name: String,
    private val installation: UtInstallation,
    private val settings: Settings,
    private val workspace: Workspace,
) {
    private val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

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

    /**
     * Writes the configuration copies, the exec script and the executable copy of this run. Called by [play];
     * nothing else should call it.
     */
    suspend fun writeFiles() {
        copyIni(installation.gameIniPath, gameIniPath, settings.gameIniEdits)
        copyIni(installation.userIniPath, userIniPath, settings.userIniEdits)
        installation.systemDirectory["Running.ini"].deleteIfExists()
        writeExec()
        installation.executablePath.copy(executablePath) { "UT executable not found at ${installation.executablePath}" }
        executablePath.toFile().setExecutable(true)
    }

    private suspend fun copyIni(
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
     * The result of this run exiting with [exitCode], holding the contents of the files it wrote. Called by [play];
     * nothing else should call it.
     */
    suspend fun readResult(exitCode: Int): UtInstanceResult =
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
     * [failure] as suppressed. Called by [play]; nothing else should call it.
     *
     * @throws IOException if there is no [failure] and the files cannot be removed, the first failure thrown with the
     * other suppressed.
     */
    suspend fun deleteFiles(failure: Throwable?) {
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
    private suspend fun removeFiles() {
        listOf(deleteExecutableCatching(), runCatching { workspace.delete() })
            .mapNotNull { it.exceptionOrNull() }
            .mergeAndThrow()
    }

    /**
     * Deletes the executable copy, then moves every other file of the run into `<home>/workspace/<name>-<id>/`, trying
     * both, and returns the failures of the two.
     */
    private suspend fun keepFiles(): List<Throwable> =
        listOf(deleteExecutableCatching(), runCatching { workspace.persistShared() })
            .mapNotNull { it.exceptionOrNull() }

    private suspend fun deleteExecutableCatching(): Result<Unit> = executablePath.deleteCatching(attempts = 10, delay = 100.milliseconds)

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
         * An unstarted instance of the run this builder describes. Building one opens the run's workspace, pruning the
         * expired leftovers of earlier runs of the same name, and writes nothing into the installation.
         *
         * @throws IllegalArgumentException if the name does not match `[A-Za-z0-9._-]+`, or [arguments] are set without
         * an [executable].
         */
        suspend fun build(): UtInstance {
            require(NAME_PATTERN.matches(name)) { "Instance name '$name' does not match ${NAME_PATTERN.pattern}" }
            require(arguments.isEmpty() || executable != null) {
                "Instance $name takes arguments ahead of the game executable but starts no program to receive them"
            }
            val workspace = Workspace.open(name, installation.systemDirectory.toAbsolutePath(), directories)
            return UtInstance(name, installation, settings(), workspace)
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
     * The single run [play] hands its [block]: the process output, and nothing else. Meaningless away from [play],
     * which is why it is nested here rather than a type of its own.
     */
    class Run(
        private val process: ExecutableProcess,
    ) {
        /**
         * The process output. See [ExecutableProcess.drain].
         */
        fun drain(): Flow<String> = process.drain()
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
         * The pattern the name of an instance matches, so that it can be part of a file name.
         */
        private val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}

/**
 * Writes this run's files, starts its process, runs [block] against it, awaits it within [timeout] and returns the
 * result, having removed the run's files. [timeout] covers [block] and the wait together, not the wait alone; a run
 * that outlives it is killed and reported with [UtInstanceResult.exitCode] `-1` instead of throwing. The process is
 * closed once [block] and the wait are done, whichever way, forcibly if it outlives [Executable.closeTimeout] past
 * that.
 *
 * A run that throws keeps its files, except the executable copy, in `<home>/workspace/<name>-<id>/`; a run that
 * returns or whose coroutine is cancelled has every file removed. The clean-up of a cancelled run runs under
 * [NonCancellable], so cancellation still leaves nothing behind.
 *
 * @throws IOException if writing the files, or removing or moving them back, fails.
 */
suspend fun <T> UtInstance.play(
    timeout: Duration = Duration.INFINITE,
    block: suspend UtInstance.Run.() -> T,
): UtInstanceResult =
    try {
        writeFiles()
        val result = readResult(playExitCode(timeout, block))
        deleteFiles(null)
        result
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { deleteFiles(null) }
        throw cancelled
    } catch (failure: Exception) {
        deleteFiles(failure)
        throw failure
    }

/**
 * The exit code of running [block] against the started process and awaiting it within [timeout], `-1` when the
 * process outlived [timeout] and was killed instead.
 *
 * [block] and the wait run under separate [withTimeout] calls, sharing [timeout]'s budget through [remaining],
 * rather than one [withTimeout] wrapping both: nesting [ExecutableProcess.await]'s own [withTimeout] inside this
 * function's would let this function's [TimeoutCancellationException] be caught by [ExecutableProcess.await]'s
 * `catch` clause, which matches by type and cannot tell the two apart, misreporting an outer timeout as its own.
 */
private suspend fun <T> UtInstance.playExitCode(
    timeout: Duration,
    block: suspend UtInstance.Run.() -> T,
): Int =
    try {
        executable.execute {
            val process = this
            val mark = TimeSource.Monotonic.markNow()
            try {
                withTimeout(timeout) { UtInstance.Run(process).block() }
            } catch (timedOut: TimeoutCancellationException) {
                throw TimeoutException("Instance $name outlived its timeout and was killed").apply { initCause(timedOut) }
            }
            process.await(remaining(timeout, mark)).exitCode
        }
    } catch (timedOut: TimeoutException) {
        log.warn(timedOut) { timedOut.message.orEmpty() }
        KILLED_EXIT_CODE
    }

/**
 * The remainder of [timeout]'s budget left after [mark], never less than one millisecond so it never fails
 * [withTimeout]'s own positivity check.
 */
private fun remaining(
    timeout: Duration,
    mark: TimeSource.Monotonic.ValueTimeMark,
): Duration = (timeout - mark.elapsedNow()).coerceAtLeast(1.milliseconds)

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
