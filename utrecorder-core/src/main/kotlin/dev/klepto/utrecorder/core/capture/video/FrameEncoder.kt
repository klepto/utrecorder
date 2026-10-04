package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * An ffmpeg capture that encodes the raw frames the hook library streams over loopback TCP into the final codec.
 * The capture listens for the hook library itself and relays its frames to the standard input of ffmpeg. Run it
 * through [encode].
 *
 * @property config The video capture configuration, carrying the codec, the backpressure bound, the port and the finish
 * timeout.
 * @property format The format of the run, carrying the frame size, the bitrate and the preset of the encode.
 * @property listenTimeout How long the capture waits for the hook library to connect.
 * @property target The file the capture encodes into, already in the final codec.
 * @property ffmpeg The ffmpeg the capture process is started from.
 * @property onProgress Receives the progress ffmpeg reports while it encodes. It is called from any thread.
 * @property operatingSystem The operating system the frame socket is configured for.
 * @property processors The processors ffmpeg may spread its encoder and filter threads over. Defaults to the processors
 * the JVM sees, which on Linux follows the CPU quota of a container.
 */
class FrameEncoder(
    private val config: VideoCaptureConfig,
    private val format: VideoFormat,
    private val listenTimeout: Duration,
    val target: Path,
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
    private val onProgress: (MediaProgress) -> Unit = {},
    private val operatingSystem: OperatingSystem = OperatingSystem.current(),
    private val processors: Int = Runtime.getRuntime().availableProcessors(),
) {
    /**
     * Returns the result of [block] paired with the path of the finished capture. The capture listens on the loopback for
     * the whole duration of [block], and the capture is finalised and checked after [block] returns. The hook library has to
     * connect before [block] returns.
     *
     * Cancelling the coroutine running this function cancels the capture along with [block], and leaves no ffmpeg process
     * running.
     *
     * @throws IllegalStateException if the hook library does not connect before [block] returns or closes the connection
     * inside a frame, if ffmpeg fails the capture, or if the capture does not exist or holds no frames.
     * @throws TimeoutException if the hook library does not connect within [listenTimeout], or if ffmpeg does not finish
     * within [VideoCaptureConfig.finishTimeout] after [block] returns. ffmpeg is killed first.
     */
    suspend fun <T> encode(block: suspend Connection.() -> T): Pair<T, Path> =
        listen().use { listener ->
            coroutineScope {
                val port = (listener.localAddress as InetSocketAddress).port
                val ended = CompletableDeferred<Unit>()
                val worker = async { capture(listener, ended) }
                log.debug { "Capturing rendered frames to ${target.fileName}, listening on $STREAM_HOST:$port" }
                val value = Connection(port).block()
                ended.complete(Unit)
                finish(worker)
                checkCapture()
                value to target
            }
        }

    /**
     * Returns a non-blocking loopback listener for the hook library, bound to [VideoCaptureConfig.port], or to a free
     * port if it is `0`. On Windows, its receive buffer holds one frame. On Linux, its receive buffer is left at the
     * system default. Use [encode] instead of calling this function directly.
     */
    suspend fun listen(): ServerSocketChannel =
        withContext(Dispatchers.IO) {
            ServerSocketChannel
                .open()
                .apply { if (operatingSystem == WINDOWS) setOption(StandardSocketOptions.SO_RCVBUF, frameSize()) }
                .bind(InetSocketAddress(STREAM_HOST, config.port))
                .apply { configureBlocking(false) }
        }

    /**
     * Runs ffmpeg until the capture ends, relaying the frames of the first connection [listener] accepts. Once [ended]
     * completes, no connection is waited for any more. Use [encode] instead of calling this function directly.
     *
     * @throws TimeoutException if the hook library does not connect within [listenTimeout].
     * @throws IllegalStateException if the hook library has not connected when [ended] completes, closes the
     * connection inside a frame, or if ffmpeg exits with a code other than `0`.
     */
    private suspend fun capture(
        listener: ServerSocketChannel,
        ended: Job,
    ): Unit = ffmpeg.run(*captureArguments().toTypedArray(), input = { relay(listener, ended, it) }, onProgress = onProgress)

    /**
     * Copies every byte of the first connection [listener] accepts to [input], until the hook library closes it.
     *
     * @throws IllegalStateException if no connection has arrived when [ended] completes, or the connection ends inside
     * a frame.
     */
    private suspend fun relay(
        listener: ServerSocketChannel,
        ended: Job,
        input: OutputStream,
    ) {
        val stream = accept(listener, ended)
        val bytes = runInterruptible(Dispatchers.IO) { stream.use { Channels.newInputStream(it).copyTo(input, frameSize()) } }
        val partial = bytes % frameSize()
        check(partial == 0L) { "Frame capture ended inside a frame, $partial bytes past the last whole frame" }
    }

    /**
     * Returns the first connection [listener] accepts.
     *
     * @throws TimeoutException if no connection arrives within [listenTimeout].
     * @throws IllegalStateException if no connection has arrived when [ended] completes.
     */
    private suspend fun accept(
        listener: ServerSocketChannel,
        ended: Job,
    ): SocketChannel =
        try {
            withTimeout(listenTimeout) { awaitConnection(listener, ended) }
        } catch (timedOut: TimeoutCancellationException) {
            throw TimeoutException("Hook library did not connect within $listenTimeout").apply { initCause(timedOut) }
        }

    /**
     * Returns the first connection [listener] accepts, looking for one every [ACCEPT_INTERVAL]. A connection that
     * arrived before [ended] completed is still returned.
     *
     * @throws IllegalStateException if no connection has arrived when [ended] completes.
     */
    private suspend fun awaitConnection(
        listener: ServerSocketChannel,
        ended: Job,
    ): SocketChannel {
        while (true) {
            val endedBefore = ended.isCompleted
            withContext(Dispatchers.IO) { listener.accept() }?.let { return it }
            check(!endedBefore) { "Hook library did not connect before the capture ended" }
            delay(ACCEPT_INTERVAL)
        }
    }

    /**
     * Waits for [worker], the running [capture], to finish. Use [encode] instead of calling this function directly.
     *
     * @throws TimeoutException if [worker] does not finish within [VideoCaptureConfig.finishTimeout].
     */
    private suspend fun finish(worker: Deferred<Unit>): Unit =
        try {
            withTimeout(config.finishTimeout) { worker.await() }
        } catch (timedOut: TimeoutCancellationException) {
            throw TimeoutException("Frame capture was not finalised by ffmpeg within ${config.finishTimeout}")
                .apply { initCause(timedOut) }
        }

    /**
     * Checks that [target] holds a finished capture. Use [encode] instead of calling this function directly.
     *
     * @throws IllegalStateException if [target] does not exist or holds no frames.
     */
    private suspend fun checkCapture() {
        check(withContext(Dispatchers.IO) { target.exists() }) { "Frame capture was not produced by ffmpeg" }
        check(ffmpeg.frames(target) > 0) { "Frame capture holds no frames" }
    }

    /**
     * Returns the arguments [capture] runs ffmpeg with, reading raw frames from standard input and encoding them into
     * [target].
     */
    fun captureArguments(): List<String> =
        buildList {
            this += listOf("-y", "-f", "rawvideo", "-pix_fmt", "bgra")
            this += listOf("-s", "${format.width}x${format.height}")
            this += listOf("-framerate", config.renderFps().toString())
            this += listOf("-thread_queue_size", config.queue.toString())
            this += listOf("-i", "pipe:0")
            this += listOf("-vf", videoFilter())
            this += listOf("-map", "0:v:0")
            this += listOf("-c:v", config.codec)
            this += option("-preset", format.preset.orEmpty())
            this += listOf("-b:v", "${format.bitrate / 1000}k")
            this += option("-svtav1-params", config.svtav1Params)
            this += listOf("-g", config.gop.toString())
            this += listOf("-threads", processors.toString(), "-filter_threads", processors.toString())
            this += recordArguments()
            this += listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv")
            this += listOf("-pix_fmt", config.pixelFormat)
            this += "-an"
            this += target.absolutePathString()
        }

    /**
     * Returns the `-vf` filter chain: a vertical flip, a BT.709 limited-range conversion to
     * [VideoCaptureConfig.pixelFormat] with accurate chroma downsampling, and, when [VideoCaptureConfig.motionBlur] is
     * on, a motion blur of the converted frames.
     */
    private fun videoFilter(): String {
        val conversion = "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=${config.pixelFormat}"
        return when {
            config.motionBlur -> "vflip,$conversion,${motionBlur()}"
            else -> "vflip,$conversion"
        }
    }

    /**
     * Returns the motion blur step: one encoded frame for every [VideoCaptureConfig.motionBlurSamples] rendered frames.
     * Each encoded frame blends the `motionBlurSamples - 1` rendered frames of its own time slice at full weight with the
     * rendered frame on either edge at quarter weight. Each edge frame is shared with the neighboring encoded frame.
     */
    private fun motionBlur(): String {
        val samples = config.motionBlurSamples
        val weights = listOf(1) + List(samples - 1) { 4 } + listOf(1)
        return "tmix=frames=${samples + 1}:weights='${weights.joinToString(" ")}',framestep=$samples"
    }

    /**
     * Returns the size in bytes of one BGRA frame.
     */
    private fun frameSize(): Int = format.width * format.height * 4

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
     * @property port The loopback port the capture listens on for the frames of the hook library.
     */
    class Connection(
        val port: Int,
    )

    private companion object {
        /**
         * The loopback host the capture listens on.
         */
        const val STREAM_HOST = "127.0.0.1"

        /**
         * How long the capture waits between two looks for the connection of the hook library.
         */
        val ACCEPT_INTERVAL = 50.milliseconds

        /**
         * The logger of the frame capture.
         */
        val log = logger<FrameEncoder>()

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
