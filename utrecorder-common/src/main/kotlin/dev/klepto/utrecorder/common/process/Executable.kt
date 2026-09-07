package dev.klepto.utrecorder.common.process

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.process.ExecutableProcess.Companion.CLOSE_TIMEOUT
import dev.klepto.utrecorder.common.process.ExecutableProcess.Companion.MAX_OUTPUT_LINES
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Returns the executable of the program at [executablePath], described by [configure]. Environment values already set
 * for the current process are read from [properties].
 */
fun executable(
    executablePath: Path,
    properties: PropertySource = PropertySource.DEFAULT,
    configure: ExecutableBuilder.() -> Unit = {},
): Executable = ExecutableBuilder(executablePath, properties).apply(configure).build()

/**
 * A mutable description of an executable, built into the immutable value by [executable].
 *
 * @property executablePath The path of the program to run.
 * @property properties The source the environment of the current process is read from.
 */
class ExecutableBuilder(
    var executablePath: Path,
    private val properties: PropertySource = PropertySource.DEFAULT,
) {
    /**
     * The directory the process starts in.
     */
    var workingDirectory: Path = Path.of("").toAbsolutePath()

    /**
     * The arguments passed to the program, in the order the program receives them.
     */
    val arguments = mutableListOf<String>()

    /**
     * The environment variables the process starts with, layered over the ones it inherits.
     */
    val environment = mutableMapOf<String, String>()

    /**
     * Puts [value] in front of the variable [name], separated by the platform path separator. What follows it is the
     * value this configuration already holds, or the one the current process inherited.
     */
    fun prependEnvironment(
        name: String,
        value: String,
    ) {
        val existing = environment[name] ?: properties.environment(name)
        environment[name] =
            when {
                existing.isNullOrEmpty() -> value
                else -> value + File.pathSeparator + existing
            }
    }

    /**
     * Returns the immutable executable this configuration describes.
     */
    fun build(): Executable = Executable(executablePath, arguments.toList(), workingDirectory, environment.toMap())
}

/**
 * An immutable command line and the process environment it starts under, reusable across runs.
 *
 * Use [executable] to build one.
 *
 * @property executablePath The path of the program to run.
 * @property arguments The arguments passed to the program, in the order the program receives them.
 * @property workingDirectory The directory the process starts in.
 * @property environment The environment variables the process starts with, layered over the ones it inherits.
 */
data class Executable(
    val executablePath: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path = Path.of("").toAbsolutePath(),
    val environment: Map<String, String> = emptyMap(),
) {
    /**
     * Starts the process using this executable configuration. The error stream is merged into the output stream, which
     * the returned [ExecutableProcess] starts reading immediately.
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
     * Starts the process and waits for it to finish. See [ExecutableProcess.await].
     */
    fun startAndAwait(timeout: Duration = Duration.INFINITE): ExecutableResult = start().use { it.await(timeout) }

    /**
     * Returns a string representation of this executable configuration.
     */
    override fun toString(): String = "$executablePath ${arguments.joinToString(" ")}"
}

/**
 * A running process started from an [Executable], holding its trailing output lines and the thread that reads them.
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
     * Guards [output] and [consumer]. Held by the daemon thread while it delivers a line, and by [drain] while it
     * replays the buffer and registers a consumer.
     */
    private val lock = Any()

    /**
     * The process output, capped to the last [MAX_OUTPUT_LINES] lines.
     */
    private val output = ArrayDeque<String>()

    /**
     * The head of the output consumer chain. Starts as [appendOutput] and grows with every consumer registered through
     * [drain], which are invoked in registration order.
     */
    private var consumer: (String) -> Unit = ::appendOutput

    /**
     * The failure thrown by an output consumer, rethrown by [await] on the calling thread.
     */
    @Volatile private var consumerFailure: Throwable? = null

    /**
     * Marks whether [close] has been called, so that the reader failing on the stream it closed is not mistaken for a
     * read error.
     */
    @Volatile private var closing = false

    /**
     * The thread that reads the process output until the stream closes, or until a [consumer] throws.
     */
    private val daemon: Thread = startOutputReader()

    /**
     * Starts and returns the thread backing [daemon].
     */
    private fun startOutputReader(): Thread =
        Thread(::readOutput).apply {
            isDaemon = true
            name = executable.toString()
            start()
        }

    /**
     * Reads the process output until the stream closes, or until a [consumer] throws. A read that fails after [close]
     * has been called is the closed stream itself and is discarded.
     */
    private fun readOutput() {
        try {
            process.inputStream.bufferedReader().forEachLine(::consumeLine)
        } catch (cause: IOException) {
            if (!closing) throw cause
        }
    }

    /**
     * Delivers the given [line] to the head of the consumer chain.
     */
    private fun consumeLine(line: String) = synchronized(lock) { notifyConsumer(consumer, line) }

    /**
     * Appends the given [line] to the process [output].
     */
    private fun appendOutput(line: String) {
        if (output.size >= MAX_OUTPUT_LINES) output.removeFirst()
        output += line
    }

    /**
     * Notifies the given [consumer] with the given [line], recording the failure in [consumerFailure] before rethrowing
     * it to the caller.
     */
    private fun notifyConsumer(
        consumer: (String) -> Unit,
        line: String,
    ) {
        runCatching { consumer(line) }
            .onFailure { consumerFailure = it }
            .getOrThrow()
    }

    /**
     * Drains the process output to the given [consumer]. Buffered lines are replayed on the calling thread before the
     * consumer is registered, every line after that is delivered on the process daemon thread. A consumer that throws
     * fails the process and every other consumer along with it, and is left unregistered if it throws during the
     * replay.
     */
    fun drain(consumer: (String) -> Unit): ExecutableProcess = apply { synchronized(lock) { registerConsumer(consumer) } }

    /**
     * Replays the buffered [output] to the given [consumer] and appends it to the consumer chain.
     */
    private fun registerConsumer(consumer: (String) -> Unit) {
        output.forEach { line -> notifyConsumer(consumer, line) }
        this.consumer = chainConsumers(this.consumer, consumer)
    }

    /**
     * Returns the consumer that invokes [first] and then [second].
     */
    private fun chainConsumers(
        first: (String) -> Unit,
        second: (String) -> Unit,
    ): (String) -> Unit =
        { line ->
            first(line)
            second(line)
        }

    /**
     * Waits for the process to finish and returns the result. Throws the cause if an output consumer failed, or a
     * [TimeoutException] if the process doesn't finish within the given [timeout]. The process is closed on every
     * failure. Repeated calls return the same result or rethrow the same consumer failure.
     */
    fun await(timeout: Duration = Duration.INFINITE): ExecutableResult =
        runCatching { awaitResult(timeout) }
            .onFailure { close() }
            .getOrThrow()

    /**
     * Backs [await] with the waiting itself, leaving the process untouched on failure so that [await] owns the closing.
     */
    private fun awaitResult(timeout: Duration): ExecutableResult {
        val timer = TimeSource.Monotonic.markNow()
        val drained = daemon.join(timeout.toJavaDuration())
        consumerFailure?.let { cause ->
            throw IllegalStateException("Output consumer failed: $executable", cause)
        }

        if (!drained) {
            throw TimeoutException("Timed out waiting for output stream: $executable")
        }

        val remaining = timeout - timer.elapsedNow()
        if (!process.waitFor(remaining.toJavaDuration())) {
            throw TimeoutException("Timed out waiting for process: $executable")
        }

        return ExecutableResult(executable, output.toList(), process.exitValue())
    }

    /**
     * Attempts to close the process gracefully. If the process is still running after [CLOSE_TIMEOUT], it will be
     * forcefully terminated. Ends the output reading thread along with the stream it reads.
     */
    override fun close() {
        closing = true
        process.destroy()
        process.waitFor(CLOSE_TIMEOUT.toJavaDuration())
        if (isRunning) process.destroyForcibly()
    }

    companion object {
        /**
         * The time a process is given to exit on its own before it is terminated forcibly.
         */
        private val CLOSE_TIMEOUT = 5.seconds

        /**
         * Maximum number of lines to keep in the process [output] buffer.
         */
        private const val MAX_OUTPUT_LINES = 5000
    }
}

/**
 * The exit code and output of a process that has finished. The [output] holds the trailing lines emitted by the
 * process, earlier lines are dropped once the process exceeds the retained line count.
 *
 * @property executable The configuration the process was started from.
 * @property output The trailing lines the process emitted.
 * @property exitCode The code the process exited with.
 */
data class ExecutableResult(
    val executable: Executable,
    val output: List<String>,
    val exitCode: Int,
) {
    /**
     * Marks whether the process exited with a success code.
     */
    val isSuccess: Boolean
        get() = exitCode == 0

    /**
     * Marks whether the process exited with a failure code.
     */
    val isFailure: Boolean
        get() = !isSuccess

    /**
     * Throws an exception if the process exited with a failure code.
     */
    fun orThrow(): ExecutableResult = apply { check(isSuccess) { failureMessage() } }

    /**
     * Returns the message of the exception [orThrow] throws.
     */
    private fun failureMessage(): String {
        val lines = output.takeLast(10).joinToString("\n")
        return "Process $executable exited with code $exitCode: $lines"
    }
}
