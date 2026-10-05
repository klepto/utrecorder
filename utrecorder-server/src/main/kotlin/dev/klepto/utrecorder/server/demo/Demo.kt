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
 * @property userId The UTBT identifier of the player who capped, or `null` if the cap list leaves it out.
 * @property capTime The time the cap took.
 * @property timestamp The time the cap was added.
 * @property demoUrl The URL the `.dem` file downloads from.
 * @property medal The medal of the cap, as UTBT numbers it, or `null` if the cap list leaves it out.
 * @property teammates The UTBT identifiers of the other players of the team, sorted. Empty for a solo cap.
 * @property teamRunId The UTBT identifier of the team run the cap belongs to, shared by the caps of every member of
 * that run, or `null` for a solo cap.
 */
@Serializable
data class Demo(
    val capId: String,
    val map: String,
    val player: String,
    val userId: String? = null,
    val capTime: Duration,
    val timestamp: Instant,
    val demoUrl: String,
    val medal: Int? = null,
    val teammates: List<String> = emptyList(),
    val teamRunId: String? = null,
) {
    /**
     * Returns the cap as `<capId>: <map> - <capTime> by <player>`, with the cap time as `mm:ss.SSS`. If the cap time is
     * under a minute, the cap time is `ss.SSS` instead.
     */
    override fun toString(): String = "$capId: ${title()}"

    /**
     * Returns the title of the cap, `<map> - <capTime> by <player>`, with the cap time as [formatCapTime] returns it.
     */
    fun title(): String = "$map - ${formatCapTime()} by $player"

    /**
     * Returns [capTime] as `mm:ss.SSS`. If [capTime] is under a minute, returns `ss.SSS` instead.
     */
    fun formatCapTime(): String =
        capTime.toComponents { minutes, seconds, nanoseconds ->
            val milliseconds = nanoseconds / 1_000_000
            when (minutes) {
                0L -> "%02d.%03d".format(seconds, milliseconds)
                else -> "%02d:%02d.%03d".format(minutes, seconds, milliseconds)
            }
        }
}
