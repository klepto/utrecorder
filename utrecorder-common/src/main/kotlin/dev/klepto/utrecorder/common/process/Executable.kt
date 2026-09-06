package dev.klepto.utrecorder.common.process

import dev.klepto.utrecorder.common.process.ExecutableProcess.Companion.closeTimeout
import dev.klepto.utrecorder.common.process.ExecutableProcess.Companion.maxOutputLines
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Immutable executable command configuration.
 *
 * @author Augustinas R. <https://github.com/klepto>
 */
data class Executable(
    val executablePath: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path = Path.of("").toAbsolutePath(),
    val environment: Map<String, String> = emptyMap(),
) {
    /**
     * Creates a new executable with the given [arguments].
     */
    fun arguments(vararg arguments: String) = copy(arguments = this.arguments + arguments.toList())

    /**
     * Creates a new executable with the given [environment].
     */
    fun environment(vararg environment: Pair<String, String>) = copy(environment = this.environment + environment.toMap())

    /**
     * Creates a new executable with the given [environment] values prepended to the values inherited from the current
     * configuration.
     */
    fun prependEnvironment(vararg environment: Pair<String, String>) =
        copy(
            environment =
                this.environment +
                    environment.associate { (key, value) ->
                        val existing = this.environment[key] ?: System.getenv(key)
                        key to if (existing.isNullOrEmpty()) value else value + File.pathSeparator + existing
                    },
        )

    /**
     * Creates a new executable with the given [workingDirectory].
     */
    fun workingDirectory(workingDirectory: Path) = copy(workingDirectory = workingDirectory)

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
 * Represents a started executable process.
 *
 * @author Augustinas R. <https://github.com/klepto>
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
     * The process output, capped to the last [maxOutputLines] lines.
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
    private val daemon: Thread =
        Thread {
            try {
                process
                    .inputStream
                    .bufferedReader()
                    .forEachLine { line ->
                        synchronized(lock) {
                            notifyConsumer(consumer, line)
                        }
                    }
            } catch (cause: IOException) {
                if (!closing) throw cause
            }
        }.apply {
            isDaemon = true
            name = executable.toString()
            start()
        }

    /**
     * Appends the given [line] to the process [output].
     */
    private fun appendOutput(line: String) {
        if (output.size >= maxOutputLines) output.removeFirst()
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
    fun drain(consumer: (String) -> Unit): ExecutableProcess =
        apply {
            synchronized(lock) {
                output.forEach { line -> notifyConsumer(consumer, line) }

                val current = this.consumer
                this.consumer = { line ->
                    current(line)
                    consumer(line)
                }
            }
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
     * Attempts to close the process gracefully. If the process is still running after [closeTimeout], it will be
     * forcefully terminated. Ends the output reading thread along with the stream it reads.
     */
    override fun close() {
        closing = true
        process.destroy()
        process.waitFor(closeTimeout.toJavaDuration())
        if (isRunning) process.destroyForcibly()
    }

    companion object {
        /**
         * Timeout for [close] method.
         */
        private val closeTimeout = 5.seconds

        /**
         * Maximum number of lines to keep in the process [output] buffer.
         */
        private val maxOutputLines = 5000
    }
}

/**
 * Represents the result of an executed executable command. The [output] holds the trailing lines emitted by the
 * process, earlier lines are dropped once the process exceeds the retained line count.
 *
 * @author Augustinas R. <https://github.com/klepto>
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
    fun orThrow(): ExecutableResult =
        apply {
            check(isSuccess) {
                val lines = output.takeLast(10).joinToString("\n")
                "Process $executable exited with code $exitCode: $lines"
            }
        }
}
