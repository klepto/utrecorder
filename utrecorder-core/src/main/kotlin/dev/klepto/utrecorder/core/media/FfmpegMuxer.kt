package dev.klepto.utrecorder.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Mux of a recording: ffmpeg writing the mp4 from a finished frame capture and a part of an audio file placed against
 * it.
 *
 * @property filter The ffmpeg audio filter applied to the audio.
 * @property bitrate The bitrate the audio is encoded at.
 * @property ffmpeg The ffmpeg that runs the mux.
 */
class FfmpegMuxer(
    private val filter: String,
    private val bitrate: String,
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
) : Muxer {
    override suspend fun merge(
        video: Path,
        audio: Path,
        offset: Double,
        length: Double,
        target: Path,
    ): Path {
        withContext(Dispatchers.IO) { target.toFile().delete() }
        check(ffmpeg.run(*arguments(video, audio, offset, length, target).toTypedArray())) {
            "Merging ${video.fileName} with ${audio.fileName} failed"
        }
        return target
    }

    /**
     * Returns the ffmpeg arguments that merge [video] with [audio] into [target]. The video stream is copied, and the
     * audio is trimmed to the part that [offset] and [length] select.
     */
    fun arguments(
        video: Path,
        audio: Path,
        offset: Double,
        length: Double,
        target: Path,
    ): List<String> =
        buildList {
            this += "-y"
            this += listOf("-i", video.toString())
            this += listOf("-ss", seconds(offset))
            this += listOf("-t", seconds(length))
            this += listOf("-i", audio.toString())
            this += listOf("-map", "0:v:0")
            this += listOf("-map", "1:a:0")
            this += listOf("-c:v", "copy")
            this += listOf("-af", filter)
            this += listOf("-c:a", "aac")
            this += listOf("-b:a", bitrate)
            this += listOf("-ac", "2")
            this += "-shortest"
            this += listOf("-movflags", "+faststart")
            this += target.toString()
        }

    private companion object {
        fun seconds(value: Double): String = "%.3f".format(value)
    }
}
