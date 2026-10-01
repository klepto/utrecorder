package dev.klepto.utrecorder.core.capture.audio

import java.nio.file.Path

/**
 * Audio of a demo playback: a wav and the part of it that the playback takes.
 *
 * @property file The wav that holds the audio.
 * @property offset How many seconds into [file] the playback plays [demoStart].
 * @property length How many seconds of [file] the playback runs from [offset].
 * @property demoStart The demo time, in seconds, that [file] plays at [offset].
 */
data class AudioTrack(
    val file: Path,
    val offset: Double,
    val length: Double,
    val demoStart: Double,
)
