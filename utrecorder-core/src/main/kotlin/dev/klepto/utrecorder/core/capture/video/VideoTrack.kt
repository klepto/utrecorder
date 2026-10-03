package dev.klepto.utrecorder.core.capture.video

import java.nio.file.Path

/**
 * Video of a demo playback: a finished frame capture and the demo time it starts at.
 *
 * @property file The video file that holds the frames.
 * @property length How many seconds [file] runs.
 * @property format The format the frames of [file] were recorded in.
 * @property demoStart The demo time, in seconds, the first frame of [file] shows. If it is not known, it is `null`.
 */
data class VideoTrack(
    val file: Path,
    val length: Double,
    val format: VideoFormat,
    val demoStart: Double?,
)
