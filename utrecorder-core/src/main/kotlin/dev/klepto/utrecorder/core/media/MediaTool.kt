package dev.klepto.utrecorder.core.media

import java.io.OutputStream
import java.nio.file.Path

/**
 * A media program that runs a command line and probes media files.
 */
interface MediaTool {
    /**
     * Runs the program with [arguments] to completion and returns whether it exited with code `0`.
     *
     * [input] writes the standard input of the program, which is closed once [input] returns. If [input] is not
     * provided, the program reads no input.
     *
     * [onProgress] receives the progress the program reports while it runs. It is called from any thread. If
     * [onProgress] is not provided, the program reports no progress.
     */
    suspend fun run(
        vararg arguments: String,
        input: (suspend (OutputStream) -> Unit)? = null,
        onProgress: ((MediaProgress) -> Unit)? = null,
    ): Boolean

    /**
     * Returns the length of [file] in seconds.
     */
    suspend fun duration(file: Path): Double

    /**
     * Returns the number of frames in [file].
     */
    suspend fun frames(file: Path): Int
}
