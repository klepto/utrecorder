package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.log.logger
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

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
    private var port: Int = config.framePort.takeIf { it > 0 } ?: freePort()

    private val completed = CountDownLatch(1)

    @Volatile
    private var finalised: Boolean = false

    /**
     * Starts ffmpeg listening on the loopback and returns the port the shim connects to, taken from
     * [RecorderConfig.framePort] or a free one.
     *
     * The capture runs on a daemon thread of its own, blocking until the shim's frames end the stream, so [stop]
     * decides when the recording is over.
     */
    fun start(): Int {
        val worker =
            Thread {
                finalised = ffmpeg.capture(*captureArguments(port).toTypedArray())
                completed.countDown()
            }.apply {
                isDaemon = true
                name = CAPTURE_THREAD
            }
        worker.start()
        log.info { "Capturing rendered frames to ${target.fileName}, listening on $STREAM_HOST:$port" }
        return port
    }

    /**
     * Waits for ffmpeg to finalise the capture and returns [target], whose duration is the video's whole length.
     *
     * @throws IllegalStateException when ffmpeg never finished, produced no capture or a capture holding no frames.
     */
    fun stop(): Path =
        try {
            check(completed.await(config.frameTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                "ffmpeg never finished the frame capture"
            }
            check(finalised) { "ffmpeg failed the frame capture" }
            check(target.exists()) { "ffmpeg produced no frame capture" }
            check(ffmpeg.frames(target) > 0) { "Frame capture holds no frames" }
            target
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("ffmpeg never finished the frame capture", interrupted)
        }

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

    /**
     * The free loopback port a capture that names none listens on.
     */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun streamUrl(port: Int): String =
        "tcp://$STREAM_HOST:$port?listen=1&listen_timeout=${config.startupTimeout.inWholeMilliseconds}" +
            "&recv_buffer_size=${config.width * config.height * 4}"

    private fun option(
        name: String,
        value: String,
    ): List<String> = if (value.isBlank()) emptyList() else listOf(name, value)

    companion object {
        /**
         * The host the capture binds on, never leaving the loopback.
         */
        private const val STREAM_HOST = "127.0.0.1"

        /**
         * The name the capture thread carries.
         */
        private const val CAPTURE_THREAD = "frame-capture"

        /**
         * The logger.
         */
        private val log = logger<FrameRecorder>()
    }
}
