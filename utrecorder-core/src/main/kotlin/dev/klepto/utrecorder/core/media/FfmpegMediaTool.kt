package dev.klepto.utrecorder.core.media

import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.LINUX
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.ExecutableProcess
import dev.klepto.utrecorder.common.io.ExecutableResult
import dev.klepto.utrecorder.common.io.execute
import dev.klepto.utrecorder.common.io.startAndAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.ffmpeg.ffprobe
import org.bytedeco.javacpp.Loader
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The bundled ffmpeg and ffprobe programs, each run as a process from the directory that holds its libraries.
 *
 * Runs an ffmpeg command line to completion, reads the duration of a media file and counts the frames of a video. An
 * ffmpeg run that reports progress writes it to standard out through `-progress pipe:1`.
 */
class FfmpegMediaTool : MediaTool {
    /**
     * The path of the bundled ffmpeg program, extracted on first use.
     */
    private val ffmpegPath: Path by lazy { resolve(ffmpeg::class.java) }

    /**
     * The path of the bundled ffprobe program, extracted on first use.
     */
    private val ffprobePath: Path by lazy { resolve(ffprobe::class.java) }

    override suspend fun run(
        vararg arguments: String,
        input: (suspend (OutputStream) -> Unit)?,
        onProgress: ((MediaProgress) -> Unit)?,
    ) {
        val result =
            when (onProgress) {
                null -> execute(ffmpegPath, arguments.toList(), input) {}
                else -> execute(ffmpegPath, PROGRESS_ARGUMENTS + arguments, input) { report(drain(), onProgress) }
            }
        if (!result.isSuccess) throw failure(result)
    }

    /**
     * Returns the length of [file] in seconds. If ffprobe reports no duration, returns `0.0` instead.
     */
    override suspend fun duration(file: Path): Double =
        probe(file, "-show_entries", "format=duration")
            .firstNotNullOfOrNull { it.trim().toDoubleOrNull() } ?: 0.0

    /**
     * Returns the number of frames in the first video stream of [file]. If ffprobe reports no count, returns `0`
     * instead.
     */
    override suspend fun frames(file: Path): Int =
        probe(file, "-select_streams", "v:0", "-count_packets", "-show_entries", "stream=nb_read_packets")
            .firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: 0

    /**
     * Returns the executable that runs the program at [path] with [arguments] from [libraryDirectory], with
     * [libraryDirectory] put in front of the library path. On Linux, the program starts through `nice -n 10`, at a
     * lower CPU priority. If [operatingSystem] is not provided, uses the current operating system instead.
     */
    fun executable(
        path: Path,
        libraryDirectory: Path,
        arguments: List<String> = emptyList(),
        operatingSystem: OperatingSystem = OperatingSystem.current(),
    ): Executable {
        val (program, leadingArguments) =
            when (operatingSystem) {
                LINUX -> Path.of("nice") to listOf("-n", "10", "$path")
                WINDOWS -> path to emptyList<String>()
            }
        return Executable.build(program) {
            this.arguments += leadingArguments + arguments
            workingDirectory = libraryDirectory
            prependLibraryPath(libraryDirectory, operatingSystem)
        }
    }

    /**
     * Returns the output lines of ffprobe run on [file] with [arguments], printing each value without its key.
     */
    private suspend fun probe(
        file: Path,
        vararg arguments: String,
    ): List<String> {
        val format = listOf("-of", "default=noprint_wrappers=1:nokey=1", "${file.toAbsolutePath()}")
        return execute(ffprobePath, arguments.toList() + format).output
    }

    /**
     * Returns the result of running the program at [path] with [arguments] to completion, from the directory that
     * holds it.
     */
    private suspend fun execute(
        path: Path,
        arguments: List<String>,
    ): ExecutableResult = executable(path, path.parent, arguments).startAndAwait()

    /**
     * Returns the result of running the program at [path] with [arguments] to completion, from the directory that
     * holds it. [input] writes the standard input of the program, and [output] reads its output while it runs. If
     * [input] is `null`, the standard input is left open and unwritten.
     */
    private suspend fun execute(
        path: Path,
        arguments: List<String>,
        input: (suspend (OutputStream) -> Unit)?,
        output: suspend ExecutableProcess.() -> Unit,
    ): ExecutableResult =
        executable(path, path.parent, arguments).execute {
            coroutineScope {
                launch { output() }
                input?.let { launch { write(it) } }
                await()
            }
        }

    /**
     * Passes the standard input of this process to [input], and closes it once [input] returns.
     *
     * @throws IllegalStateException if [input] fails because the process exited with a code other than `0`, with the
     * failure of [input] as its cause.
     */
    private suspend fun ExecutableProcess.write(input: suspend (OutputStream) -> Unit) {
        try {
            withContext(Dispatchers.IO) { standardInput.use { input(it) } }
        } catch (cause: IOException) {
            val result = await()
            if (result.isSuccess) throw cause
            throw failure(result, cause)
        }
    }

    companion object {
        /**
         * The arguments that make ffmpeg write progress blocks to standard out instead of its statistics line.
         */
        private val PROGRESS_ARGUMENTS = listOf("-progress", "pipe:1", "-nostats")

        /**
         * A `key=value` line of a progress block.
         */
        private val PROGRESS_LINE = Regex("[a-z0-9_]+=\\S*")

        /**
         * Returns the failure of the run that ended in [result], with [cause] as its cause. The message holds the last
         * ten output lines of the run that are not part of a progress block.
         */
        private fun failure(
            result: ExecutableResult,
            cause: Throwable? = null,
        ): IllegalStateException {
            val lines =
                result.output
                    .filterNot { PROGRESS_LINE.matches(it) }
                    .takeLast(10)
                    .joinToString("\n")
            return IllegalStateException("Process ${result.executable} exited with code ${result.exitCode}:\n$lines", cause)
        }

        /**
         * Returns the path the loader extracts the bundled program [type] to.
         */
        private fun resolve(type: Class<*>): Path = Path.of(Loader.load(type))

        /**
         * Passes every progress block in [output] to [onProgress]. A block is a run of `key=value` lines ending with
         * a `progress` line.
         */
        private suspend fun report(
            output: Flow<String>,
            onProgress: (MediaProgress) -> Unit,
        ) {
            val values = mutableMapOf<String, String>()
            output.collect { line ->
                values[line.substringBefore('=')] = line.substringAfter('=')
                if (line.startsWith("progress=")) onProgress(progress(values))
            }
        }

        /**
         * Returns the progress that the `frame` and `out_time_us` values of a progress block hold. A value that is
         * missing or not a number counts as zero.
         */
        private fun progress(values: Map<String, String>): MediaProgress =
            MediaProgress(
                frames = values["frame"]?.trim()?.toLongOrNull() ?: 0,
                time = values["out_time_us"]?.trim()?.toLongOrNull()?.microseconds ?: Duration.ZERO,
            )
    }
}
