package dev.klepto.utrecorder.core

import java.util.Locale
import kotlin.time.Duration

/**
 * The per-pass values a recording pass applies to its own game-ini copy: the viewport it renders in and the frame
 * rate limit its clients hold.
 *
 * Use [framePass] or [soundPass] to derive one from a [RecorderConfig].
 *
 * @property width The viewport width the pass renders at.
 * @property height The viewport height the pass renders at.
 * @property frameRateLimit The `FrameRateLimit` the pass's clients hold, `0.0` for an unbounded frame pass.
 * @property frameTimeout How long the pass may run before it is killed.
 */
data class RecordingDescription(
    val width: Int,
    val height: Int,
    val frameRateLimit: Double,
    val frameTimeout: Duration,
) {
    /**
     * Returns the values the pass sets in each of its client sections.
     */
    fun clientValues(): Map<String, String> =
        mapOf(
            "WindowedViewportX" to width.toString(),
            "WindowedViewportY" to height.toString(),
            "FullscreenViewportX" to width.toString(),
            "FullscreenViewportY" to height.toString(),
            "FrameRateLimit" to rate(frameRateLimit),
        )

    /**
     * Returns the values the pass sets in the `[OpenGLDrv.OpenGLRenderDevice]` section.
     */
    fun renderDeviceValues(): Map<String, String> = mapOf("FrameRateLimit" to rate(frameRateLimit))

    companion object {
        /**
         * The client section the Windows build configures.
         */
        const val WINDOWS_CLIENT = "WinDrv.WindowsClient"

        /**
         * The client section the Linux build configures.
         */
        const val SDL_CLIENT = "SDLDrv.SDLClient"

        /**
         * The render device section the sound pass caps.
         */
        const val RENDER_DEVICE = "OpenGLDrv.OpenGLRenderDevice"

        /**
         * Returns the description of the frame pass: the config's resolution with an unbounded frame rate and the
         * fixed frame timeout.
         */
        fun framePass(config: RecorderConfig): RecordingDescription =
            RecordingDescription(
                width = config.width,
                height = config.height,
                frameRateLimit = 0.0,
                frameTimeout = config.frameTimeout,
            )

        /**
         * Returns the description of the sound pass: the sound viewport with the sound frame rate as its limit and
         * the timeout a demo [demoDuration] long is run under.
         */
        fun soundPass(
            config: RecorderConfig,
            demoDuration: Duration,
        ): RecordingDescription =
            RecordingDescription(
                width = config.soundWidth,
                height = config.soundHeight,
                frameRateLimit = config.soundFps.toDouble(),
                frameTimeout = config.timeout(demoDuration),
            )

        /**
         * Returns the frame rate [value] written the way the game's ini holds one.
         */
        private fun rate(value: Double) = String.format(Locale.ROOT, "%f", value)
    }
}
