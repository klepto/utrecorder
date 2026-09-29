package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.demo.Demo
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Recording of one demo, from the time the server learns of the demo until its mp4 is stored.
 *
 * @property demo The demo to record.
 * @property status How far the recording has come.
 * @property error The failure of the last capture of [demo], or `null` if none has failed.
 * @property views The number of times the recording was requested.
 * @property lastView The time the recording was last requested, or `null` if it never was.
 */
@Serializable
data class Recording(
    val demo: Demo,
    val status: RecordingStatus = RecordingStatus.Pending,
    val error: String? = null,
    val views: Int = 0,
    val lastView: Instant? = null,
)

/**
 * State of a [Recording].
 */
@Serializable
enum class RecordingStatus {
    /** Waiting for a recorder. */
    Pending,

    /** Taken by a recorder. */
    Recording,

    /** Stored as an mp4. */
    Recorded,

    /** Given up on after its last capture failed. Pending again when a [RecordingQueue] is opened. */
    Failed,

    /** Given up on because its demo names a package that no source holds. */
    Unresolvable,
}
