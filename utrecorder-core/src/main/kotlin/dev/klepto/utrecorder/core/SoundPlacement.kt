package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * Where a [SoundTrack] sits against a finished frame capture: how far into the wav the mux seeks and how much of it
 * the mux takes.
 *
 * [RecorderConfig.audioOffset] anchors on the demo's last mark; a pass pair that disagree on it more than the end
 * slack, or lack a demo clock, leave the track where the sound pass's own marks place it.
 */
object SoundPlacement {
    /**
     * Returns the placement of [track] against a video [videoLength] seconds long, the audio's end anchored on the
     * demo's last mark where the two passes agree on it, with [config] nudging and [audioOffset] applied.
     */
    fun place(
        track: SoundTrack,
        videoLength: Double,
        framePassEnd: Double?,
        config: RecorderConfig,
    ): Placement = TODO()

    /**
     * One placed sound track: how many seconds into the wav the mux seeks, and how many seconds it takes.
     *
     * @property offset The seek the mux applies to the wav.
     * @property length The duration the mux takes from the seek on.
     */
    data class Placement(
        val offset: Double,
        val length: Double,
    )
}
