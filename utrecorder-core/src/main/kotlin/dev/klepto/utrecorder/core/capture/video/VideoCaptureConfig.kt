package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.core.demo.DemoPlaybackViewport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of the video capture: the viewport it renders, the fps it steps at, and the encode of its frames.
 *
 * Use the constructor. A property that is not provided takes the value its `record.*` key configures, or else its
 * literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `record.*` keys.
 * @property fps The rendered frames per demo second the video capture is locked to.
 * @property codec The ffmpeg video encoder the capture encodes into while the demo renders.
 * @property preset The encoder preset handed to codecs that take one, blank when the encoder has none.
 * @property crf The constant rate factor the encode targets, blank for an encode at an average [bitrate].
 * @property bitrate The video bitrate of the encode, in ffmpeg's notation: the ceiling of a [crf] encode, the average
 * of one without.
 * @property svtav1Params The `-svtav1-params` handed to the encoder, blank for none. Tags the AV1 bitstream BT.709
 * limited range by default, since a generic `-color_primaries`/`-color_trc` does not reach the bitstream through
 * `libsvtav1`.
 * @property gop How many frames lie between the keyframes of the encode.
 * @property width The viewport width of the video capture, which is also the width of the captured frames.
 * @property height The viewport height of the video capture, which is also the height of the captured frames.
 * @property queue The ffmpeg `thread_queue_size` of the capture: how many frames its input may queue.
 * @property port The loopback port the capture listens on, or `0` to take a free one.
 * @property idleTimeout How long the video capture may go without encoding a new frame before it is killed. The
 * window covers the startup of the game before its first frame, so it outlasts the startup timeout of a playback.
 * @property arguments Extra arguments appended to the capture command line, split on spaces.
 * @property smoothRecorder Whether the capture smooths the movement and view of the player who recorded the demo.
 * @property motionBlur Whether the capture blends each frame with the previous one, a light 50/50 mix that softens
 * motion and helps the encode compress it.
 * @property colorGrade Whether the capture applies a light color grade to the converted frames: a vibrance lift and
 * a contrast, saturation and gamma tweak.
 * @property linuxMesa The Mesa tuning of the video capture on Linux.
 */
class VideoCaptureConfig(
    reader: ConfigReader = SystemConfigReader(),
    val fps: Int = reader.get("record.fps", 60, String::toInt),
    val codec: String = reader.get("record.codec", "libsvtav1"),
    val preset: String = reader.get("record.preset", "4"),
    val crf: String = reader.get("record.crf", "32"),
    val bitrate: String = reader.get("record.bitrate", "12000k"),
    val svtav1Params: String =
        reader.get(
            "record.svtav1_params",
            "tune=0:color-primaries=1:transfer-characteristics=1:matrix-coefficients=1:color-range=0",
        ),
    val gop: Int = reader.get("record.gop", 300, String::toInt),
    val width: Int = reader.get("record.width", 1280, String::toInt),
    val height: Int = reader.get("record.height", 720, String::toInt),
    val queue: Int = reader.get("record.frame_queue", 32, String::toInt),
    val port: Int = reader.get("record.frame_port", 0, String::toInt),
    val idleTimeout: Duration = reader.duration("record.frame_idle_timeout", 300.seconds),
    val arguments: String = reader.get("record.arguments", ""),
    val smoothRecorder: Boolean = reader.get("record.smooth_recorder", true, String::toBoolean),
    val motionBlur: Boolean = reader.get("record.motion_blur", true, String::toBoolean),
    val colorGrade: Boolean = reader.get("record.color_grade", true, String::toBoolean),
    val linuxMesa: LinuxMesaConfig = LinuxMesaConfig(reader),
) {
    /**
     * Returns the viewport of the video capture: the configured resolution and no frame rate limit.
     */
    fun viewport(): DemoPlaybackViewport = DemoPlaybackViewport(width = width, height = height, frameRateLimit = 0.0)
}
