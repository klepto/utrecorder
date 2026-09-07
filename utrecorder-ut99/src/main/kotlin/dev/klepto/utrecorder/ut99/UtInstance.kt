package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.log.logger
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableProcess
import dev.klepto.utrecorder.common.process.executable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds

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
     * Returns the unstarted instance this description builds.
     */
    fun build(): UtInstance = UtInstance(name, installation)
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
 */
class UtInstance(
    val name: String,
    private val installation: UtInstallation,
) {
    /**
     * The Unix timestamp in milliseconds naming this run. Every file the run adds to the installation carries it.
     */
    val id: Long = nextId()

    /**
     * The name every file of this run begins with.
     */
    private val prefix = "$FILE_PREFIX$id"

    /**
     * The directory the run adds its files to and starts in.
     */
    private val systemDirectory: Path = installation.systemDirectory.toAbsolutePath()

    /**
     * The copy of the game executable the run starts, so that a run never waits on the lock the game holds on its own
     * executable.
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
     * The command line the run starts, pointing the game at the files of this run.
     */
    private val executable: Executable =
        executable(executablePath) {
            workingDirectory = systemDirectory
            arguments += "-ini=${gameIniPath.absolutePathString()}"
            arguments += "-userini=${userIniPath.absolutePathString()}"
            arguments += "-log=${logPath.name}"
            arguments += installation.platform.homeArguments
            prependEnvironment(LIBRARY_PATH, "$systemDirectory")
        }

    /**
     * The process of the run, or `null` until the instance is started.
     */
    private var process: ExecutableProcess? = null

    /**
     * Copies the files of this run into the installation and starts the process reading them. Returns this instance,
     * so that a caller can chain [await]. Throws if the instance is already started: a retry is a new instance with a
     * new id.
     */
    fun start(): UtInstance =
        apply {
            check(process == null) { "Instance $name is already started" }
            copyFiles()
            process = executable.start()
        }

    /**
     * Copies the game executable and the configuration the run reads under the prefix of this run. A copy that fails
     * part of the way through takes the files it already wrote with it, so that a run that never started leaves the
     * installation as it found it.
     */
    private fun copyFiles() {
        runCatching { copyRunFiles() }
            .onFailure { deleteFiles() }
            .getOrThrow()
    }

    /**
     * Copies the game executable and the configuration the run reads, in the order a caller reads about them.
     */
    private fun copyRunFiles() {
        copyIni(installation.gameIniPath, gameIniPath)
        copyIni(installation.userIniPath, userIniPath)
        installation.executablePath.copyTo(executablePath)
        executablePath.toFile().setExecutable(true)
    }

    /**
     * Waits for the run to finish, and returns the code it exited with together with the contents of every file it
     * wrote. The files are deleted before this returns, whether the run exited cleanly or not. Throws if the instance
     * is not started.
     */
    fun await(): UtInstanceResult {
        val process = checkNotNull(process) { "Instance $name is not started" }
        return try {
            process.use { readResult(it.await().exitCode) }
        } finally {
            deleteFiles()
        }
    }

    /**
     * Returns the result of a run that exited with [exitCode], reading back every file the run wrote.
     */
    private fun readResult(exitCode: Int): UtInstanceResult =
        UtInstanceResult(
            exitCode = exitCode,
            log = readLinesOrEmpty(logPath),
            gameIni = readTextOrEmpty(gameIniPath),
            userIni = readTextOrEmpty(userIniPath),
            exec = readLinesOrEmpty(execPath),
        )

    /**
     * Deletes every file this run added to the installation, naming them one by one rather than sweeping the prefix,
     * so that a run can never remove a file of another one.
     */
    private fun deleteFiles() {
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
         * The name every file of a run begins with, followed by the id of the run.
         */
        private const val FILE_PREFIX = "ut-"

        /**
         * The environment variable the game resolves its shared libraries through.
         */
        private const val LIBRARY_PATH = "LD_LIBRARY_PATH"

        /**
         * The number of times the executable copy of a run is deleted before the copy is left behind.
         */
        private const val DELETE_ATTEMPTS = 10

        /**
         * The time between two attempts to delete the executable copy of a run.
         */
        private val DELETE_DELAY = 100.milliseconds

        /**
         * The id handed to the instance built last.
         */
        private val lastId = AtomicLong()

        /**
         * Logger for this class.
         */
        private val log = logger<UtInstance>()

        /**
         * Returns the id of the next instance: the current Unix time in milliseconds, advanced past the id handed out
         * last when the clock has not moved since, so that two instances built within one millisecond keep their
         * files apart.
         */
        private fun nextId(): Long = lastId.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis()) }

        /**
         * Copies the installation configuration at [source] to the [target] of a run.
         */
        private fun copyIni(
            source: Path,
            target: Path,
        ) {
            check(source.exists()) { "UT configuration not found at $source" }
            source.copyTo(target)
        }

        /**
         * Returns the lines of the file at [path], or no lines if the run wrote none.
         */
        private fun readLinesOrEmpty(path: Path): List<String> =
            when {
                path.exists() -> path.readLines(UtIni.CHARSET)
                else -> emptyList()
            }

        /**
         * Returns the contents of the file at [path], or an empty string if the run wrote none.
         */
        private fun readTextOrEmpty(path: Path): String =
            when {
                path.exists() -> path.readText(UtIni.CHARSET)
                else -> ""
            }
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
