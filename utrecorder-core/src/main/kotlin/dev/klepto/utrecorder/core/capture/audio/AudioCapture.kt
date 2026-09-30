package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.ProgressListener
import java.nio.file.Path

/**
 * The audio capture of a recording: a demo played with sound on, its audio written to an [AudioTrack].
 *
 * [OpenalAudioCapture] runs it through Unreal Tournament with the OpenAL wave output.
 */
interface AudioCapture {
    /**
     * Returns the [AudioTrack] of [demo] played with sound on, placed by the marks logged while it plays.
     *
     * The audio file of the track is kept in a workspace of the capture's own. [onProgress] receives an estimate of
     * how much of [demo] is captured. It is called from any thread.
     */
    suspend fun record(
        demo: Path,
        onProgress: ProgressListener<Unit> = {},
    ): AudioTrack
}
