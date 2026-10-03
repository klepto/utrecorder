package dev.klepto.utrecorder.core.media

import java.nio.file.Path

/**
 * The thumbnail of a recording: one frame of a finished mp4 written as an image.
 *
 * [FfmpegThumbnailer] runs it through ffmpeg.
 */
interface Thumbnailer {
    /**
     * Returns [target], written from the frame of [video] shown [time] seconds into it.
     *
     * @throws IllegalStateException If ffmpeg fails to write [target].
     */
    suspend fun extract(
        video: Path,
        time: Double,
        target: Path,
    ): Path
}
