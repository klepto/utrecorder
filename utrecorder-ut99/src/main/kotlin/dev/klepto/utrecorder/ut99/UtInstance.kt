package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.readLinesOrEmpty
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableProcess
import dev.klepto.utrecorder.common.process.executable
import dev.klepto.utrecorder.common.time.TimestampId
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A mutable description of one run of an installation, built into the [UtInstance] it describes by
 * [UtInstallation.instance].
 *
 * @property installation The installation the run is made against.
 * @property name The name the run is logged under.
 */
class UtInstanceBuilder(
    private val installation: UtInstallation,
    private val name: String,
) {
    /**
     * The program the run starts, or `null` to start the game itself. A run that starts something else hands that
     * program the path of the executable copy the run mints, so that a wrapper launches the game of this run.
     */
    var executable: Path? = null

    /**
     * The arguments the started program takes ahead of the path of the game executable copy. Only a run overriding
     * [executable] has that position, so setting these without overriding it fails the build.
     */
    val arguments = mutableListOf<String>()

    /**
     * The map or demo the run opens, or `null` to leave the game to open what it opens by itself.
     */
    var map: String? = null

    /**
     * The lines of the exec script the run plays. The instance writes them as the exec file of the run, so that a
     * caller hands over playback commands as content rather than as a file to rewrite before every run.
     */
    var exec: List<String> = emptyList()

    /**
     * Marks whether the run has sound.
     */
    var sound: Boolean = true

    /**
     * Marks whether the run draws into a window rather than over the whole screen.
     */
    var windowed: Boolean = false

    /**
     * Marks whether the run reads a joystick. A run without one switches it off on the command line; a run with one
     * leaves the game to the configuration it reads.
     */
    var joystick: Boolean = false

    /**
     * The environment variables the run starts with, layered over the ones the current process holds.
     */
    val environment = mutableMapOf<String, String>()

    /**
     * The edits the run makes to its copy of the game configuration, in the order they were registered.
     */
    private val gameIniEdits = mutableListOf<UtIni.() -> Unit>()

    /**
     * The edits the run makes to its copy of the user configuration, in the order they were registered.
     */
    private val userIniEdits = mutableListOf<UtIni.() -> Unit>()

    /**
     * Applies [edit] to the copy of the game configuration the run reads, seeded from the configuration of the
     * installation. The installation keeps its own.
     */
    fun gameIni(edit: UtIni.() -> Unit) {
        gameIniEdits += edit
    }

    /**
     * Applies [edit] to the copy of the user configuration the run reads, seeded from the configuration of the
     * installation. The installation keeps its own.
     */
    fun userIni(edit: UtIni.() -> Unit) {
        userIniEdits += edit
    }

    /**
     * Returns the unstarted instance this description builds. Throws if [arguments] are set without an [executable]
     * to take them.
     */
    fun build(): UtInstance {
        require(arguments.isEmpty() || executable != null) {
            "Instance $name takes arguments ahead of the game executable but starts no program to receive them"
        }
        return UtInstance(name, installation, settings())
    }

    /**
     * Returns the run description the properties of this builder amount to.
     */
    private fun settings(): UtInstanceSettings =
        UtInstanceSettings(
            program = executable,
            arguments = arguments.toList(),
            gameArguments = gameArguments(),
            exec = exec.toList(),
            environment = environment.toMap(),
            gameIniEdits = gameIniEdits.toList(),
            userIniEdits = userIniEdits.toList(),
        )

    /**
     * Returns the arguments the bound properties of this builder translate to, the map or demo first, since Unreal
     * Tournament reads the first argument as the URL it opens.
     */
    private fun gameArguments(): List<String> =
        listOfNotNull(
            map,
            "-nosound".takeUnless { sound },
            "-nojoy".takeUnless { joystick },
        ) + displayArgument()

    /**
     * Returns the argument naming the surface the run draws on, emitted either way so that a run never inherits the
     * choice from the configuration of the installation.
     */
    private fun displayArgument(): String =
        when {
            windowed -> "-windowed"
            else -> "-fullscreen"
        }
}

/**
 * What a [UtInstanceBuilder] amounts to: the arguments, the exec script, the environment and the configuration edits
 * of one run, held apart from the [UtInstance] carrying them out.
 *
 * @property program The program the run starts, or `null` when the run starts the executable copy of the game.
 * @property arguments The arguments the started program takes ahead of the path of the game executable copy, empty
 * when the run starts the game itself.
 * @property gameArguments The arguments the bound properties of the run translate to, passed ahead of the ones the
 * instance emits itself.
 * @property exec The lines of the exec script the run plays, empty when the run plays none.
 * @property environment The environment variables the run starts with, layered over the ones the current process
 * holds.
 * @property gameIniEdits The edits the run makes to its copy of the game configuration.
 * @property userIniEdits The edits the run makes to its copy of the user configuration.
 */
internal class UtInstanceSettings(
    val program: Path?,
    val arguments: List<String>,
    val gameArguments: List<String>,
    val exec: List<String>,
    val environment: Map<String, String>,
    val gameIniEdits: List<UtIni.() -> Unit>,
    val userIniEdits: List<UtIni.() -> Unit>,
)

/**
 * The files one run adds to an installation, every one of them carrying the `ut-<id>` prefix of that run.
 *
 * [UtInstance] owns one and reaches its paths through it. [write] mints them, [read] reads them back, and [delete]
 * removes them again, naming them one by one rather than sweeping the prefix, so that a run can never remove a file
 * of another one.
 *
 * @property installation The installation the files are added to.
 * @property name The name the run is logged under.
 * @property prefix The name every one of these files begins with.
 */
internal class UtInstanceFiles(
    private val installation: UtInstallation,
    private val name: String,
    private val prefix: String,
) {
    /**
     * The directory holding these files, which is also the directory the run starts in.
     */
    val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

    /**
     * The copy of the game executable of this run, so that a run never waits on the lock the game holds on its own
     * executable. The game is the file copied whatever the run starts: only the game locks itself, and a wrapper stays
     * where it is.
     */
    val executablePath: Path = systemDirectory["$prefix${installation.platform.executableSuffix}"]

    /**
     * The copy of the game configuration the run reads.
     */
    val gameIniPath: Path = systemDirectory["$prefix.ini"]

    /**
     * The copy of the user configuration the run reads.
     */
    val userIniPath: Path = systemDirectory["$prefix.user.ini"]

    /**
     * The exec script the run plays.
     */
    val execPath: Path = systemDirectory["$prefix.exec.txt"]

    /**
     * The log the run writes.
     */
    val logPath: Path = systemDirectory["$prefix.log"]

    /**
     * Copies the game executable and writes the configuration and the exec script [settings] describes. A write that
     * fails part of the way through takes the files it already made with it, so that a run that never started leaves
     * the installation as it found it.
     */
    fun write(settings: UtInstanceSettings) {
        runCatching { writeFiles(settings) }
            .onFailure { delete() }
            .getOrThrow()
    }

    /**
     * Writes the configuration and the exec script the run reads, and copies the game executable it starts, in the
     * order a caller reads about them.
     */
    private fun writeFiles(settings: UtInstanceSettings) {
        copyIni(installation.gameIniPath, gameIniPath, settings.gameIniEdits)
        copyIni(installation.userIniPath, userIniPath, settings.userIniEdits)
        writeExec(settings.exec)
        installation.executablePath.copy(executablePath) { "UT executable not found at ${installation.executablePath}" }
        executablePath.toFile().setExecutable(true)
    }

    /**
     * Copies the configuration at [source] to [target] and applies [edits] to the copy, so that the run reads what the
     * installer wrote together with what the caller changed, and the installation keeps its own configuration.
     */
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

    /**
     * Writes [exec] as the exec script of this run. A run that plays no script writes none, and is started without the
     * argument naming one.
     */
    private fun writeExec(exec: List<String>) {
        if (exec.isEmpty()) return
        val newline = System.lineSeparator()
        execPath.writeText(exec.joinToString(newline, postfix = newline), UtIni.CHARSET)
    }

    /**
     * Returns the result of a run that ended with [exitCode], reading back every file the run wrote. A file the run
     * never wrote reads as empty.
     */
    fun read(exitCode: Int): UtInstanceResult =
        UtInstanceResult(
            exitCode = exitCode,
            log = logPath.readLinesOrEmpty(UtIni.CHARSET),
            gameIni = gameIniPath.readTextOrEmpty(UtIni.CHARSET),
            userIni = userIniPath.readTextOrEmpty(UtIni.CHARSET),
            exec = execPath.readLinesOrEmpty(UtIni.CHARSET),
        )

    /**
     * Deletes every one of these files.
     */
    fun delete() {
        listOf(gameIniPath, userIniPath, execPath, logPath).forEach { it.deleteIfExists() }
        deleteExecutable()
    }

    /**
     * Deletes the executable copy of this run, retrying while the operating system still holds it. A copy that cannot
     * be deleted is left in place and logged, since cleanup must not fail a run that otherwise succeeded.
     */
    private fun deleteExecutable() {
        deleteExecutableCatching().onFailure { cause ->
            log.warn(cause) { "Instance $name could not delete its executable copy at $executablePath" }
        }
    }

    /**
     * Deletes the executable copy of this run and returns the outcome of the attempt that ended the retrying.
     */
    private fun deleteExecutableCatching(): Result<Boolean> {
        repeat(DELETE_ATTEMPTS - 1) {
            val outcome = runCatching { executablePath.deleteIfExists() }
            if (outcome.isSuccess) return outcome
            Thread.sleep(DELETE_DELAY.inWholeMilliseconds)
        }
        return runCatching { executablePath.deleteIfExists() }
    }

    companion object {
        /**
         * The number of times the executable copy of a run is deleted before the copy is left behind.
         */
        private const val DELETE_ATTEMPTS = 10

        /**
         * The time between two attempts to delete the executable copy of a run.
         */
        private val DELETE_DELAY = 100.milliseconds

        /**
         * Logger for this class.
         */
        private val log = logger<UtInstanceFiles>()
    }
}

/**
 * One run of an installation, holding the files the run adds to it and, once started, the process reading them.
 *
 * Use [UtInstallation.instance] to build one. A built instance has touched nothing: [start] copies the executable and
 * the configuration the run reads into the installation, [await] reads them back, deletes them, and returns them as a
 * [UtInstanceResult]. An instance only ever adds files carrying its own [id], so two runs against one installation do
 * not see each other.
 *
 * @property name The name the run is logged under.
 * @property installation The installation the run is made against.
 * @property settings What the run opens, plays, reads and starts under.
 */
class UtInstance internal constructor(
    val name: String,
    private val installation: UtInstallation,
    private val settings: UtInstanceSettings,
) {
    /**
     * The date and time naming this run, such as `2026-09-08-1450-49`. Every file the run adds to the installation
     * carries it.
     */
    val id: String = ids.next()

    /**
     * The files this run adds to the installation.
     */
    private val files = UtInstanceFiles(installation, name, "$FILE_PREFIX$id")

    /**
     * The copy of the game executable of this run, which the run starts unless it starts a program in front of it.
     */
    val executablePath: Path get() = files.executablePath

    /**
     * The copy of the game configuration the run reads.
     */
    val gameIniPath: Path get() = files.gameIniPath

    /**
     * The copy of the user configuration the run reads.
     */
    val userIniPath: Path get() = files.userIniPath

    /**
     * The exec script the run plays.
     */
    val execPath: Path get() = files.execPath

    /**
     * The log the run writes.
     */
    val logPath: Path get() = files.logPath

    /**
     * The command line the run starts, pointing the game at the files of this run. A run overriding the program it
     * starts runs it as `<program> <arguments> <executable copy> <game arguments>`; a run starting the game itself
     * runs the executable copy with nothing in front of it.
     */
    val executable: Executable =
        executable(settings.program ?: files.executablePath) {
            workingDirectory = files.systemDirectory
            arguments += wrapperArguments()
            arguments += settings.gameArguments
            arguments += "-ini=${files.gameIniPath.absolutePathString()}"
            arguments += "-userini=${files.userIniPath.absolutePathString()}"
            arguments += execArguments()
            arguments += "-log=${files.logPath.name}"
            arguments += installation.platform.homeArguments
            environment += settings.environment
            prependLibraryPath(files.systemDirectory)
        }

    /**
     * Returns the arguments the started program takes before the game arguments: the ones the caller supplied,
     * followed by the path of the executable copy the program launches. A run starting the game itself takes none,
     * since the copy is then the program.
     */
    private fun wrapperArguments(): List<String> =
        when (settings.program) {
            null -> emptyList()
            else -> settings.arguments + files.executablePath.absolutePathString()
        }

    /**
     * Returns the argument pointing the run at its own exec script, or no argument when the run plays none.
     */
    private fun execArguments(): List<String> =
        when {
            settings.exec.isEmpty() -> emptyList()
            else -> listOf("-EXEC=${files.execPath.absolutePathString()}")
        }

    /**
     * The process of the run, or `null` until the instance is started.
     */
    private var process: ExecutableProcess? = null

    /**
     * How the run ended, or `null` until it has been awaited. A capture that failed is held too, so that a second
     * await reports what the first one did rather than reading files that no longer exist.
     */
    private var outcome: Result<UtInstanceResult>? = null

    /**
     * Guards [outcome], so that two threads awaiting one run capture it once and both read the same capture.
     */
    private val outcomeLock = Any()

    /**
     * Marks whether the run is still going. A run that has not been started, and one that has already ended, is not.
     */
    val isRunning: Boolean get() = process?.isRunning == true

    /**
     * Copies the files of this run into the installation and starts the process reading them. Returns this instance,
     * so that a caller can chain [await]. A copy that fails part of the way through takes the files it already wrote
     * with it, so that a run that never started leaves the installation as it found it. Throws if the instance is
     * already started: a retry is a new instance with a new id.
     */
    fun start(): UtInstance =
        apply {
            check(process == null) { "Instance $name is already started" }
            files.write(settings)
            process = executable.start()
        }

    /**
     * Registers [consumer] to receive the output of the run as it arrives, so that a caller can follow a long run
     * instead of reading it once the run ends. Lines already emitted are replayed to the consumer before it is
     * registered. Returns this instance. Throws if the instance is not started.
     */
    fun drain(consumer: (String) -> Unit): UtInstance = apply { startedProcess().drain(consumer) }

    /**
     * Waits up to [timeout] for the run to finish, and returns the code it ended with together with the contents of
     * every file it wrote. A run that outlives the timeout is killed and returns the code the kill produced, since a
     * killed run is an outcome rather than a failure. The files of the run are deleted before this returns, whichever
     * way the run ended. Repeated calls return the result the first one captured. Throws if the instance is not
     * started.
     */
    fun await(timeout: Duration = Duration.INFINITE): UtInstanceResult =
        synchronized(outcomeLock) { outcome ?: captureOutcome(timeout) }.getOrThrow()

    /**
     * Returns how the run ended, waiting up to [timeout] for it, and holds the outcome for every later await.
     */
    private fun captureOutcome(timeout: Duration): Result<UtInstanceResult> =
        runCatching { captureResult(timeout) }.also { outcome = it }

    /**
     * Returns what the run wrote, waiting up to [timeout] for it to end. The files of the run are deleted on every
     * path out, so that a run ending in a failure leaves no more behind than one ending cleanly.
     */
    private fun captureResult(timeout: Duration): UtInstanceResult {
        val process = startedProcess()
        return try {
            process.use { files.read(exitCode(it, timeout)) }
        } finally {
            files.delete()
        }
    }

    /**
     * Returns the code [process] ended with, waiting up to [timeout] for it and killing the run once it expires.
     */
    private fun exitCode(
        process: ExecutableProcess,
        timeout: Duration,
    ): Int =
        runCatching { process.await(timeout).exitCode }
            .recoverCatching { cause -> killExitCode(process, cause) }
            .getOrThrow()

    /**
     * Returns the code a run that outlived its timeout ended with. The wait that timed out has already killed the
     * process, so this only reads what the kill produced, falling back to [KILLED_EXIT_CODE] when the killed run
     * reports nothing within [KILL_TIMEOUT]. A wait that failed for any other reason is handed back to the caller,
     * since only a timeout is an outcome of the run rather than a failure of it.
     */
    private fun killExitCode(
        process: ExecutableProcess,
        cause: Throwable,
    ): Int {
        if (cause !is TimeoutException) throw cause

        log.warn { "Instance $name outlived its timeout and was killed" }
        return runCatching { process.await(KILL_TIMEOUT).exitCode }.getOrDefault(KILLED_EXIT_CODE)
    }

    /**
     * Returns the process of this run, or throws if the instance is not started.
     */
    private fun startedProcess(): ExecutableProcess = checkNotNull(process) { "Instance $name is not started" }

    companion object {
        /**
         * The name every file of a run begins with, followed by the id of the run.
         */
        private const val FILE_PREFIX = "ut-"

        /**
         * The time a killed run is given to report the code it ended with.
         */
        private val KILL_TIMEOUT = 5.seconds

        /**
         * The code reported for a killed run that never reported one of its own.
         */
        private const val KILLED_EXIT_CODE = -1

        /**
         * The ids of every instance, so that two instances built within one second keep their files apart.
         */
        private val ids = TimestampId()

        /**
         * Logger for this class.
         */
        private val log = logger<UtInstance>()
    }
}

/**
 * What a finished run hands back: the code it exited with, and the contents of every file it wrote, read before the
 * instance deleted them.
 *
 * @property exitCode The code the process exited with.
 * @property log The lines the run wrote to its log.
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
