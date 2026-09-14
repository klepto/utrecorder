package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The listening side of the frame capture: an ffmpeg process encoding the frames the shim streams over loopback TCP
 * into the final codec, started by [start] and finished by [stop].
 *
 * @property config The recording the capture runs for, carrying the codec, the frame size and the backpressure bound.
 * @property target The file the capture encodes into, already in the final codec.
 * @property ffmpeg The ffmpeg the capture process is spawned from.
 */
class FrameRecorder(
    private val config: RecorderConfig,
    private val target: Path,
    private val ffmpeg: Ffmpeg = Ffmpeg(),
) {
    /**
     * Starts ffmpeg listening on the loopback and returns the port the shim connects to, taken from
     * [RecorderConfig.framePort] or a free one.
     */
    fun start(): Int = TODO()

    /**
     * Waits for ffmpeg to finalise the capture and returns [target], whose duration is the video's whole length.
     *
     * @throws IllegalStateException when ffmpeg never finished, produced no capture or a capture holding no frames.
     */
    fun stop(): Path = TODO()

    /**
     * The arguments the capture runs ffmpeg with, reading the raw frames the shim streams to [port].
     */
    fun captureArguments(port: Int): List<String> = TODO()
}
