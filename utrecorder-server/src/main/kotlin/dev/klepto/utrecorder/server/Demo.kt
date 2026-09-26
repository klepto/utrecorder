@file:UseSerializers(InstantSerializer::class)

package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.server.database.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import kotlin.time.Duration

/**
 * A capped run taken from the demo source, with the demo file it was recorded in.
 *
 * @property id The identifier of the cap at the demo source.
 * @property map The map the demo plays on, or `null` if the demo could not be parsed.
 * @property player The alias of the player who capped.
 * @property capTime The time the cap took.
 * @property duration The real-time length of the demo, or `null` if the demo could not be parsed.
 * @property addedAt The time the demo source added the cap. Demos are recorded in this order.
 */
@Serializable
data class Demo(
    val id: String,
    val map: String?,
    val player: String,
    val capTime: Duration,
    val duration: Duration?,
    val addedAt: Instant,
)
