package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The mux of a recording: ffmpeg placing a [SoundTrack] against the finished frame capture and writing the mp4.
 *
 * @property config The recording the mux runs for, carrying the audio filter and bitrate.
 * @property ffmpeg The ffmpeg the mux process is spawned from.
 */
class Muxer(
    private val config: RecorderConfig,
    private val ffmpeg: Ffmpeg = Ffmpeg(),
) {
    /**
     * Merges [video] with [track] into [target] and returns [target], the video stream copied and the audio placed.
     *
     * @throws IllegalStateException when ffmpeg fails.
     */
    fun merge(
        video: Path,
        track: SoundTrack,
        target: Path,
    ): Path = TODO()

    /**
     * The arguments the mux runs ffmpeg with, trimming the audio to where [track] places it against the video.
     */
    fun arguments(
        video: Path,
        track: SoundTrack,
        target: Path,
    ): List<String> = TODO()
}
