package dev.klepto.utrecorder.core.capture.video

import java.nio.file.Path

/**
 * Video of a demo playback: a finished frame capture and the demo time it starts at.
 *
 * @property file The video file that holds the frames.
 * @property length How many seconds [file] runs.
 * @property demoStart The demo time, in seconds, the first frame of [file] shows. If it is not known, it is `null`.
 * @property smoothingDelay How many seconds behind the demo [file] shows the movement of the player who recorded the
 * demo. If that movement is not smoothed, it is `0.0`.
 */
data class VideoTrack(
    val file: Path,
    val length: Double,
    val demoStart: Double?,
    val smoothingDelay: Double = 0.0,
)
