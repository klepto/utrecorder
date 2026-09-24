package dev.klepto.utrecorder.core.media

import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.ExecutableResult
import dev.klepto.utrecorder.common.io.startAndAwait
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.ffmpeg.ffprobe
import org.bytedeco.javacpp.Loader
import java.nio.file.Path

/**
 * The bundled ffmpeg and ffprobe programs, each run as a process from the directory that holds its libraries.
 *
 * Runs an ffmpeg command line to completion, reads the duration of a media file and counts the frames of a video.
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

    override suspend fun run(vararg arguments: String): Boolean = execute(ffmpegPath, arguments.toList()).isSuccess

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
     * [libraryDirectory] put in front of the library path. If [operatingSystem] is not provided, uses the current
     * operating system instead.
     */
    fun executable(
        path: Path,
        libraryDirectory: Path,
        arguments: List<String> = emptyList(),
        operatingSystem: OperatingSystem = OperatingSystem.current(),
    ): Executable =
        Executable.build(path) {
            this.arguments += arguments
            workingDirectory = libraryDirectory
            prependLibraryPath(libraryDirectory, operatingSystem)
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

    companion object {
        /**
         * Returns the path the loader extracts the bundled program [type] to.
         */
        private fun resolve(type: Class<*>): Path = Path.of(Loader.load(type))
    }
}
