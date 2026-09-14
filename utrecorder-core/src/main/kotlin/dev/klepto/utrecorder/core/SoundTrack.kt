package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The sound a recording carries and where it sits: a wav a sound pass produced, placed by its own marks.
 *
 * @property file The wav the sound pass wrote.
 * @property offset How many seconds into [file] the demo's audio begins.
 * @property length How many seconds of [file] the demo's audio runs.
 * @property demoEnd The demo stream time, in seconds, the sound pass ended its playback at.
 * @property demoSpan The demo stream time, in seconds, the sound pass's playback spanned.
 */
data class SoundTrack(
    val file: Path,
    val offset: Double,
    val length: Double,
    val demoEnd: Double,
    val demoSpan: Double,
)
