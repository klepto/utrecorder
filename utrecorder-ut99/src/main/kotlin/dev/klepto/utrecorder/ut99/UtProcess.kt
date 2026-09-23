package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.readLinesOrEmpty
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableProcess
import dev.klepto.utrecorder.common.process.execute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * A mutable description of one run of an installation, built into the immutable value by [UtProcess.build].
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
     * The immutable process this builder describes.
     *
     * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [programArguments] are set
     * without a [program].
     */
    fun build(): UtProcess =
        UtProcess(
            installation = installation,
            name = name,
            program = program,
            programArguments = programArguments.toList(),
            map = map,
            exec = exec.toList(),
            sound = sound,
            windowed = windowed,
            environment = environment.toMap(),
            baseGameIni = baseGameIni,
            baseUserIni = baseUserIni,
            gameIniEdits = gameIniEdits.toList(),
            userIniEdits = userIniEdits.toList(),
        )
}

/**
 * The settings of one run of an installation and, once [launch] starts it, the process reading them. Touches neither
 * the installation nor the disk until then.
 *
 * Use [UtProcess.build] to build one, [launch] to run it, and [drain] to read its output while it runs. One process
 * runs once: a second [launch] fails.
 *
 * @property installation The installation the run is made against.
 * @property name The name the run is logged under and its files are named after.
 * @property program The program the run starts in front of the game executable copy, or `null` to start the copy
 * itself.
 * @property programArguments The arguments [program] takes ahead of the path of the game executable copy.
 * @property map The map or demo the run opens, or `null` to open the game's default.
 * @property exec The lines of the exec script the run plays.
 * @property sound `true` when the run plays sound.
 * @property windowed `true` when the run draws into a window, `false` when it draws over the whole screen.
 * @property environment The environment variables the run starts with, over those of the current process.
 * @property baseGameIni The text the run's game configuration starts from, or `null` to start from the installation's
 * own.
 * @property baseUserIni The text the run's user configuration starts from, or `null` to start from the installation's
 * own.
 * @property gameIniEdits The edits the run applies to its copy of the installation's game configuration.
 * @property userIniEdits The edits the run applies to its copy of the installation's user configuration.
 * @throws IllegalArgumentException if [name] does not match `[A-Za-z0-9._-]+`, or [programArguments] are set without a
 * [program].
 */
class UtProcess(
    val installation: UtInstallation,
    val name: String,
    val program: Path?,
    val programArguments: List<String>,
    val map: String?,
    val exec: List<String>,
    val sound: Boolean,
    val windowed: Boolean,
    val environment: Map<String, String>,
    val baseGameIni: String?,
    val baseUserIni: String?,
    val gameIniEdits: List<UtIni.() -> Unit>,
    val userIniEdits: List<UtIni.() -> Unit>,
) {
    /**
     * The directory of the installation the run works in, the one holding the files it stages.
     */
    private val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

    /**
     * The started process, completed by [launch] once the process is running and never completed again, so that
     * [drain] can be collected before the run starts and a second [launch] can be refused.
     */
    private val startedProcess = CompletableDeferred<ExecutableProcess>()

    init {
        require(NAME_PATTERN.matches(name)) { "Process name '$name' does not match ${NAME_PATTERN.pattern}" }
        require(programArguments.isEmpty() || program != null) {
            "Process $name takes arguments ahead of the game executable but starts no program to receive them"
        }
    }

    /**
     * A cold flow over the output of this run, collectable before [launch] starts it: it suspends until the process is
     * running, replays the lines it already emitted, emits every line still to come and completes once the process
     * output stream closes. See [ExecutableProcess.drain].
     */
    fun drain(): Flow<String> = flow { emitAll(startedProcess.await().drain()) }

    /**
     * Opens the workspace of this run in the installation's `System` directory, stages the files the run reads, starts
     * the process, awaits it within [timeout] and returns its result. The workspace closes once the process has exited
     * and the result has been read, so the files outlive the process rather than the other way round.
     *
     * [timeout] covers the whole run; a run that outlives it is killed and reported with [UtProcessResult.exitCode]
     * `-1` instead of throwing.
     *
     * @throws IllegalStateException if this process has already been launched.
     * @throws IOException if staging the files, or removing them afterwards, fails.
     */
    suspend fun launch(timeout: Duration = Duration.INFINITE): UtProcessResult {
        check(!startedProcess.isCompleted) { "Process $name has already been launched" }
        return workspace(name, systemDirectory, installation.config.directoriesConfig) { workspace ->
            play(files(workspace), timeout)
        }
    }

    /**
     * The files the run at [workspace] reads, each named after the file of the installation it copies. [launch] stages
     * them; a caller that only wants to read the command line the run starts pairs them with [executable].
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
     * Stages [files], runs the process reading them and returns its result.
     */
    private suspend fun play(
        files: UtProcessFiles,
        timeout: Duration,
    ): UtProcessResult {
        stage(files)
        val exitCode = exitCode(files, timeout)
        return result(files, exitCode)
    }

    /**
     * Writes the configuration copies, the exec script and the executable copy of the run, and removes the lock file
     * the installation may hold from an earlier run.
     */
    private suspend fun stage(files: UtProcessFiles) {
        stageIni(installation.gameIniPath, baseGameIni, files.gameIni, gameIniEdits)
        stageIni(installation.userIniPath, baseUserIni, files.userIni, userIniEdits)
        installation.systemDirectory[RUNNING_INI].deleteIfExists()
        stageExec(files)
        installation.executablePath.copy(files.executable) { "UT executable not found at ${installation.executablePath}" }
        files.executable.toFile().setExecutable(true)
    }

    /**
     * Writes [base] to [target], or copies the configuration at [source] there when [base] is `null`, and applies
     * [edits] to the result.
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
     * Writes the exec script of the run, or nothing when the run plays none.
     */
    private fun stageExec(files: UtProcessFiles) {
        if (exec.isEmpty()) return
        files.exec.writeLines(exec, UtPlatform.CHARSET)
    }

    /**
     * The exit code of starting the process reading [files] and awaiting it within [timeout], [KILLED_EXIT_CODE] when
     * the process outlived [timeout] and was killed instead. Completes [startedProcess], so a collector of [drain] is
     * served from here on.
     */
    private suspend fun exitCode(
        files: UtProcessFiles,
        timeout: Duration,
    ): Int =
        try {
            executable(files).execute {
                startedProcess.complete(this)
                await(timeout).exitCode
            }
        } catch (timedOut: TimeoutException) {
            log.warn(timedOut) { "Process $name outlived its timeout and was killed" }
            KILLED_EXIT_CODE
        }

    /**
     * The command line of the run reading [files]: `<program> <programArguments> <executable copy> <game arguments>`
     * when the run overrides its program, otherwise the executable copy followed by the game arguments.
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
            arguments += installation.platform.homeArguments
            environment += this@UtProcess.environment
            prependLibraryPath(systemDirectory)
        }

    /**
     * The arguments the overriding program takes ahead of the game, the path of the executable copy last. Empty for a
     * run that starts the copy itself.
     */
    private fun programArguments(files: UtProcessFiles): List<String> =
        when (program) {
            null -> emptyList()
            else -> programArguments + files.executable.absolutePathString()
        }

    /**
     * The arguments the game takes: the map, the joystick always off, sound off when the run plays none, and the
     * display mode.
     */
    private fun gameArguments(): List<String> =
        listOfNotNull(
            map,
            "-nosound".takeUnless { sound },
            "-nojoy",
            displayArgument(),
        )

    /**
     * The display mode argument of the run.
     */
    private fun displayArgument(): String =
        when {
            windowed -> "-windowed"
            else -> "-fullscreen"
        }

    /**
     * The exec script argument of the run, empty for a run that plays none.
     */
    private fun execArguments(files: UtProcessFiles): List<String> =
        when {
            exec.isEmpty() -> emptyList()
            else -> listOf("-EXEC=${files.exec.absolutePathString()}")
        }

    /**
     * The result of the run exiting with [exitCode], holding the contents of [files] as the run left them.
     */
    private suspend fun result(
        files: UtProcessFiles,
        exitCode: Int,
    ): UtProcessResult =
        UtProcessResult(
            exitCode = exitCode,
            log = files.log.readLinesOrEmpty(UtPlatform.CHARSET),
            gameIni = files.gameIni.readTextOrEmpty(UtPlatform.CHARSET),
            userIni = files.userIni.readTextOrEmpty(UtPlatform.CHARSET),
            exec = files.exec.readLinesOrEmpty(UtPlatform.CHARSET),
        )

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
         * The name of the lock file the game writes while it runs, left behind by a run that was killed.
         */
        private const val RUNNING_INI = "Running.ini"

        /**
         * The exit code [launch] reports for a run it killed after its timeout ran out, because the process reported
         * none.
         */
        private const val KILLED_EXIT_CODE = -1

        /**
         * The logger.
         */
        private val log = logger<UtProcess>()

        /**
         * The run of [installation] named [name] that [configure] describes.
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
 * The exit code of a finished [UtProcess] and the contents of the files it wrote, read before they were removed.
 *
 * Returned by [UtProcess.launch].
 *
 * @property exitCode The code the process exited with, or `-1` for a killed run that reported none.
 * @property log The lines of the run's log.
 * @property gameIni The game configuration as the run left it.
 * @property userIni The user configuration as the run left it.
 * @property exec The lines of the exec script the run played.
 */
data class UtProcessResult(
    val exitCode: Int,
    val log: List<String>,
    val gameIni: String,
    val userIni: String,
    val exec: List<String>,
)
