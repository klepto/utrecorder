package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.core.capture.video.VideoStream
import java.nio.file.Path

/**
 * Files of one finished recording, with the video they were recorded at.
 *
 * @property mp4 The recorded mp4.
 * @property thumbnail The thumbnail of [mp4], beside it.
 * @property video The frame size and the frame rate of the video of [mp4].
 */
data class RecorderOutput(
    val mp4: Path,
    val thumbnail: Path,
    val video: VideoStream,
)
