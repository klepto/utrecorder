package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The pieces of [Ffmpeg] the frame capture reaches, narrow so unit tests hand a stand-in.
 */
interface FfmpegLike {
    /**
     * Runs the capture with [arguments] to completion, returning whether it finalised with code `0`.
     */
    fun capture(vararg arguments: String): Boolean

    /**
     * Returns how many frames [file] holds.
     */
    fun frames(file: Path): Int
}
