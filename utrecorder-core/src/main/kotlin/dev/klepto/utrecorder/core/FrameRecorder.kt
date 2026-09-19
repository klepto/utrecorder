package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.log.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * The host the capture binds on, never leaving the loopback.
 */
private const val STREAM_HOST = "127.0.0.1"

/**
 * The logger [record] reports the listening port through.
 */
private val log = logger<FrameRecorder>()

/**
 * The listening side of the frame capture: an ffmpeg process encoding the frames the shim streams over loopback TCP
 * into the final codec, run for the duration of [record]'s block.
 *
 * @property config The recording the capture runs for, carrying the codec, the frame size and the backpressure bound.
 * @property target The file the capture encodes into, already in the final codec.
 * @property ffmpeg The ffmpeg the capture process is spawned from.
 */
class FrameRecorder(
    private val config: RecorderConfig,
    val target: Path,
    private val ffmpeg: FfmpegLike = Ffmpeg(),
) {
    /**
     * The port the shim connects to, taken from [RecorderConfig.framePort] or a free one.
     */
    val port: Int = config.framePort.takeIf { it > 0 } ?: freePort()

    /**
     * Runs the capture to completion, listening on [port], and returns whether ffmpeg finalised it with code `0`.
     * Called by [record], which starts this as a child of its own `coroutineScope`; nothing else should call it.
     */
    suspend fun capture(): Boolean = ffmpeg.capture(*captureArguments(port).toTypedArray())

    /**
     * Checks that [target] holds a capture ffmpeg finished. Called by [record] once its block has returned; nothing
     * else should call it.
     *
     * @throws IllegalStateException when [target] does not exist or holds no frames.
     */
    suspend fun checkCapture() {
        check(withContext(Dispatchers.IO) { target.exists() }) { "ffmpeg produced no frame capture" }
        check(ffmpeg.frames(target) > 0) { "Frame capture holds no frames" }
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
            addAll(option("-crf", config.crf))
            addAll(option(bitrateFlag(), config.bitrate))
            addAll(option("-svtav1-params", config.svtav1Params))
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

    /**
     * The flag the bitrate is handed under: the ceiling of a constant rate factor encode, the average of one without.
     */
    private fun bitrateFlag(): String =
        when {
            config.crf.isBlank() -> "-b:v"
            else -> "-maxrate"
        }

    /**
     * The single capture [record] hands its block: the port the shim connects to, and nothing else. Meaningless away
     * from [record], which is why it is nested here rather than a type of its own.
     *
     * @property port The port ffmpeg is listening on for the shim's frames.
     */
    class Capture(
        val port: Int,
    )
}

/**
 * Runs [block] against a [FrameRecorder.Capture] of the port the shim connects to, ffmpeg listening on the loopback
 * for the whole duration of the block, and returns the block's own result alongside the finished capture's path.
 *
 * The capture runs as a child of this function's own `coroutineScope`, started before [block] and awaited after it
 * returns, so cancelling the coroutine running this function cancels the capture worker along with [block] and
 * leaves no ffmpeg process alive.
 *
 * @throws IllegalStateException when ffmpeg failed the capture, produced no capture or a capture holding no frames.
 */
suspend fun <T> FrameRecorder.record(block: suspend FrameRecorder.Capture.() -> T): Pair<T, Path> =
    coroutineScope {
        val worker = async { capture() }
        log.info { "Capturing rendered frames to ${target.fileName}, listening on $STREAM_HOST:$port" }
        val value = FrameRecorder.Capture(port).block()
        check(worker.await()) { "ffmpeg failed the frame capture" }
        checkCapture()
        value to target
    }
