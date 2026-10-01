package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.OperatingSystem
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
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A mutable description of an executable, built into the immutable value by [Executable.build].
 *
 * @property executablePath The path of the program to run.
 */
class ExecutableBuilder(
    var executablePath: Path,
) {
    /**
     * The directory the process starts in. If not set, the working directory of the current process.
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
    var closeTimeout: Duration = 5.seconds

    /**
     * The maximum number of output lines a started process keeps, oldest dropped first. Must not be negative.
     */
    var maxOutputLines: Int = 5000

    /**
     * Puts [value] in front of the variable [name], separated by the platform path separator. What follows it is the
     * value this builder already holds, or else the value the current process inherited.
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
     * that resolves libraries without such a variable is left alone. If [operatingSystem] is not provided, uses the
     * current operating system instead.
     */
    fun prependLibraryPath(
        path: Path,
        operatingSystem: OperatingSystem = OperatingSystem.current(),
    ) {
        operatingSystem.libraryPathVariable?.let { prependEnvironment(it, "$path") }
    }

    /**
     * Returns the immutable executable this builder describes.
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
    val closeTimeout: Duration,
    val maxOutputLines: Int,
) {
    /**
     * Returns a process started from this executable, with its error stream merged into its output stream. The
     * returned process reads none of its output until [execute] starts its pump.
     */
    suspend fun start(): ExecutableProcess {
        val builder =
            ProcessBuilder(listOf("$executablePath") + arguments)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
        builder.environment() += environment
        val process = withContext(Dispatchers.IO) { builder.start() }
        return ExecutableProcess(this, process)
    }

    override fun toString(): String = "$executablePath ${arguments.joinToString(" ")}"

    companion object {
        /**
         * Returns the executable of the program at [executablePath] that [configure] describes.
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
 * Closing destroys the process and every process it started, and destroys forcibly any of them still running after
 * [Executable.closeTimeout]. Closing also ends the output stream [pump] reads.
 *
 * @property executable The configuration the process was started from.
 * @property process The process this one reads and waits on.
 */
class ExecutableProcess(
    val executable: Executable,
    private val process: Process,
) : AutoCloseable {
    /**
     * `true` while the process is still running.
     */
    val isRunning: Boolean
        get() = process.isAlive

    /**
     * The standard input of the process. The process reads end of input once this stream is closed.
     */
    val standardInput: OutputStream
        get() = process.outputStream

    /**
     * The process output, followed by [EndOfStream] once the output stream closes. A late collector of [drain] gets
     * the last [Executable.maxOutputLines] lines and the marker replayed, oldest dropped first. A slow collector loses
     * the oldest lines instead of stalling [pump].
     */
    private val outputFlow =
        MutableSharedFlow<Any>(
            replay = executable.maxOutputLines + 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Completed by [pump] once the output stream closes. [await] waits on it before it waits for the process to exit.
     */
    private val streamClosed = CompletableDeferred<Unit>()

    /**
     * `true` once [close] has been called.
     */
    @Volatile private var closing = false

    /**
     * Reads the process output until the stream closes, emitting every line to [outputFlow], then [EndOfStream].
     * Nothing but the output pump of [Executable.execute] should call it. A read that fails after [close] has been
     * called is the closed stream itself and is discarded.
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
     * Returns a cold flow over the process output. It emits the lines already seen, then every line still to come,
     * and completes once [pump] emits [EndOfStream]. So `drain().toList()` returns the whole output, even from a
     * collector that suspends between lines or starts late. A collector that throws fails only itself.
     */
    fun drain(): Flow<String> =
        outputFlow
            .takeWhile { it !== EndOfStream }
            .map { it as String }

    /**
     * Returns the result of the process once it finishes. [timeout] covers both the wait for the output stream to
     * close and the wait for the process to exit. If [timeout] is not provided, waits without limit.
     *
     * @throws TimeoutException if the process does not finish within [timeout].
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

    override fun close() {
        closing = true
        val processes = listOf(process.toHandle()) + process.descendants().toList()
        processes.forEach { it.destroy() }
        val exits = processes.map { it.onExit() }
        CompletableFuture
            .allOf(*exits.toTypedArray())
            .completeOnTimeout(null, executable.closeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .join()
        processes
            .filter { it.isAlive }
            .forEach { it.destroyForcibly() }
    }

    private companion object {
        /**
         * The marker [pump] emits into [outputFlow] once the output stream closes, ending a [drain] collector.
         */
        object EndOfStream
    }
}

/**
 * Starts this executable, runs [block] against the started [ExecutableProcess] and returns what [block] returns. The
 * output pump runs for the duration of [block], so a collector of [ExecutableProcess.drain] sees output produced
 * while [block] runs.
 *
 * The process is closed when [block] finishes, before this function waits for the pump. The close runs even when the
 * caller is cancelled, and then blocks the cancelling thread for up to [Executable.closeTimeout].
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
 * Returns the result of this executable once it finishes. See [ExecutableProcess.await] for [timeout].
 */
suspend fun Executable.startAndAwait(timeout: Duration = Duration.INFINITE): ExecutableResult = execute { await(timeout) }

/**
 * The exit code and trailing output of a finished process.
 *
 * Returned by [ExecutableProcess.await] and [Executable.startAndAwait].
 *
 * @property executable The configuration the process was started from.
 * @property output The last lines the process emitted, at most [Executable.maxOutputLines] of them.
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
     * Returns this result.
     *
     * @throws IllegalStateException if the process exited with a code other than `0`.
     */
    fun orThrow(): ExecutableResult = apply { check(isSuccess) { failureMessage() } }

    /**
     * Returns the message of the exception [orThrow] throws, holding the last ten output lines.
     */
    private fun failureMessage(): String {
        val lines = output.takeLast(10).joinToString("\n")
        return "Process $executable exited with code $exitCode: $lines"
    }
}
