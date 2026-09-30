package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.core.demo.DemoPlaybackViewport

/**
 * Settings of the audio capture and of the audio the mux writes from its track.
 *
 * Use the constructor. A property that is not provided takes the value its `record.*` key configures, or else its
 * literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `record.*` keys.
 * @property bitrate The audio bitrate of the mux, in ffmpeg's notation.
 * @property filter The ffmpeg audio filter graph applied to the audio track in the mux.
 * @property offset The manual nudge, in seconds, added to the audio placement.
 * @property render Whether the audio capture draws frames; disabling it also collapses its marks.
 * @property width The viewport width of the audio capture.
 * @property height The viewport height of the audio capture.
 * @property fps The frame rate cap of the audio capture in real time. Its clock steps one mixer period of the wav per
 * frame whatever the cap, so the cap only slows the run down.
 */
class AudioCaptureConfig(
    reader: ConfigReader = SystemConfigReader(),
    val bitrate: String = reader.get("record.audio_bitrate", "192k"),
    val filter: String = reader.get("record.audio_filter", "aresample=async=1"),
    val offset: Double = reader.get("record.audio_offset", 0.0, String::toDouble),
    val render: Boolean = reader.get("record.sound_render", true, String::toBoolean),
    val width: Int = reader.get("record.sound_width", 64, String::toInt),
    val height: Int = reader.get("record.sound_height", 64, String::toInt),
    val fps: Int = reader.get("record.sound_fps", 60, String::toInt),
) {
    /**
     * Returns the viewport of the audio capture: the configured sound viewport, with its fps as the frame rate limit.
     */
    fun viewport(): DemoPlaybackViewport = DemoPlaybackViewport(width = width, height = height, frameRateLimit = fps.toDouble())
}
