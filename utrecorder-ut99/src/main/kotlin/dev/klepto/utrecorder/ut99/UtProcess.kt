package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.ExecutableProcess
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.execute
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.link
import dev.klepto.utrecorder.common.io.readFilteredLinesOrEmpty
import dev.klepto.utrecorder.common.io.readLastLinesOrEmpty
import dev.klepto.utrecorder.common.io.readLinesOrEmpty
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * Mutable settings of one run of an installation, which [build] turns into a [UtProcess].
 *
 * Configured through the block of [UtProcess.build].
 *
 * @property installation The installation the run is made against.
 * @property name The name the run is logged under and its files are named after.
 */
class UtProcessBuilder(
    val installation: UtInstallation,
    val name: String,
) {
    /**
     * The program the run starts in front of the game executable copy, or `null` to start the copy itself.
     */
    var program: Path? = null

    /**
     * The arguments [program] takes ahead of the path of the game executable copy.
     */
    val programArguments = mutableListOf<String>()

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
     * The environment variables the run starts with, over those of the current process.
     */
    val environment = mutableMapOf<String, String>()

    /**
     * The text the run's game configuration starts from, or `null` to start from the installation's own.
     */
    var baseGameIni: String? = null

    /**
     * The text the run's user configuration starts from, or `null` to start from the installation's own.
     */
    var baseUserIni: String? = null

    /**
     * The filter that picks the lines of the run's log kept in [UtProcessResult.log]. If not set, keeps no line.
     */
    var logFilter: (String) -> Boolean = { false }

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
     * Returns the process this builder describes.
     *
     * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [programArguments] are set
     * without a [program].
     */
    fun build(): UtProcess =
        UtProcess(
            installation = installation,
            name = name,
            program = program,
            programArguments = programArguments,
            map = map,
            exec = exec,
            sound = sound,
            windowed = windowed,
            environment = environment,
            baseGameIni = baseGameIni,
            baseUserIni = baseUserIni,
            gameIniEdits = gameIniEdits,
            userIniEdits = userIniEdits,
            logFilter = logFilter,
        )
}

/**
 * The immutable settings of one run of an installation and, once [launch] starts it, the process reading them. Touches
 * neither the installation nor the disk until then.
 *
 * Use [UtProcess.build] to build one, [launch] to run it, and [drain] to read its output while it runs. One process
 * runs once: a second [launch] fails.
 *
 * @property installation The installation the run is made against.
 * @property name The name the run is logged under and its files are named after.
 * @property program The program the run starts in front of the game executable copy, or `null` to start the copy
 * itself.
 * @property map The map or demo the run opens, or `null` to open the game's default.
 * @property sound `true` when the run plays sound.
 * @property windowed `true` when the run draws into a window, `false` when it draws over the whole screen.
 * @property baseGameIni The text the run's game configuration starts from, or `null` to start from the installation's
 * own.
 * @property baseUserIni The text the run's user configuration starts from, or `null` to start from the installation's
 * own.
 * @property logFilter The filter that picks the lines of the run's log kept in [UtProcessResult.log].
 * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [programArguments] are set without a
 * [program].
 */
class UtProcess(
    val installation: UtInstallation,
    val name: String,
    val program: Path?,
    programArguments: List<String>,
    val map: String?,
    exec: List<String>,
    val sound: Boolean,
    val windowed: Boolean,
    environment: Map<String, String>,
    val baseGameIni: String?,
    val baseUserIni: String?,
    gameIniEdits: List<UtIni.() -> Unit>,
    userIniEdits: List<UtIni.() -> Unit>,
    val logFilter: (String) -> Boolean,
) {
    /**
     * The arguments [program] takes ahead of the path of the game executable copy.
     */
    val programArguments: List<String> = programArguments.toList()

    /**
     * The lines of the exec script the run plays.
     */
    val exec: List<String> = exec.toList()

    /**
     * The environment variables the run starts with, over those of the current process.
     */
    val environment: Map<String, String> = environment.toMap()

    /**
     * The edits the run applies to its copy of the installation's game configuration.
     */
    val gameIniEdits: List<UtIni.() -> Unit> = gameIniEdits.toList()

    /**
     * The edits the run applies to its copy of the installation's user configuration.
     */
    val userIniEdits: List<UtIni.() -> Unit> = userIniEdits.toList()

    /**
     * The `System` directory of the installation, the one the run works in and stages its files in.
     */
    private val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

    /**
     * `true` once [launch] has been called, so that a second call fails.
     */
    private val launched = AtomicBoolean(false)

    /**
     * The started process, completed by [launch] once the process runs, or with the failure of a launch that ended
     * before. [drain] awaits it.
     */
    private val startedProcess = CompletableDeferred<ExecutableProcess>()

    init {
        require(NAME_PATTERN.matches(name)) { "Process name '$name' does not match ${NAME_PATTERN.pattern}" }
        require(this.programArguments.isEmpty() || program != null) {
            "Process $name takes arguments ahead of the game executable but starts no program to receive them"
        }
    }

    /**
     * Returns a cold flow of the output lines of this run.
     *
     * The flow can be collected before [launch] starts the run. It suspends until the process runs, replays the lines
     * the process already emitted and emits every line still to come. It completes once the output stream of the
     * process closes. If [launch] fails before the process runs, the flow fails with the same exception. See
     * [ExecutableProcess.drain].
     */
    fun drain(): Flow<String> = flow { emitAll(startedProcess.await().drain()) }

    /**
     * Returns the result of this run once the process exits.
     *
     * Opens the workspace of the run in the installation's `System` directory, stages the files the run reads, starts
     * the process and awaits it. The workspace closes once the process has exited and the result has been read.
     *
     * [timeout] covers the whole run. If the run outlives [timeout], it is killed and reported with
     * [UtProcessResult.exitCode] `-1` instead of throwing. If [timeout] is not provided, waits without limit.
     *
     * @throws IllegalStateException if this process has already been launched.
     * @throws IOException if staging the files, or moving them into the workspace afterwards, fails.
     */
    suspend fun launch(timeout: Duration = Duration.INFINITE): UtProcessResult {
        check(launched.compareAndSet(false, true)) { "Process $name has already been launched" }
        return runCatching { run(timeout) }
            .onFailure(startedProcess::completeExceptionally)
            .getOrThrow()
    }

    /**
     * Returns the result of this run once the process exits, staged and started in the workspace of the run.
     */
    private suspend fun run(timeout: Duration): UtProcessResult =
        workspace(name, systemDirectory, installation.config.directoriesConfig) { workspace ->
            val files = files(workspace)
            stage(files)
            executable(files).execute {
                startedProcess.complete(this)
                result(files, this, exitCode(timeout))
            }
        }

    /**
     * Returns the files a run in [workspace] reads, each named after the file of the installation it copies.
     *
     * [launch] stages them. To read the command line of a run without starting it, pass them to [executable].
     */
    fun files(workspace: Workspace): UtProcessFiles =
        UtProcessFiles(
            executable = workspace[installation.executablePath.name],
            gameIni = workspace[installation.gameIniPath.name],
            userIni = workspace[installation.userIniPath.name],
            exec = workspace[EXEC_FILE],
            log = workspace[LOG_FILE],
        )

    /**
     * Writes the configuration copies, the exec script and the executable copy of the run, and removes the lock file
     * the installation may hold from an earlier run.
     */
    private suspend fun stage(files: UtProcessFiles) {
        stageIni(installation.gameIniPath, baseGameIni, files.gameIni, gameIniEdits)
        stageIni(installation.userIniPath, baseUserIni, files.userIni, userIniEdits)
        stageExec(files)
        stageExecutable(files)
    }

    /**
     * Writes [base] to [target] and applies [edits] to the result.
     *
     * If [base] is `null`, copies the configuration at [source] to [target] instead.
     */
    private suspend fun stageIni(
        source: Path,
        base: String?,
        target: Path,
        edits: List<UtIni.() -> Unit>,
    ) {
        when (base) {
            null -> source.copy(target) { "UT configuration not found at $source" }
            else -> withContext(Dispatchers.IO) { target.writeText(base, UtPlatform.CHARSET) }
        }
        if (edits.isEmpty()) return

        val ini = UtIni.read(target)
        edits.forEach { edit -> ini.edit() }
        ini.write(target)
    }

    /**
     * Writes the exec script of the run.
     *
     * If the run plays no exec script, writes nothing.
     */
    private suspend fun stageExec(files: UtProcessFiles) {
        if (exec.isEmpty()) return
        withContext(Dispatchers.IO) { files.exec.writeLines(exec, UtPlatform.CHARSET) }
    }

    /**
     * Removes the lock file an earlier run may have left in the installation, and stages the executable copy of the
     * run.
     *
     * On Linux the copy is a hard link to the installation's executable. On Windows it is a copy of the file, made
     * executable.
     */
    private suspend fun stageExecutable(files: UtProcessFiles) {
        val executable = installation.executablePath
        val failureMessage = { "UT executable not found at $executable" }
        withContext(Dispatchers.IO) { systemDirectory[RUNNING_INI].deleteIfExists() }
        when (installation.platform) {
            UtPlatform.LINUX -> {
                executable.link(files.executable, failureMessage)
            }

            UtPlatform.WINDOWS -> {
                executable.copy(files.executable, failureMessage = failureMessage)
                withContext(Dispatchers.IO) { files.executable.toFile().setExecutable(true) }
            }
        }
    }

    /**
     * Returns the exit code of this process once it exits within [timeout].
     *
     * If the process outlives [timeout], it is killed and [KILLED_EXIT_CODE] is returned instead.
     */
    private suspend fun ExecutableProcess.exitCode(timeout: Duration): Int =
        try {
            await(timeout).exitCode
        } catch (timedOut: TimeoutException) {
            log.warn(timedOut) { "Process $name outlived its timeout and was killed" }
            close()
            KILLED_EXIT_CODE
        }

    /**
     * Returns the command line of the run reading [files].
     *
     * The command line is `<program> <programArguments> <executable copy> <game arguments>` when the run starts a
     * [program], and the executable copy followed by the game arguments otherwise.
     */
    fun executable(files: UtProcessFiles): Executable =
        Executable.build(program ?: files.executable) {
            workingDirectory = systemDirectory
            arguments += programArguments(files)
            arguments += gameArguments()
            arguments += "-ini=${files.gameIni.absolutePathString()}"
            arguments += "-userini=${files.userIni.absolutePathString()}"
            arguments += execArguments(files)
            arguments += "-log=${files.log.name}"
            arguments += "-forcelogflush"
            arguments += installation.platform.homeArguments
            environment += this@UtProcess.environment
            outputFilter = logFilter
            prependLibraryPath(systemDirectory)
        }

    /**
     * Returns the arguments [program] takes ahead of the game, the path of the executable copy last.
     *
     * If the run starts the executable copy itself, returns an empty list instead.
     */
    private fun programArguments(files: UtProcessFiles): List<String> =
        when (program) {
            null -> emptyList()
            else -> programArguments + files.executable.absolutePathString()
        }

    /**
     * Returns the arguments the game takes: the map, the joystick always off, sound off when the run plays none, and
     * the display mode.
     */
    private fun gameArguments(): List<String> =
        listOfNotNull(
            map,
            "-nosound".takeUnless { sound },
            "-nojoy",
            displayArgument(),
        )

    /**
     * Returns the display mode argument of the run.
     */
    private fun displayArgument(): String =
        when {
            windowed -> "-windowed"
            else -> "-fullscreen"
        }

    /**
     * Returns the exec script argument of the run.
     *
     * If the run plays no exec script, returns an empty list instead.
     */
    private fun execArguments(files: UtProcessFiles): List<String> =
        when {
            exec.isEmpty() -> emptyList()
            else -> listOf("-EXEC=${files.exec.absolutePathString()}")
        }

    /**
     * Returns the result of the run of [process] that exited with [exitCode], holding the contents of [files] as the
     * run left them.
     */
    private suspend fun result(
        files: UtProcessFiles,
        process: ExecutableProcess,
        exitCode: Int,
    ): UtProcessResult =
        UtProcessResult(
            exitCode = exitCode,
            log = log(files, process),
            logTail = logTail(files, process),
            gameIni = files.gameIni.readTextOrEmpty(UtPlatform.CHARSET),
            userIni = files.userIni.readTextOrEmpty(UtPlatform.CHARSET),
            exec = files.exec.readLinesOrEmpty(UtPlatform.CHARSET),
        )

    /**
     * Returns the lines of the run's log that [logFilter] accepts, in log order.
     *
     * When the game of the installation echoes its log, the lines come from the output of [process]. Otherwise, they
     * come from the log in [files].
     */
    private suspend fun log(
        files: UtProcessFiles,
        process: ExecutableProcess,
    ): List<String> =
        when (installation.platform.echoesLog) {
            true -> process.filteredOutput
            false -> files.log.readFilteredLinesOrEmpty(UtPlatform.CHARSET, filter = logFilter)
        }

    /**
     * Returns the last [LOG_TAIL_LINES] lines of the run's log, whatever [logFilter] accepts, in log order.
     *
     * When the game of the installation echoes its log, the lines come from the output of [process]. Otherwise, they
     * come from the log in [files].
     */
    private suspend fun logTail(
        files: UtProcessFiles,
        process: ExecutableProcess,
    ): List<String> =
        when (installation.platform.echoesLog) {
            true -> process.output.takeLast(LOG_TAIL_LINES)
            false -> files.log.readLastLinesOrEmpty(LOG_TAIL_LINES, UtPlatform.CHARSET)
        }

    companion object {
        /**
         * The pattern the name of a process matches, so that it can be part of a file name.
         */
        private val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

        /**
         * The name of the exec script a run plays.
         */
        private const val EXEC_FILE = "exec.txt"

        /**
         * The name of the log a run writes.
         */
        private const val LOG_FILE = "UnrealTournament.log"

        /**
         * The number of last lines of the log a run keeps in [UtProcessResult.logTail].
         */
        private const val LOG_TAIL_LINES = 100

        /**
         * The name of the lock file the game writes while it runs, left behind by a run that was killed.
         */
        private const val RUNNING_INI = "Running.ini"

        /**
         * The exit code [launch] reports for a run it killed after its timeout ran out, since such a run reports no
         * exit code of its own.
         */
        private const val KILLED_EXIT_CODE = -1

        private val log = logger<UtProcess>()

        /**
         * Returns the process of a run of [installation] named [name], as [configure] describes it.
         *
         * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [configure] sets arguments
         * without a program.
         */
        fun build(
            installation: UtInstallation,
            name: String,
            configure: UtProcessBuilder.() -> Unit = {},
        ): UtProcess = UtProcessBuilder(installation, name).apply(configure).build()
    }
}

/**
 * The files one run of a [UtProcess] adds to the installation's `System` directory, each named by the run's workspace.
 *
 * Returned by [UtProcess.files].
 *
 * @property executable The copy of the game executable the run launches.
 * @property gameIni The copy of the game configuration the run reads.
 * @property userIni The copy of the user configuration the run reads.
 * @property exec The exec script the run plays.
 * @property log The log the run writes.
 */
class UtProcessFiles(
    val executable: Path,
    val gameIni: Path,
    val userIni: Path,
    val exec: Path,
    val log: Path,
)

/**
 * The exit code of a finished [UtProcess] and the contents of the files it wrote, read once it exited.
 *
 * Returned by [UtProcess.launch].
 *
 * @property exitCode The code the process exited with, or `-1` for a killed run that reported none. On Linux, a process
 * that a signal ended reports 128 plus the number of the signal, such as `139` for a segmentation fault.
 * @property log The lines of the run's log that the run's log filter accepts, in log order. On a platform whose game
 * echoes its log, they are read from the output of the game, so none is lost when the game can no longer write its log
 * file. Otherwise, they are read from the log file, and a line longer than 65536 characters is cut to its first 65536
 * characters. If the run wrote no log, it is empty.
 * @property logTail The last lines of the run's log, at most 100 of them, whatever the run's log filter accepts, in log
 * order. On a platform whose game echoes its log, they are the last lines of the output of the game. Otherwise, only
 * the lines within the last 1 MiB of the log file are kept. If the run wrote no log, it is empty.
 * @property gameIni The game configuration as the run left it.
 * @property userIni The user configuration as the run left it.
 * @property exec The lines of the exec script the run played.
 */
data class UtProcessResult(
    val exitCode: Int,
    val log: List<String>,
    val logTail: List<String>,
    val gameIni: String,
    val userIni: String,
    val exec: List<String>,
) {
    /**
     * Returns the result of [block].
     *
     * If [block] throws an [IllegalStateException], throws one whose message also names the exit code of this run
     * instead, with the original as its cause. A [CancellationException] propagates unchanged.
     */
    inline fun <T> namingExitCode(block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            throw IllegalStateException("${failure.message} (exit code $exitCode)", failure)
        }
}
