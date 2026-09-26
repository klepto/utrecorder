package dev.klepto.utrecorder.server.source

import java.time.Instant
import kotlin.time.Duration

/**
 * A listing of capped runs, each with a downloadable demo.
 */
interface DemoSource {
    /**
     * Returns the page of verified caps starting at [offset], newest first. An empty page means [offset] is past the
     * oldest cap.
     *
     * @throws java.io.IOException If the source cannot be reached or answers with an error.
     */
    suspend fun caps(offset: Int): List<Cap>

    /**
     * Returns the bytes of the demo of the cap [id], or `null` if the source does not serve it.
     *
     * @throws java.io.IOException If the source cannot be reached or answers with an error other than not found.
     */
    suspend fun demo(id: String): ByteArray?
}

/**
 * A capped run listed by a [DemoSource].
 *
 * @property id The identifier of the cap at the source.
 * @property map The map the source lists the cap on.
 * @property alias The alias of the player who capped.
 * @property capTime The time the cap took.
 * @property added The time the source added the cap.
 */
data class Cap(
    val id: String,
    val map: String,
    val alias: String,
    val capTime: Duration,
    val added: Instant,
)
