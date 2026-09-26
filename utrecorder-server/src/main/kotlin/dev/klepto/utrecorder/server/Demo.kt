package dev.klepto.utrecorder.server

import java.time.Instant
import kotlin.time.Duration

/**
 * A capped run taken from the demo source, with the demo file it was recorded in.
 *
 * @property id The local key. `0` for a demo that is not stored yet.
 * @property sourceId The identifier of the cap at the demo source.
 * @property map The map the demo plays on, or `null` if the demo could not be parsed.
 * @property player The alias of the player who capped.
 * @property capTime The time the cap took.
 * @property duration The real-time length of the demo, or `null` if the demo could not be parsed.
 * @property addedAt The time the demo source added the cap. Demos are recorded in this order.
 * @property recordingId The local key of the demo's [Recording]. `0` for a demo that is not stored yet.
 */
data class Demo(
    val id: Long,
    val sourceId: String,
    val map: String?,
    val player: String,
    val capTime: Duration,
    val duration: Duration?,
    val addedAt: Instant,
    val recordingId: Long,
)
