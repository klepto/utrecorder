package dev.klepto.utrecorder.core.capture.video

/**
 * Frame size and frame rate of a recorded video.
 *
 * @property width The width of a frame, in pixels.
 * @property height The height of a frame, in pixels.
 * @property fps The frames per second of the video.
 */
data class VideoStream(
    val width: Int,
    val height: Int,
    val fps: Int,
)
