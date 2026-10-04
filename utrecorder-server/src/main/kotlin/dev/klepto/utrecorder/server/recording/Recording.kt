package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.demo.Demo
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Recording of one demo, from the time the server learns of the demo until its mp4 is archived.
 *
 * @property demo The demo to record.
 * @property status How far the recording has come.
 * @property error The failure of the last capture of [demo], or `null` if none has failed.
 * @property md5 The MD5 checksum of the uploaded mp4, or `null` if it was not uploaded.
 * @property lastUpdate The time [status] last changed, or [Instant.DISTANT_PAST] if the recording was never stored.
 * @property views The number of views of the recording written to the store.
 * @property lastView The time of the last view of the recording written to the store, or `null` if none was.
 * @property recordedAt The time the mp4 was stored, or `null` if it was not recorded by the server.
 * @property processingTime The time the last attempt took to produce the mp4, or `null` if it was not recorded by
 * the server.
 * @property itemId The id of the item in remote storage the mp4 was uploaded into, or `null` if the mp4 is not in remote
 * storage, or its file there has been deleted.
 */
@Serializable
data class Recording(
    val demo: Demo,
    val status: RecordingStatus = RecordingStatus.Pending,
    val error: String? = null,
    val md5: String? = null,
    val lastUpdate: Instant = Instant.DISTANT_PAST,
    val views: Int = 0,
    val lastView: Instant? = null,
    val recordedAt: Instant? = null,
    val processingTime: Duration? = null,
    val itemId: String? = null,
)

/**
 * State of a [Recording].
 *
 * @property isCompleted `true` if the recording has been recorded and has not been beaten since. Only [Recorded],
 * [Uploaded] and [Archived] are completed.
 */
@Serializable
enum class RecordingStatus(
    val isCompleted: Boolean,
) {
    /** Waiting for a recorder. */
    Pending(isCompleted = false),

    /** Taken by a recorder. Set to [Pending] again when the server starts. */
    Recording(isCompleted = false),

    /** Stored as an mp4. */
    Recorded(isCompleted = true),

    /** Stored as an mp4 and uploaded to remote storage. */
    Uploaded(isCompleted = true),

    /** Uploaded, and confirmed by remote storage to hold an mp4 with the same checksum. */
    Archived(isCompleted = true),

    /**
     * Recorded, uploaded or archived, then beaten by a faster recording of the same player on the same map that has an
     * mp4. Kept as a tombstone, with every other field unchanged. Its [Recording.lastUpdate] is the time it became
     * outdated.
     */
    Outdated(isCompleted = false),

    /**
     * Given up on after its last capture failed. Set to [Pending] again when the server starts, and once every
     * `server.recording.retry_interval` while the server runs.
     */
    Failed(isCompleted = false),

    /**
     * Given up on because its demo names a package that no source holds. Set to [Pending] again when the server starts,
     * and once every `server.recording.retry_interval` while the server runs.
     */
    Unresolvable(isCompleted = false),
}
