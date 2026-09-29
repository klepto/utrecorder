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
 * @property lastUpdate The time [status] last changed. [Instant.DISTANT_PAST] for a recording stored before the time
 * was kept.
 * @property views The number of times the recording was requested.
 * @property lastView The time the recording was last requested, or `null` if it never was.
 * @property recordedAt The time the mp4 was stored, or `null` if it was not recorded by the server.
 * @property processingTime The time the last attempt took to produce the mp4, or `null` if it was not recorded by
 * the server.
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

    /** Stored as an mp4 and uploaded to remote storage. */
    Uploaded,

    /** Uploaded, and confirmed by remote storage to hold an mp4 with the same checksum. */
    Archived,

    /** Given up on after its last capture failed. Pending again when a [RecordingRepository] is opened. */
    Failed,

    /**
     * Given up on because its demo names a package that no source holds. Pending again when a [RecordingRepository] is
     * opened.
     */
    Unresolvable,
}
