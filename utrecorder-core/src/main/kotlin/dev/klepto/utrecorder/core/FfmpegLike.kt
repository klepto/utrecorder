package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The pieces of [Ffmpeg] the frame capture reaches, narrow so unit tests hand a stand-in.
 */
interface FfmpegLike {
    /**
     * Runs the capture with [arguments] to completion, returning whether it finalised with code `0`.
     */
    suspend fun capture(vararg arguments: String): Boolean

    /**
     * Returns how many seconds long [file] is.
     */
    suspend fun duration(file: Path): Double

    /**
     * Returns how many frames [file] holds.
     */
    suspend fun frames(file: Path): Int
}
