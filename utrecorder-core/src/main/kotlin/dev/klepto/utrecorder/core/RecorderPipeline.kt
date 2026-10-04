package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.slowest
import dev.klepto.utrecorder.core.RecorderStage.Capturing
import dev.klepto.utrecorder.core.RecorderStage.Muxing
import dev.klepto.utrecorder.core.capture.audio.AudioAlignment
import dev.klepto.utrecorder.core.capture.audio.AudioCapture
import dev.klepto.utrecorder.core.capture.audio.AudioCaptureConfig
import dev.klepto.utrecorder.core.capture.audio.OpenalAudioCapture
import dev.klepto.utrecorder.core.capture.video.FfmpegVideoCapture
import dev.klepto.utrecorder.core.capture.video.VideoCapture
import dev.klepto.utrecorder.core.capture.video.VideoCaptureConfig
import dev.klepto.utrecorder.core.capture.video.VideoCaptureConfig.Companion.LENGTH_TOLERANCE
import dev.klepto.utrecorder.core.capture.video.VideoFormat
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.FfmpegMuxer
import dev.klepto.utrecorder.core.media.FfmpegThumbnailer
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.core.media.Muxer
import dev.klepto.utrecorder.core.media.Thumbnailer
import dev.klepto.utrecorder.ut99.UtInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit.SECONDS

/**
 * A recording of demos in one installation: a video capture and an audio capture, muxed into one mp4 with a thumbnail.
 *
 * Create one with the constructor. Several recordings may share one installation.
 *
 * @property video The configuration of the video capture and of its encode.
 * @property audio The configuration of the audio capture and of the audio of the mux.
 * @property installation The installation both captures run in.
 * @property directories The directories the workspaces of the recording and of the captures are kept under.
 * @property ffmpeg The ffmpeg that the default captures, the default mux and the default thumbnailer run.
 * @param videoCapture The video capture. If not provided, uses an [FfmpegVideoCapture] of [video], the bitrate of
 * [audio], [installation] and [ffmpeg] instead.
 * @param audioCapture The audio capture. If not provided, uses an [OpenalAudioCapture] of [audio], [installation] and
 * [ffmpeg] instead.
 * @param muxer The mux. If not provided, uses an [FfmpegMuxer] of [audio] and [ffmpeg] instead.
 * @param thumbnailer The thumbnailer. If not provided, uses an [FfmpegThumbnailer] of [ffmpeg] instead.
 */
class RecorderPipeline(
    private val video: VideoCaptureConfig,
    private val audio: AudioCaptureConfig,
    private val installation: UtInstallation,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
    videoCapture: VideoCapture? = null,
    audioCapture: AudioCapture? = null,
    muxer: Muxer? = null,
    thumbnailer: Thumbnailer? = null,
) {
    /**
     * The capture the recording takes its video from.
     */
    private val videoCapture: VideoCapture = videoCapture ?: FfmpegVideoCapture(video, audio.bitrate, installation, directories, ffmpeg)

    /**
     * The capture the recording takes its audio from.
     */
    private val audioCapture: AudioCapture = audioCapture ?: OpenalAudioCapture(audio, installation, directories, ffmpeg)

    /**
     * The mux that merges the video and the audio into the mp4.
     */
    private val muxer: Muxer = muxer ?: FfmpegMuxer(audio.filter, audio.bitrate, ffmpeg)

    /**
     * The thumbnailer that writes the thumbnail of the mp4.
     */
    private val thumbnailer: Thumbnailer = thumbnailer ?: FfmpegThumbnailer(ffmpeg)

    /**
     * Returns the path of the mp4 `<name>.mp4` recorded from [demo], kept in a workspace of the recording's own.
     *
     * The audio capture runs beside the video capture, and the mux runs once both have finished. Cancelling this call
     * cancels both captures. The audio track is placed by [AudioAlignment.place] to start at the demo time of the video
     * capture's spawn mark, the demo time the first frame of the video shows.
     *
     * Then the length of the mp4 is checked against [runTime]. The mp4 must run at least [runTime], and at most
     * [LENGTH_TOLERANCE] longer. If [runTime] is not provided, the length is not checked.
     *
     * The last step writes the thumbnail `<name>.jpg` beside the mp4: the frame [THUMBNAIL_TIME] into the mp4, or the
     * middle frame of a shorter mp4.
     *
     * [onProgress] receives the [Capturing] stage and then the [Muxing] stage, each with an estimate of how much of it
     * is done. The progress of [Capturing] is the lesser progress of the two captures, and reaches `1.0` once both have
     * finished. [onProgress] is called from any thread.
     *
     * @param runTime The length of the run the demo records.
     * @throws IllegalStateException If either capture fails, if the length of the mp4 does not match [runTime], or if
     * the thumbnail cannot be written. The mp4 stays in the workspace either way.
     */
    suspend fun record(
        demo: Path,
        name: String,
        runTime: Duration? = null,
        onProgress: ProgressListener<RecorderStage> = {},
    ): Path =
        workspace("record", config = directories) { output ->
            val capturing = Capturing.start(onProgress)
            val (videoProgress, audioProgress) = capturing.slowest(2)
            val (videoTrack, audioTrack) =
                coroutineScope {
                    val videoTrack = async(Dispatchers.IO) { videoCapture.record(demo, videoProgress) }
                    val audioTrack = async(Dispatchers.IO) { audioCapture.record(demo, audioProgress) }
                    videoTrack.await() to audioTrack.await()
                }
            capturing(Progress(1.0))

            val muxing = Muxing.start(onProgress)
            val target = (output.directory / "$name.mp4").also(output::track)
            val placement = AudioAlignment.place(audioTrack, videoTrack.length, videoTrack.demoStart, audio.offset)
            val mp4 = muxer.merge(videoTrack.file, audioTrack.file, placement.offset, placement.length, target, muxing)
            runTime?.let { checkLength(mp4, it) }
            warnIfOversized(mp4, videoTrack.format)
            val thumbnail = (output.directory / "$name.jpg").also(output::track)
            thumbnailer.extract(mp4, minOf(THUMBNAIL_TIME, videoTrack.length / 2), thumbnail)
            mp4
        }

    /**
     * Checks that [mp4] runs at least [runTime], and at most [LENGTH_TOLERANCE] longer.
     *
     * @throws IllegalStateException If the length of [mp4] is outside that range.
     */
    private suspend fun checkLength(
        mp4: Path,
        runTime: Duration,
    ) {
        val length = ffmpeg.duration(mp4).seconds
        check(length in runTime..runTime + LENGTH_TOLERANCE) {
            "The mp4 runs ${format(length)} seconds, but the run takes ${format(runTime)} seconds"
        }
    }

    /**
     * Logs a warning when [mp4], recorded in [format], is larger than the target size of [video] although the budget
     * chose [format]. A format the fallback chose is expected to miss the target and logs nothing.
     */
    private suspend fun warnIfOversized(
        mp4: Path,
        format: VideoFormat,
    ) {
        if (!format.fitsBudget) return
        val size = withContext(Dispatchers.IO) { mp4.fileSize() }
        if (size > video.targetSize) {
            log.warn { "The mp4 $mp4 is $size bytes, over the target size of ${video.targetSize} bytes" }
        }
    }

    private companion object {
        /**
         * How many seconds into the mp4 the thumbnail is taken.
         */
        const val THUMBNAIL_TIME = 1.0

        /**
         * The logger of the recorder pipeline.
         */
        val log = logger<RecorderPipeline>()

        fun format(duration: Duration): String = "%.3f".format(Locale.ROOT, duration.toDouble(SECONDS))
    }
}
