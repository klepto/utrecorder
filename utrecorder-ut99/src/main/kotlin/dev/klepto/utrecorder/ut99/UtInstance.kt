package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.deleteRecursivelyCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.readLinesOrEmpty
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableProcess
import dev.klepto.utrecorder.common.time.TimestampId
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
 * One run of an installation: the files it adds to the installation, all named after its [id], and, once started, the
 * process reading them.
 *
 * Built by [UtInstallation.instance]. [start] writes the files and starts the process; [await] waits for it, reads the
 * files back into a [UtInstanceResult] and deletes them.
 *
 * @property name The name the run is logged under.
 */
class UtInstance private constructor(
    val name: String,
    private val installation: UtInstallation,
    private val settings: Settings,
) {
    /**
     * The date and time naming this run and its files, such as `2026-09-08-1450-49`.
     */
    val id: String = ids.next()

    private val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

    private val prefix = "$FILE_PREFIX$id"

    /**
     * The copy of the game executable this run launches, whether the run starts it directly or through [Builder.executable].
     */
    val executablePath: Path = systemDirectory["$prefix${installation.platform.operatingSystem.executableSuffix}"]

    /**
     * The copy of the game configuration this run reads.
     */
    val gameIniPath: Path = systemDirectory["$prefix.ini"]

    /**
     * The copy of the user configuration this run reads.
     */
    val userIniPath: Path = systemDirectory["$prefix.user.ini"]

    /**
     * The exec script this run plays.
     */
    val execPath: Path = systemDirectory["$prefix.exec.txt"]

    /**
     * The log this run writes.
     */
    val logPath: Path = systemDirectory["$prefix.log"]

    private val files = Files()

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
     * Writes the files of this run into the installation, starts the process and returns this instance. A write that
     * fails deletes the files already written.
     *
     * @throws IllegalStateException if the instance is already started.
     */
    fun start(): UtInstance =
        apply {
            check(process == null) { "Instance $name is already started" }
            runCatching { files.write() }
                .onFailure { failure -> deleteFiles(failure) }
                .getOrThrow()
            process = executable.start()
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
     * and killing it once the timeout expires. The files of the run are deleted before this returns or throws. Every
     * call after the first returns or throws what the first one did.
     *
     * @throws IllegalStateException if the instance is not started.
     * @throws IOException if the files of the run cannot be deleted.
     */
    fun await(timeout: Duration = Duration.INFINITE): UtInstanceResult =
        synchronized(outcomeLock) { outcome ?: captureOutcome(timeout) }.getOrThrow()

    private fun captureOutcome(timeout: Duration): Result<UtInstanceResult> = runCatching { captureResult(timeout) }.also { outcome = it }

    private fun captureResult(timeout: Duration): UtInstanceResult {
        val process = startedProcess()
        val result = runCatching { process.use { files.read(exitCode(it, timeout)) } }
        deleteFiles(result.exceptionOrNull())
        return result.getOrThrow()
    }

    /**
     * Deletes the files of this run, adding a failure to do so to [failure] as suppressed, or throwing it when there
     * is no [failure].
     */
    private fun deleteFiles(failure: Throwable?) {
        try {
            files.delete()
        } catch (cause: IOException) {
            failure?.addSuppressed(cause) ?: throw cause
        }
    }

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
     * @property name The name the run is logged under.
     */
    class Builder(
        private val installation: UtInstallation,
        private val name: String,
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
         * An unstarted instance of the run this builder describes.
         *
         * @throws IllegalArgumentException if [arguments] are set without an [executable].
         */
        fun build(): UtInstance {
            require(arguments.isEmpty() || executable != null) {
                "Instance $name takes arguments ahead of the game executable but starts no program to receive them"
            }
            return UtInstance(name, installation, settings())
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

    /**
     * Writing, reading back and deleting the files at the paths of this run, each named rather than matched by prefix.
     */
    private inner class Files {
        fun write() {
            copyIni(installation.gameIniPath, gameIniPath, settings.gameIniEdits)
            copyIni(installation.userIniPath, userIniPath, settings.userIniEdits)
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

        fun read(exitCode: Int): UtInstanceResult =
            UtInstanceResult(
                exitCode = exitCode,
                log = logPath.readLinesOrEmpty(UtPlatform.CHARSET),
                gameIni = gameIniPath.readTextOrEmpty(UtPlatform.CHARSET),
                userIni = userIniPath.readTextOrEmpty(UtPlatform.CHARSET),
                exec = execPath.readLinesOrEmpty(UtPlatform.CHARSET),
            )

        fun delete() {
            listOf(gameIniPath, userIniPath, execPath, logPath).forEach { it.deleteIfExists() }
            executablePath.deleteRecursivelyCatching(attempts = 10, delay = 100.milliseconds).getOrThrow()
        }
    }

    companion object {
        private const val FILE_PREFIX = "ut-"

        /**
         * The time a killed run is given to report its exit code.
         */
        private val KILL_TIMEOUT = 5.seconds

        private const val KILLED_EXIT_CODE = -1

        private val ids = TimestampId()

        private val log = logger<UtInstance>()
    }
}

/**
 * The exit code of a finished [UtInstance] and the contents of the files it wrote, read before they were deleted.
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
