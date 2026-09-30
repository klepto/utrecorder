package dev.klepto.utrecorder.core.capture.audio

/**
 * Placement of an [AudioTrack] against a finished video, reached through [place].
 */
object AudioAlignment {
    /**
     * Returns the placement of [track] against a video [videoLength] seconds long that starts at demo time
     * [videoStart].
     *
     * The placed audio starts where the wav plays demo time [videoStart], shifted by [audioOffset] seconds, and runs
     * for [videoLength]. The wav position of [videoStart] is counted back from the end of [track], by the demo time
     * between [videoStart] and the demo end of [track]. If [videoStart] or the demo end of [track] is not known, or if
     * the shifted start would fall before the start of the wav, returns the track's own offset and length instead.
     *
     * @param videoStart The demo time, in seconds, the first frame of the video shows, or `null` if not known.
     * @param audioOffset The manual shift, in seconds, added to the start of the placed audio.
     */
    fun place(
        track: AudioTrack,
        videoLength: Double,
        videoStart: Double?,
        audioOffset: Double,
    ): Placement {
        val offset =
            videoStart
                ?.takeIf { track.demoEnd > 0 && videoLength > 0 }
                ?.let { track.offset + track.length - (track.demoEnd - it) + audioOffset }
        return when {
            offset == null || offset < 0 -> Placement(track.offset, track.length)
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
