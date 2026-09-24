package dev.klepto.utrecorder.core.media

import java.nio.file.Path

/**
 * A media program that runs a command line and probes media files.
 */
interface MediaTool {
    /**
     * Runs the program with [arguments] to completion and returns whether it exited with code `0`.
     */
    suspend fun run(vararg arguments: String): Boolean

    /**
     * Returns the length of [file] in seconds.
     */
    suspend fun duration(file: Path): Double

    /**
     * Returns the number of frames in [file].
     */
    suspend fun frames(file: Path): Int
}
