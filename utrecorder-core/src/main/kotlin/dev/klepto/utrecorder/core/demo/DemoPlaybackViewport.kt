package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.ut99.UtPlatform
import java.util.Locale

/**
 * Viewport and frame rate limit of one demo playback.
 *
 * Create one with the constructor.
 *
 * @property width The viewport width the playback renders at.
 * @property height The viewport height the playback renders at.
 * @property frameRateLimit The `FrameRateLimit` the playback holds, `0.0` for no limit.
 * @property renderDevice The render device the playback renders with.
 */
data class DemoPlaybackViewport(
    val width: Int,
    val height: Int,
    val frameRateLimit: Double,
    val renderDevice: DemoPlaybackRenderDevice = DemoPlaybackRenderDevice.OPENGL,
) {
    /**
     * Returns the values the playback sets in each of its client sections.
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
     * Returns the values the playback sets in the section of its [renderDevice].
     */
    fun renderDeviceValues(): Map<String, String> = mapOf("FrameRateLimit" to rate(frameRateLimit))

    /**
     * Returns the game ini values of the playback by section: [clientValues] in the client section of every platform,
     * [renderDevice] as the `GameRenderDevice` of [ENGINE], and [renderDeviceValues] in the section of [renderDevice].
     */
    fun gameIniValues(): Map<String, Map<String, String>> {
        val clients = UtPlatform.entries.associate { it.client to clientValues() }
        return clients +
            (ENGINE to mapOf("GameRenderDevice" to renderDevice.section)) +
            (renderDevice.section to renderDeviceValues())
    }

    companion object {
        /**
         * The section that names the render device of the game.
         */
        const val ENGINE = "Engine.Engine"

        /**
         * Returns the frame rate [value] written the way the game's ini holds one.
         */
        private fun rate(value: Double): String = String.format(Locale.ROOT, "%f", value)
    }
}
