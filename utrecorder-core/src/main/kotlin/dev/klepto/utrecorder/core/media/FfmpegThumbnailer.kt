package dev.klepto.utrecorder.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString

/**
 * Thumbnail of a recording: ffmpeg writing one frame of a finished mp4 as a JPEG.
 *
 * @property ffmpeg The ffmpeg that writes the thumbnail.
 */
class FfmpegThumbnailer(
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
) : Thumbnailer {
    override suspend fun extract(
        video: Path,
        time: Double,
        target: Path,
    ): Path {
        withContext(Dispatchers.IO) { target.toFile().delete() }
        ffmpeg.run(*arguments(video, time, target).toTypedArray())
        return target
    }

    /**
     * Returns the ffmpeg arguments that write the frame of [video] shown [time] seconds into it to [target], each file
     * named by its absolute path.
     */
    fun arguments(
        video: Path,
        time: Double,
        target: Path,
    ): List<String> =
        buildList {
            this += "-y"
            this += listOf("-ss", "%.3f".format(Locale.ROOT, time))
            this += listOf("-i", video.absolutePathString())
            this += listOf("-frames:v", "1")
            this += listOf("-q:v", "2")
            this += listOf("-update", "1")
            this += target.absolutePathString()
        }
}
