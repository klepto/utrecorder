package dev.klepto.utrecorder.core

import java.nio.file.Path
import kotlin.io.path.absolutePathString

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
    private val ffmpeg: FfmpegLike = Ffmpeg(),
) {
    private var created: Process? = null

    private var bound = 0

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
    fun captureArguments(port: Int): List<String> =
        buildList {
            add("-y")
            add("-f")
            add("rawvideo")
            add("-pix_fmt")
            add("bgra")
            add("-s")
            add("${config.width}x${config.height}")
            add("-framerate")
            add(config.fps.toString())
            add("-thread_queue_size")
            add(config.frameQueue.toString())
            add("-i")
            add(streamUrl(port))
            add("-vf")
            add("vflip")
            add("-map")
            add("0:v:0")
            add("-c:v")
            add(config.codec)
            addAll(option("-preset", config.preset))
            addAll(option("-b:v", config.bitrate))
            add("-g")
            add(config.gop.toString())
            addAll(config.recordArguments.split(" ").filter { it.isNotBlank() })
            add("-pix_fmt")
            add("yuv420p")
            add("-an")
            add(target.absolutePathString())
        }

    private fun streamUrl(port: Int): String =
        "tcp://127.0.0.1:$port?listen=1&listen_timeout=${config.startupTimeout.inWholeMilliseconds}" +
            "&recv_buffer_size=${config.width * config.height * 4}"

    private fun option(
        name: String,
        value: String,
    ): List<String> = if (value.isBlank()) emptyList() else listOf(name, value)
}
