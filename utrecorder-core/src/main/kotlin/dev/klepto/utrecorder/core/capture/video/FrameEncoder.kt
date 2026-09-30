package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.time.Duration

/**
 * The loopback host the capture listens on.
 */
private const val STREAM_HOST = "127.0.0.1"

/**
 * The logger of the frame capture.
 */
private val log = logger<FrameEncoder>()

/**
 * The `vibrance` filter step of the color grade, run on planar RGB: `vibrance` has no packed or YUV pixel format
 * support, so it runs before the frames are converted to YUV.
 */
private const val VIBRANCE = "vibrance=intensity=0.15"

/**
 * The `lut` filter step of the color grade, run on the converted YUV. ffmpeg's `eq` filter supports only 8-bit
 * pixel formats, so contrast 1.05 and gamma 0.98 are applied to the luma plane and saturation 1.1 scales the two
 * chroma planes around their neutral midpoint, replicating what `eq` does to an 8-bit `yuv420p` at the same
 * settings, without leaving the 10-bit path.
 */
private const val COLOR_GRADE =
    "lut=y='clip(pow(clip((val-(minval+maxval)/2)*1.05+(minval+maxval)/2\\,minval\\,maxval)/maxval\\,1/0.98)*maxval\\,minval\\,maxval)':" +
        "u='clip((val-(minval+maxval)/2)*1.1+(minval+maxval)/2\\,minval\\,maxval)':" +
        "v='clip((val-(minval+maxval)/2)*1.1+(minval+maxval)/2\\,minval\\,maxval)'"

/**
 * An ffmpeg capture that encodes the raw frames the hook library streams over loopback TCP into the final codec.
 * Run it through [encode].
 *
 * @property config The video capture configuration, carrying the codec, the frame size and the backpressure bound.
 * @property listenTimeout How long ffmpeg waits for the hook library to connect.
 * @property target The file the capture encodes into, already in the final codec.
 * @property ffmpeg The ffmpeg the capture process is started from.
 * @property onProgress Receives the progress ffmpeg reports while it encodes. It is called from any thread.
 */
class FrameEncoder(
    private val config: VideoCaptureConfig,
    private val listenTimeout: Duration,
    val target: Path,
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
    private val onProgress: (MediaProgress) -> Unit = {},
) {
    /**
     * The loopback port ffmpeg listens on for frames. Uses [VideoCaptureConfig.port] if it is positive, otherwise a
     * free port.
     */
    val port: Int = config.port.takeIf { it > 0 } ?: freePort()

    /**
     * Runs ffmpeg listening on [port] until the capture ends, and returns whether ffmpeg exited with code `0`.
     * Use [encode] instead of calling this function directly.
     */
    suspend fun capture(): Boolean = ffmpeg.run(*captureArguments().toTypedArray(), onProgress = onProgress)

    /**
     * Checks that [target] holds a finished capture. Use [encode] instead of calling this function directly.
     *
     * @throws IllegalStateException if [target] does not exist or holds no frames.
     */
    suspend fun checkCapture() {
        check(withContext(Dispatchers.IO) { target.exists() }) { "Frame capture was not produced by ffmpeg" }
        check(ffmpeg.frames(target) > 0) { "Frame capture holds no frames" }
    }

    /**
     * Returns the arguments [capture] runs ffmpeg with, reading raw frames from [port] and encoding them into [target].
     */
    fun captureArguments(): List<String> =
        buildList {
            this += listOf("-y", "-f", "rawvideo", "-pix_fmt", "x2rgb10le")
            this += listOf("-s", "${config.width}x${config.height}")
            this += listOf("-framerate", config.fps.toString())
            this += listOf("-thread_queue_size", config.queue.toString())
            this += listOf("-i", streamUrl())
            this += listOf("-vf", videoFilter())
            this += listOf("-map", "0:v:0")
            this += listOf("-c:v", config.codec)
            this += option("-preset", config.preset)
            this += option("-crf", config.crf)
            this += option(bitrateFlag(), config.bitrate)
            this += option("-svtav1-params", config.svtav1Params)
            this += listOf("-g", config.gop.toString())
            this += recordArguments()
            this += listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv")
            this += listOf("-pix_fmt", "yuv420p10le")
            this += "-an"
            this += target.absolutePathString()
        }

    /**
     * Returns the `-vf` filter chain: a vertical flip, the color grade of [VideoCaptureConfig.colorGrade], a
     * BT.709 limited-range conversion to the encoder's 10-bit 4:2:0 pixel format with accurate chroma downsampling,
     * and a light frame blend of the converted frames when [VideoCaptureConfig.motionBlur] is on.
     */
    private fun videoFilter(): String {
        val grade = if (config.colorGrade) "format=gbrp10le,$VIBRANCE," else ""
        val gradeAfterConvert = if (config.colorGrade) ",$COLOR_GRADE" else ""
        val blend = if (config.motionBlur) ",tmix=frames=2:weights='1 1'" else ""
        return "vflip,$grade" +
            "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le" +
            gradeAfterConvert +
            blend
    }

    /**
     * Returns the ffmpeg input URL that listens on [port], with a receive buffer the size of one 10-bit BGRA frame.
     */
    private fun streamUrl(): String =
        "tcp://$STREAM_HOST:$port?listen=1&listen_timeout=${listenTimeout.inWholeMilliseconds}" +
            "&recv_buffer_size=${config.width * config.height * 4}"

    /**
     * Returns the flag the bitrate is passed under: the ceiling of a constant rate factor encode, the average of one
     * without.
     */
    private fun bitrateFlag(): String =
        when {
            config.crf.isBlank() -> "-b:v"
            else -> "-maxrate"
        }

    /**
     * Returns the extra arguments of [VideoCaptureConfig.arguments], split on spaces.
     */
    private fun recordArguments(): List<String> =
        config.arguments
            .split(" ")
            .filter { it.isNotBlank() }

    /**
     * The receiver of the [encode] block.
     *
     * @property port The loopback port ffmpeg listens on for the frames of the hook library.
     */
    class Connection(
        val port: Int,
    )

    private companion object {
        /**
         * Returns a port that is free on this machine.
         */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * Returns [name] followed by [value], or no arguments if [value] is blank.
         */
        fun option(
            name: String,
            value: String,
        ): List<String> =
            when {
                value.isBlank() -> emptyList()
                else -> listOf(name, value)
            }
    }
}

/**
 * Returns the result of [block] paired with the path of the finished capture. ffmpeg listens on the loopback for the
 * whole duration of [block], and the capture is finalised and checked after [block] returns.
 *
 * Cancelling the coroutine running this function cancels the capture along with [block], and leaves no ffmpeg process
 * running.
 *
 * @throws IllegalStateException if ffmpeg fails the capture, or the capture does not exist or holds no frames.
 */
suspend fun <T> FrameEncoder.encode(block: suspend FrameEncoder.Connection.() -> T): Pair<T, Path> =
    coroutineScope {
        val worker = async { capture() }
        log.debug { "Capturing rendered frames to ${target.fileName}, listening on $STREAM_HOST:$port" }
        val value = FrameEncoder.Connection(port).block()
        check(worker.await()) { "Frame capture was not finalised by ffmpeg" }
        checkCapture()
        value to target
    }
