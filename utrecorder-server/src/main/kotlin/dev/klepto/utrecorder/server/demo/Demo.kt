package dev.klepto.utrecorder.server.demo

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A recorded cap and the demo that holds it.
 *
 * @property capId The identifier of the cap.
 * @property map The name of the map the cap was made on.
 * @property player The alias of the player who capped.
 * @property capTime The time the cap took.
 * @property timestamp The time the cap was added.
 * @property demoUrl The URL the `.dem` file downloads from.
 */
@Serializable
data class Demo(
    val capId: String,
    val map: String,
    val player: String,
    val capTime: Duration,
    val timestamp: Instant,
    val demoUrl: String,
) {
    /**
     * Returns the cap as `<capId>: <map> - <capTime> by <player>`, with the cap time as `mm:ss.SSS`. If the cap time is
     * under a minute, the cap time is `ss.SSS` instead.
     */
    override fun toString(): String = "$capId: $map - ${formatCapTime()} by $player"

    /**
     * Returns [capTime] as `mm:ss.SSS`. If [capTime] is under a minute, returns `ss.SSS` instead.
     */
    private fun formatCapTime(): String =
        capTime.toComponents { minutes, seconds, nanoseconds ->
            val milliseconds = nanoseconds / 1_000_000
            when (minutes) {
                0L -> "%02d.%03d".format(seconds, milliseconds)
                else -> "%02d:%02d.%03d".format(minutes, seconds, milliseconds)
            }
        }
}
