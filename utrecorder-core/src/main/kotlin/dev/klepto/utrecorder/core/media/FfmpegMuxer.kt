package dev.klepto.utrecorder.core.media

import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.time.DurationUnit.SECONDS

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
        onProgress: ProgressListener<Unit>,
    ): Path {
        withContext(Dispatchers.IO) { target.toFile().delete() }
        val arguments = arguments(video, audio, offset, length, target)
        val merged = ffmpeg.run(*arguments.toTypedArray()) { onProgress(Progress(it.time.toDouble(SECONDS) / length)) }
        check(merged) {
            "Merging ${video.fileName} with ${audio.fileName} failed"
        }
        onProgress(Progress(1.0))
        return target
    }

    /**
     * Returns the ffmpeg arguments that merge [video] with [audio] into [target], each named by its absolute path. The
     * video stream is copied, and the audio is trimmed to the part that [offset] and [length] select.
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
            this += listOf("-i", video.absolutePathString())
            this += listOf("-ss", seconds(offset))
            this += listOf("-t", seconds(length))
            this += listOf("-i", audio.absolutePathString())
            this += listOf("-map", "0:v:0")
            this += listOf("-map", "1:a:0")
            this += listOf("-c:v", "copy")
            this += listOf("-af", filter)
            this += listOf("-c:a", "aac")
            this += listOf("-b:a", bitrate)
            this += listOf("-ac", "2")
            this += "-shortest"
            this += listOf("-movflags", "+faststart")
            this += target.absolutePathString()
        }

    private companion object {
        fun seconds(value: Double): String = "%.3f".format(Locale.ROOT, value)
    }
}
