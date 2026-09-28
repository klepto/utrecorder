package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.workspace
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
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.FfmpegMuxer
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.core.media.Muxer
import dev.klepto.utrecorder.ut99.UtInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path

/**
 * A recording of demos in one installation: a video capture and an audio capture, muxed into one mp4.
 *
 * Create one with the constructor. Run one recording per installation at a time.
 *
 * @property video The configuration of the video capture and of its encode.
 * @property audio The configuration of the audio capture and of the audio of the mux.
 * @property installation The installation both captures run in.
 * @property directories The directories the workspaces of the recording and of the captures are kept under.
 * @property ffmpeg The ffmpeg that the default captures and the default mux run.
 * @param videoCapture The video capture. If not provided, uses an [FfmpegVideoCapture] of [video], [installation] and
 * [ffmpeg] instead.
 * @param audioCapture The audio capture. If not provided, uses an [OpenalAudioCapture] of [audio], [installation] and
 * [ffmpeg] instead.
 * @param muxer The mux. If not provided, uses an [FfmpegMuxer] of [audio] and [ffmpeg] instead.
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
) {
    /**
     * The capture the recording takes its video from.
     */
    private val videoCapture: VideoCapture = videoCapture ?: FfmpegVideoCapture(video, installation, directories, ffmpeg)

    /**
     * The capture the recording takes its audio from.
     */
    private val audioCapture: AudioCapture = audioCapture ?: OpenalAudioCapture(audio, installation, directories, ffmpeg)

    /**
     * The mux that merges the video and the audio into the mp4.
     */
    private val muxer: Muxer = muxer ?: FfmpegMuxer(audio.filter, audio.bitrate, ffmpeg)

    /**
     * Returns the path of the mp4 `<name>.mp4` recorded from [demo], kept in a workspace of the recording's own.
     *
     * The audio capture runs beside the video capture, and the mux runs once both have finished. Cancelling this call
     * cancels both captures. The audio track is placed against the end mark of the video capture.
     *
     * [onProgress] receives the [Capturing] stage and then the [Muxing] stage, each with an estimate of how much of it
     * is done. The progress of [Capturing] is the lesser progress of the two captures, and reaches `1.0` once both have
     * finished. [onProgress] is called from any thread.
     *
     * @throws IllegalStateException If either capture fails.
     */
    suspend fun record(
        demo: Path,
        name: String,
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
            val target = output["$name.mp4"]
            val placement = AudioAlignment.place(audioTrack, videoTrack.length, videoTrack.demoEnd, audio.offset)
            muxer.merge(videoTrack.file, audioTrack.file, placement.offset, placement.length, target, muxing)
        }
}
