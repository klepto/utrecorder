package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.os.OperatingSystem
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableProcess
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.ffmpeg.ffprobe
import org.bytedeco.javacpp.Loader
import java.nio.file.Path

/**
 * The bundled ffmpeg and ffprobe binaries, run as processes with their own library directory.
 *
 * Use one for everything that shells out to ffmpeg: running a command line to completion, reading the duration of a
 * media file and counting the frames of a video.
 */
class Ffmpeg(
    private val properties: PropertySource = PropertySource.DEFAULT,
) : FfmpegLike {
    /**
     * The path of the bundled ffmpeg executable, resolved once at first use alongside the probe's.
     */
    private val executablePath: Path by lazy { resolve(ffmpeg::class.java) }

    /**
     * The path of the bundled ffprobe executable, resolved once at first use alongside the main one's.
     */
    private val probePath: Path by lazy { resolve(ffprobe::class.java) }

    /**
     * Runs ffmpeg with [arguments] and returns whether it exited with code `0`.
     */
    override fun capture(vararg arguments: String): Boolean = run(*arguments)

    /**
     * Runs ffmpeg with [arguments] and returns whether it exited with code `0`.
     */
    fun run(vararg arguments: String): Boolean =
        runCommandProcess(executablePath, arguments.toList())
            .await()
            .isSuccess

    /**
     * Returns how many seconds long [file] is, `0.0` when ffprobe reports nothing.
     */
    override fun duration(file: Path): Double =
        readProbe(
            listOf(
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                file.toAbsolutePath().toString(),
            ),
        ).firstNotNullOfOrNull { it.trim().toDoubleOrNull() } ?: 0.0

    /**
     * Returns how many video frames [file] holds, `0` when ffprobe reports none.
     */
    override fun frames(file: Path): Int =
        readProbe(
            listOf(
                "-select_streams",
                "v:0",
                "-count_packets",
                "-show_entries",
                "stream=nb_read_packets",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                file.toAbsolutePath().toString(),
            ),
        ).firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: 0

    /**
     * The executable command running the program at [path] with [arguments], its directory prepended to the library
     * path.
     */
    fun builder(
        path: Path,
        libraryDirectory: Path,
        arguments: List<String> = emptyList(),
        operatingSystem: OperatingSystem = OperatingSystem.current(properties),
    ): Executable =
        Executable.build(path, properties) {
            this.arguments += arguments
            prependLibraryPath(libraryDirectory, operatingSystem)
        }

    /**
     * The lines the bundled ffprobe prints for the given probe [arguments], running from the directory its binary
     * holds.
     */
    private fun readProbe(arguments: List<String>): List<String> =
        builder(probePath, probePath.parent, arguments)
            .copy(workingDirectory = probePath.parent)
            .startAndAwait()
            .output

    /**
     * The process running the program at [path] with the given [arguments], working from the directory its binary
     * holds.
     */
    private fun runCommandProcess(
        path: Path,
        arguments: List<String>,
    ): ExecutableProcess =
        builder(path, path.parent, arguments)
            .copy(workingDirectory = path.parent)
            .start()

    /**
     * The classpath location the loader extracts the bundled program [type] into.
     */
    private fun resolve(type: Class<*>): Path = Path.of(Loader.load(type))
}
