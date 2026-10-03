package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.core.demo.DemoPlaybackRenderDevice
import dev.klepto.utrecorder.core.demo.DemoPlaybackViewport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit.SECONDS

/**
 * Settings of the video capture: the viewport it renders, the fps it steps at, and the encode of its frames.
 *
 * Use the constructor. A property that is not provided takes the value its `record.*` key configures, or else its
 * literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `record.*` keys.
 * @property fps The frames per demo second of the encoded video.
 * @property codec The ffmpeg video encoder the capture encodes into while the demo renders.
 * @property tiers The ladder of recording tiers, from the highest to the lowest minimum bitrate whatever order it is
 * configured in. Its first entry is the largest tier.
 * @property targetSize The size, in bytes, the mp4 of a run is recorded to fit. At `0`, no tier fits and every run takes
 * the fallback of [format].
 * @property svtav1Params The `-svtav1-params` handed to the encoder, blank for none. Tags the AV1 bitstream BT.709
 * limited range by default, since a generic `-color_primaries`/`-color_trc` does not reach the bitstream through
 * `libsvtav1`.
 * @property pixelFormat The ffmpeg pixel format the frames are converted to and encoded in.
 * @property gop How many frames lie between the keyframes of the encode.
 * @property queue The ffmpeg `thread_queue_size` of the capture: how many frames its input may queue.
 * @property port The loopback port the capture listens on, or `0` to take a free one.
 * @property idleTimeout How long the video capture may go without encoding a new frame before it is killed. The
 * window covers the startup of the game before its first frame, so it outlasts the startup timeout of a playback.
 * @property finishTimeout How long the video capture waits for ffmpeg to finalise the capture once the game has exited,
 * before ffmpeg is killed.
 * @property arguments Extra arguments appended to the capture command line, split on spaces.
 * @property smoothRecorder Whether the capture smooths the movement and view of the player who recorded the demo.
 * @property motionBlur Whether the capture renders [motionBlurSamples] frames for every encoded frame and blends them
 * into one at full weight, together with the rendered frame on either side of them at quarter weight. The blur softens
 * motion and helps the encode compress it.
 * @property motionBlurSamples The rendered frames of every encoded frame when [motionBlur] is on.
 * @property renderDevice The render device the video capture renders with, `opengl` or `xopengl`.
 * @property linuxMesa The Mesa tuning of the video capture on Linux.
 * @throws IllegalArgumentException If `record.tiers` holds no tier or an entry that is not
 * `<width>x<height>:<minimum>-<maximum>[:<preset>]` with an even resolution and a minimum at most its maximum.
 */
class VideoCaptureConfig(
    reader: ConfigReader = SystemConfigReader(),
    val fps: Int = reader.get("record.fps", 60, String::toInt),
    val codec: String = reader.get("record.codec", "libsvtav1"),
    tiers: List<VideoTier> =
        parseTiers(reader.get("record.tiers", "1920x1080:8000k-10000k:6,1600x900:6000k-8000k:6,1280x720:2700k-6000k:4")),
    val targetSize: Long = reader.get("record.target_size", 76_000_000L, String::toLong),
    val svtav1Params: String =
        reader.get(
            "record.svtav1_params",
            "tune=0:color-primaries=1:transfer-characteristics=1:matrix-coefficients=1:color-range=0",
        ),
    val pixelFormat: String = reader.get("record.pixel_format", "yuv420p"),
    val gop: Int = reader.get("record.gop", 300, String::toInt),
    val queue: Int = reader.get("record.frame_queue", 32, String::toInt),
    val port: Int = reader.get("record.frame_port", 0, String::toInt),
    val idleTimeout: Duration = reader.duration("record.frame_idle_timeout", 300.seconds),
    val finishTimeout: Duration = reader.duration("record.frame_finish_timeout", 120.seconds),
    val arguments: String = reader.get("record.arguments", ""),
    val smoothRecorder: Boolean = reader.get("record.smooth_recorder", true, String::toBoolean),
    val motionBlur: Boolean = reader.get("record.motion_blur", true, String::toBoolean),
    val motionBlurSamples: Int = reader.get("record.motion_blur_samples", 2, String::toInt),
    val renderDevice: DemoPlaybackRenderDevice =
        reader.get("record.render_device", DemoPlaybackRenderDevice.XOPENGL, DemoPlaybackRenderDevice::parse),
    val linuxMesa: LinuxMesaConfig = LinuxMesaConfig(reader),
) {
    val tiers: List<VideoTier> = ladder(tiers)

    /**
     * Returns the viewport of the video capture for a run in [format]: the resolution of [format], the configured render
     * device, and no frame rate limit.
     */
    fun viewport(format: VideoFormat): DemoPlaybackViewport =
        DemoPlaybackViewport(width = format.width, height = format.height, frameRateLimit = 0.0, renderDevice = renderDevice)

    /**
     * Returns the rendered frames per demo second the video capture is locked to: [fps] times [motionBlurSamples] when
     * [motionBlur] is on, otherwise [fps].
     */
    fun renderFps(): Int =
        when {
            motionBlur -> fps * motionBlurSamples
            else -> fps
        }

    /**
     * Returns the format a run of [runDuration] is recorded in, so that its mp4 fits [targetSize] beside an audio track
     * of [audioBitrate].
     *
     * The budget is the video bitrate that fills [targetSize] over [runDuration] plus [LENGTH_TOLERANCE], less
     * [audioBitrate]. The format takes the first of [tiers] whose minimum bitrate is at most the budget, at the budget or
     * at the maximum bitrate of that tier, whichever is lower, rounded down to whole kilobits.
     *
     * If no tier fits the budget, returns the fallback instead: the last of [tiers] at its maximum bitrate.
     *
     * @param audioBitrate The bitrate of the audio track in ffmpeg's notation, such as `128k`.
     * @throws IllegalArgumentException If [audioBitrate] is not a bitrate in ffmpeg's notation.
     */
    fun format(
        runDuration: Duration,
        audioBitrate: String,
    ): VideoFormat = budgeted(budget(runDuration, parseBitrate(audioBitrate))) ?: fallback()

    /**
     * Returns the video bitrate, in bits per second, that fills [targetSize] over [runDuration] plus [LENGTH_TOLERANCE]
     * beside an audio track of [audioBitrate] bits per second.
     */
    private fun budget(
        runDuration: Duration,
        audioBitrate: Long,
    ): Double = targetSize * 8.0 / (runDuration + LENGTH_TOLERANCE).toDouble(SECONDS) - audioBitrate

    /**
     * Returns the format of the first tier whose minimum bitrate is at most [budget]. If no tier fits, returns `null`
     * instead.
     */
    private fun budgeted(budget: Double): VideoFormat? =
        tiers
            .firstOrNull { it.minimumBitrate <= budget }
            ?.let { videoFormat(it, minOf(budget, it.maximumBitrate.toDouble()), fitsBudget = true) }

    /**
     * Returns the format of the last tier at its maximum bitrate.
     */
    private fun fallback(): VideoFormat = videoFormat(tiers.last(), tiers.last().maximumBitrate.toDouble(), fitsBudget = false)

    companion object {
        /**
         * How much longer than its run the recording of a run may run.
         */
        val LENGTH_TOLERANCE: Duration = 2.seconds

        private val TIER = Regex("""(\d+)x(\d+):(\d+[kM]?)-(\d+[kM]?)(?::([^:\s]+))?""")

        private val BITRATE = Regex("""(\d+)([kM]?)""")

        /**
         * Returns the tiers of the comma-separated `record.tiers` entries in [value], in the order they are listed.
         */
        private fun parseTiers(value: String): List<VideoTier> =
            value
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { parseTier(it) }

        /**
         * Returns the tier of one `record.tiers` [entry].
         *
         * @throws IllegalArgumentException If [entry] is not a valid tier, naming the key and the entry.
         */
        private fun parseTier(entry: String): VideoTier =
            runCatching { tier(entry) }
                .getOrElse { throw IllegalArgumentException("The record.tiers entry '$entry' is invalid: ${it.message}", it) }

        /**
         * Returns the tier [entry] describes.
         *
         * @throws IllegalArgumentException If [entry] is not `<width>x<height>:<minimum>-<maximum>[:<preset>]`, or if
         * its values do not make a valid [VideoTier].
         */
        private fun tier(entry: String): VideoTier {
            val match =
                requireNotNull(TIER.matchEntire(entry)) {
                    "It is not <width>x<height>:<minimum>-<maximum>[:<preset>]"
                }
            val (width, height, minimum, maximum, preset) = match.destructured
            return VideoTier(width.toInt(), height.toInt(), parseBitrate(minimum), parseBitrate(maximum), preset.ifEmpty { null })
        }

        /**
         * Returns the bits per second of [bitrate], written in ffmpeg's notation: a whole number with an optional `k` or
         * `M` suffix.
         *
         * @throws IllegalArgumentException If [bitrate] is not in that notation.
         */
        private fun parseBitrate(bitrate: String): Long {
            val match = requireNotNull(BITRATE.matchEntire(bitrate)) { "The bitrate '$bitrate' is not in ffmpeg's notation" }
            val (value, unit) = match.destructured
            val multiplier =
                when (unit) {
                    "k" -> 1_000L
                    "M" -> 1_000_000L
                    else -> 1L
                }
            return Math.multiplyExact(value.toLong(), multiplier)
        }

        /**
         * Returns [tiers] ordered from the highest to the lowest minimum bitrate.
         *
         * @throws IllegalArgumentException If [tiers] is empty.
         */
        private fun ladder(tiers: List<VideoTier>): List<VideoTier> {
            require(tiers.isNotEmpty()) { "The record.tiers ladder holds no tier" }
            return tiers.sortedByDescending { it.minimumBitrate }
        }

        /**
         * Returns the format of [tier] at [bitrate] bits per second, rounded down to whole kilobits.
         */
        private fun videoFormat(
            tier: VideoTier,
            bitrate: Double,
            fitsBudget: Boolean,
        ): VideoFormat = VideoFormat(tier.width, tier.height, (bitrate / 1000).toLong() * 1000, tier.preset, fitsBudget)
    }
}
