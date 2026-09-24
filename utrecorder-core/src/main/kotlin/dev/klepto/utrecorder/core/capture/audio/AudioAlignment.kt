package dev.klepto.utrecorder.core.capture.audio

import kotlin.math.abs

/**
 * Placement of an [AudioTrack] against a finished video, reached through [place].
 */
object AudioAlignment {
    private const val AUDIO_END_SLACK = 0.05

    /**
     * Returns the placement of [track] against a video [videoLength] seconds long.
     *
     * The end of the placed audio sits on the end of the video, shifted by [audioOffset] seconds. This applies only
     * when [videoCaptureEnd] and the demo end of [track] are both known and lie within 0.05 seconds of each other.
     * Otherwise, or if the shifted start would fall before the start of the wav, returns the track's own offset and
     * length instead.
     *
     * @param videoCaptureEnd The demo time, in seconds, the video capture ended its playback at, or `null` if not
     * known.
     * @param audioOffset The manual shift, in seconds, added to the start of the placed audio.
     */
    fun place(
        track: AudioTrack,
        videoLength: Double,
        videoCaptureEnd: Double?,
        audioOffset: Double,
    ): Placement {
        val anchored = videoCaptureEnd != null && videoCaptureEnd > 0 && track.demoEnd > 0 && videoLength > 0
        val offset = track.offset + track.length - videoLength + audioOffset
        val keepsOwnPlacement = !anchored || abs(videoCaptureEnd - track.demoEnd) > AUDIO_END_SLACK || offset < 0
        return when {
            keepsOwnPlacement -> Placement(track.offset, track.length)
            else -> Placement(offset, videoLength)
        }
    }

    /**
     * Part of a wav that an audio track takes.
     *
     * @property offset How many seconds into the wav the part begins.
     * @property length How many seconds the part runs.
     */
    data class Placement(
        val offset: Double,
        val length: Double,
    )
}
