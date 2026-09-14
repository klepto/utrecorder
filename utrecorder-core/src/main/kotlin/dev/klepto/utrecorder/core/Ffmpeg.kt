package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The bundled ffmpeg and ffprobe binaries, run as processes with their own library directory.
 *
 * Use one for everything that shells out to ffmpeg: running a command line to completion, reading the duration of a
 * media file and counting the frames of a video.
 */
class Ffmpeg {
    /**
     * Runs ffmpeg with [arguments] and returns whether it exited with code `0`.
     */
    fun run(vararg arguments: String): Boolean = TODO()

    /**
     * Returns how many seconds long [file] is, `0.0` when ffprobe reports nothing.
     */
    fun duration(file: Path): Double = TODO()

    /**
     * Returns how many video frames [file] holds, `0` when ffprobe reports none.
     */
    fun frames(file: Path): Int = TODO()
}
