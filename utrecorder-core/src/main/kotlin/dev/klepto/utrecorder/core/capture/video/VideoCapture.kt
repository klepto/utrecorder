package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ProgressListener
import java.nio.file.Path

/**
 * The video capture of a recording: a demo played with every rendered frame written to a [VideoTrack].
 *
 * [FfmpegVideoCapture] runs it through Unreal Tournament with the hook library loaded.
 */
interface VideoCapture {
    /**
     * Returns the [VideoTrack] of [demo] played with every rendered frame captured.
     *
     * The video file of the track is kept in a workspace of the capture's own. [onProgress] receives an estimate of
     * how much of [demo] is captured. It is called from any thread.
     */
    suspend fun record(
        demo: Path,
        onProgress: ProgressListener<Unit> = {},
    ): VideoTrack
}
