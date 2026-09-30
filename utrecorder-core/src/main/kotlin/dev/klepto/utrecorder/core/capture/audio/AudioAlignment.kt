package dev.klepto.utrecorder.core.capture.audio

/**
 * Placement of an [AudioTrack] against a finished video, reached through [place].
 */
object AudioAlignment {
    /**
     * Returns the placement of [track] against a video [videoLength] seconds long.
     *
     * The end of the placed audio sits on the end of the video, shifted by [audioOffset] seconds. If the two captures
     * ended at different demo times, the end moves by the demo time between [videoCaptureEnd] and the demo end of
     * [track], so the same demo time sits at the end of both. This applies only when [videoCaptureEnd] and the demo end
     * of [track] are both known. Otherwise, or if the shifted start would fall before the start of the wav, returns the
     * track's own offset and length instead.
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
        val endGap = if (anchored) videoCaptureEnd - track.demoEnd else 0.0
        val offset = track.offset + track.length + endGap - videoLength + audioOffset
        val keepsOwnPlacement = !anchored || offset < 0
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
