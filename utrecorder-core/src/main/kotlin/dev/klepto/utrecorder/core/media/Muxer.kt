package dev.klepto.utrecorder.core.media

import dev.klepto.utrecorder.common.ProgressListener
import java.nio.file.Path

/**
 * The mux of a recording: a finished frame capture and a part of an audio file merged into one mp4.
 *
 * [FfmpegMuxer] runs it through ffmpeg.
 */
interface Muxer {
    /**
     * Returns [target], written from [video] with its video stream copied and the part of [audio] that [offset] and
     * [length] select placed against it.
     *
     * @param offset How many seconds into [audio] the part begins.
     * @param length How many seconds the part runs.
     * @param onProgress Receives how much of [target] is written. It is called from any thread.
     * @throws IllegalStateException If ffmpeg fails to write [target].
     */
    suspend fun merge(
        video: Path,
        audio: Path,
        offset: Double,
        length: Double,
        target: Path,
        onProgress: ProgressListener = {},
    ): Path
}
