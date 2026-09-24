package dev.klepto.utrecorder.core.capture.video

import java.nio.file.Path

/**
 * Video of a demo playback: a finished frame capture and the demo time it ended at.
 *
 * @property file The video file that holds the frames.
 * @property length How many seconds [file] runs.
 * @property demoEnd The demo time, in seconds, the playback ended at.
 */
data class VideoTrack(
    val file: Path,
    val length: Double,
    val demoEnd: Double,
)
