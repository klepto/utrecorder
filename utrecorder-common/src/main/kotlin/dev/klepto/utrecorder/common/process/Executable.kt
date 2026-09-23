package dev.klepto.utrecorder.common.process

import dev.klepto.utrecorder.common.io.workingDirectory
import dev.klepto.utrecorder.common.os.OperatingSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The default of [ExecutableBuilder.closeTimeout] and [Executable.closeTimeout].
 */
private val DEFAULT_CLOSE_TIMEOUT = 5.seconds

/**
 * The default of [ExecutableBuilder.maxOutputLines] and [Executable.maxOutputLines].
 */
private const val DEFAULT_MAX_OUTPUT_LINES = 5000

/**
 * The number of output lines [ExecutableProcess]'s output flow buffers beyond its replay cache, so a slow collector
 * of [ExecutableProcess.drain] loses lines to [BufferOverflow.DROP_OLDEST] instead of stalling the pump.
 */
private const val EXTRA_OUTPUT_BUFFER = 64

/**
 * The private marker [ExecutableProcess.pump] emits into the output flow once the process's output stream closes,
 * ending a [ExecutableProcess.drain] collector deterministically instead of racing it with an external cancellation.
 */
private object EndOfStream

/**
 * A mutable description of an executable, built into the immutable value by [Executable.build].
 *
 * @property executablePath The path of the program to run.
 * @property reader The source the working directory, the operating system and the inherited environment are read
 * from.
 */
class ExecutableBuilder(
    var executablePath: Path,
) {
    /**
     * The directory the process starts in, the working directory of [reader] unless set.
     */
    var workingDirectory: Path = workingDirectory()

    /**
     * The arguments passed to the program, in the order the program receives them.
     */
    val arguments = mutableListOf<String>()

    /**
     * The environment variables the process starts with, layered over the ones it inherits.
     */
    val environment = mutableMapOf<String, String>()

    /**
     * The time a started process is given to exit on its own before it is terminated forcibly.
     */
    var closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT

    /**
     * The maximum number of output lines a started process keeps, oldest dropped first. Must not be negative: a
     * negative value throws [IllegalArgumentException] from the `MutableSharedFlow` backing [ExecutableProcess.drain].
     */
    var maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES

    /**
     * Puts [value] in front of the variable [name], separated by the platform path separator. What follows it is the
     * value this builder already holds, or the one [reader] holds.
     */
    fun prependEnvironment(
        name: String,
        value: String,
    ) {
        val existing = environment[name] ?: System.getenv(name)
        environment[name] =
            when {
                existing.isNullOrEmpty() -> value
                else -> value + File.pathSeparator + existing
            }
    }

    /**
     * Puts [path] in front of the variable [operatingSystem] resolves shared libraries through. An operating system
     * that resolves libraries without such a variable is left alone.
     */
    fun prependLibraryPath(
        path: Path,
        operatingSystem: OperatingSystem = OperatingSystem.current(),
    ) {
        operatingSystem.libraryPathVariable?.let { prependEnvironment(it, "$path") }
    }

    /**
     * The immutable executable this builder describes.
     */
    fun build(): Executable =
        Executable(executablePath, arguments.toList(), workingDirectory, environment.toMap(), closeTimeout, maxOutputLines)
}

/**
 * An immutable command line and the process environment it starts under, reusable across runs.
 *
 * Use [Executable.build] to build one.
 *
 * @property executablePath The path of the program to run.
 * @property arguments The arguments passed to the program, in the order the program receives them.
 * @property workingDirectory The directory the process starts in.
 * @property environment The environment variables the process starts with, layered over the ones it inherits.
 * @property closeTimeout The time a started process is given to exit on its own before [ExecutableProcess.close]
 * terminates it forcibly.
 * @property maxOutputLines The maximum number of output lines a started process keeps, oldest dropped first.
 */
data class Executable(
    val executablePath: Path,
    val arguments: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
    val maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
) {
    /**
     * Starts the process using this executable configuration. The error stream is merged into the output stream. The
     * returned [ExecutableProcess] reads none of it until [execute] starts its pump.
     */
    fun start(): ExecutableProcess {
        val builder =
            ProcessBuilder(listOf(executablePath.toString()) + arguments)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
        builder.environment().putAll(environment)
        return ExecutableProcess(this, builder.start())
    }

    /**
     * The command line of this executable, its path followed by its arguments.
     */
    override fun toString(): String = "$executablePath ${arguments.joinToString(" ")}"

    companion object {
        /**
         * The executable of the program at [executablePath] that [configure] describes, reading the working directory,
         * the operating system and the inherited environment from [reader].
         */
        fun build(
            executablePath: Path,
            configure: ExecutableBuilder.() -> Unit = {},
        ): Executable = ExecutableBuilder(executablePath).apply(configure).build()
    }
}

/**
 * A running process started from an [Executable], holding the output it emitted and the process it wraps.
 *
 * Use [Executable.start] to start one, or [Executable.execute] to run a block against it with its output pump
 * running. Nothing reads the process output until [pump] runs, so [drain] and [await] on a process [start]ed
 * directly, without going through [Executable.execute], hang forever: nothing ever completes [streamClosed].
 *
 * @property executable The configuration the process was started from.
 * @property process The process this one reads and waits on.
 */
class ExecutableProcess(
    val executable: Executable,
    private val process: Process,
) : AutoCloseable {
    /**
     * Returns `true` if the process is still running.
     */
    val isRunning: Boolean
        get() = process.isAlive

    /**
     * The process output followed, once the output stream closes, by [EndOfStream]: replayed to a late collector of
     * [drain] and capped to the last [Executable.maxOutputLines] lines plus that marker, oldest dropped first. The
     * marker ends a [drain] collector deterministically instead of racing it against an external cancellation, and
     * [EXTRA_OUTPUT_BUFFER] lets a slow collector fall behind without stalling [pump]'s `tryEmit`.
     */
    private val outputFlow =
        MutableSharedFlow<Any>(
            replay = executable.maxOutputLines + 1,
            extraBufferCapacity = EXTRA_OUTPUT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Completed by [pump] once the output stream closes, the signal [await] waits on before waiting for the process
     * to exit.
     */
    private val streamClosed = CompletableDeferred<Unit>()

    /**
     * Marks whether [close] has been called.
     */
    @Volatile private var closing = false

    /**
     * Reads the process output until the stream closes, emitting every line to [outputFlow], then [EndOfStream].
     * [Executable.execute] starts this as a child of its `coroutineScope`; nothing else should call it. A read that
     * fails after [close] has been called is the closed stream itself and is discarded.
     */
    suspend fun pump() {
        try {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().forEachLine { line -> outputFlow.tryEmit(line) }
            }
        } catch (cause: IOException) {
            if (!closing) throw cause
        } finally {
            outputFlow.tryEmit(EndOfStream)
            streamClosed.complete(Unit)
        }
    }

    /**
     * A cold flow over the process output: it emits the lines already seen, then every line still to come, and
     * completes once [pump] emits [EndOfStream], so `drain().toList()` returns the whole output even from a
     * collector that suspends between lines. A collector that starts late still sees the earlier lines, because they
     * are [outputFlow]'s replay cache. A collector that throws fails only itself.
     */
    fun drain(): Flow<String> = outputFlow.takeWhile { it !== EndOfStream }.map { it as String }

    /**
     * Waits for the process to finish within [timeout] and returns the result, covering both the wait for the output
     * stream to close and the wait for the process to exit.
     *
     * @throws TimeoutException if the process doesn't finish within [timeout].
     */
    suspend fun await(timeout: Duration = Duration.INFINITE): ExecutableResult =
        try {
            withTimeout(timeout) {
                streamClosed.await()
                runInterruptible(Dispatchers.IO) { process.waitFor() }
                ExecutableResult(executable, outputFlow.replayCache.filterIsInstance<String>(), process.exitValue())
            }
        } catch (timedOut: TimeoutCancellationException) {
            throw TimeoutException("Timed out waiting for process: $executable").apply { initCause(timedOut) }
        }

    /**
     * Attempts to close the process gracefully. If the process is still running after [Executable.closeTimeout], it
     * is terminated forcibly. Ends the output stream [pump] reads.
     */
    override fun close() {
        closing = true
        process.destroy()
        process.waitFor(executable.closeTimeout.toJavaDuration())
        if (isRunning) process.destroyForcibly()
    }
}

/**
 * Starts this executable, runs [block] against the started [ExecutableProcess] and returns what it returns. The
 * output pump runs as a child of a `coroutineScope` opened for the duration of [block], so a collector of
 * [ExecutableProcess.drain] sees output produced while [block] runs.
 *
 * The process is closed in a `finally` around [block], not around the whole `coroutineScope`: closing destroys the
 * process, which is what unblocks the pump's read of its output, so closing has to happen before this function
 * would otherwise wait on the pump to finish reading a process [block] left running. The close runs under
 * [NonCancellable] so its destroy-then-force grace wait still runs when the caller was cancelled — at the cost of
 * blocking the thread that cancelled it for that wait.
 */
suspend fun <T> Executable.execute(block: suspend ExecutableProcess.() -> T): T =
    coroutineScope {
        val process = start()
        launch { process.pump() }
        try {
            process.block()
        } finally {
            withContext(NonCancellable) { process.close() }
        }
    }

/**
 * Starts this executable and waits for it to finish. See [ExecutableProcess.await].
 */
suspend fun Executable.startAndAwait(timeout: Duration = Duration.INFINITE): ExecutableResult = execute { await(timeout) }

/**
 * The exit code and trailing output of a finished process.
 *
 * Returned by [ExecutableProcess.await] and [Executable.startAndAwait].
 *
 * @property executable The configuration the process was started from.
 * @property output The trailing lines the process emitted, earlier lines dropped past the retained line count.
 * @property exitCode The code the process exited with.
 */
data class ExecutableResult(
    val executable: Executable,
    val output: List<String>,
    val exitCode: Int,
) {
    /**
     * `true` if the process exited with code `0`.
     */
    val isSuccess: Boolean
        get() = exitCode == 0

    /**
     * This result, or an [IllegalStateException] thrown if the process exited with a failure code.
     */
    fun orThrow(): ExecutableResult = apply { check(isSuccess) { failureMessage() } }

    /**
     * The message of the exception [orThrow] throws, holding the last ten output lines.
     */
    private fun failureMessage(): String {
        val lines = output.takeLast(10).joinToString("\n")
        return "Process $executable exited with code $exitCode: $lines"
    }
}
