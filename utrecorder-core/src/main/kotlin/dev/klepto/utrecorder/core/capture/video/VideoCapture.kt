package dev.klepto.utrecorder.core.capture.video

import java.nio.file.Path

/**
 * The video capture of a recording: a demo played with every rendered frame sent to a [FrameEncoder].
 *
 * [FfmpegVideoCapture] runs it through Unreal Tournament with the hook library loaded.
 */
interface VideoCapture {
    /**
     * Returns the [VideoTrack] of [demo] played with every rendered frame captured.
     *
     * The video file of the track is kept in a workspace of the capture's own.
     */
    suspend fun record(demo: Path): VideoTrack
}
