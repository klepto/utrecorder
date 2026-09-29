package dev.klepto.utrecorder.core.capture.video

import java.util.Locale
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit.SECONDS

/**
 * Fixed delays, behind the demo, at which the recorder smoother shows the player who recorded a demo for a whole
 * playback.
 *
 * Use [of] to create one from the frame interval of a demo.
 *
 * @property frameInterval The demo time between two demo frames that the smoother fits its snapshots over.
 * @property movement How far behind the demo the movement of the player is shown.
 * @property rotation How far behind the demo the view of the player is shown. It is never longer than [movement].
 */
data class RecorderSmoothing(
    val frameInterval: Duration,
    val movement: Duration,
    val rotation: Duration,
) {
    /**
     * Returns the console command `SmoothRecorder <frameInterval> <movement> <rotation>`, each value in seconds.
     */
    fun command(): String =
        "SmoothRecorder %.6f %.3f %.3f".format(
            Locale.ROOT,
            frameInterval.toDouble(SECONDS),
            movement.toDouble(SECONDS),
            rotation.toDouble(SECONDS),
        )

    companion object {
        private const val MIN_MOVEMENT = 0.03
        private const val MAX_MOVEMENT = 0.06
        private const val MIN_ROTATION = 0.015
        private val DEFAULT_FRAME_INTERVAL = 8.milliseconds

        /**
         * Returns the delays for a demo whose demo frames are [frameInterval] apart. The movement delay is four frame
         * intervals plus 15 ms, rounded to 10 ms and kept between 30 ms and 60 ms. The rotation delay is two frame
         * intervals plus 8 ms, rounded to 5 ms and kept between 15 ms and the movement delay.
         *
         * If [frameInterval] is `null`, uses a frame interval of 8 ms instead.
         */
        fun of(frameInterval: Duration?): RecorderSmoothing {
            val interval = frameInterval ?: DEFAULT_FRAME_INTERVAL
            val seconds = interval.toDouble(SECONDS)
            val movement = roundTo(4 * seconds + 0.015, 0.01).coerceIn(MIN_MOVEMENT, MAX_MOVEMENT)
            val rotation = roundTo(2 * seconds + 0.008, 0.005).coerceIn(MIN_ROTATION, movement)
            return RecorderSmoothing(interval, movement.seconds, rotation.seconds)
        }

        /**
         * Returns [value] rounded to the nearest whole multiple of [step], with a half step rounded up.
         */
        private fun roundTo(
            value: Double,
            step: Double,
        ): Double = floor(value / step + 0.5) * step
    }
}
