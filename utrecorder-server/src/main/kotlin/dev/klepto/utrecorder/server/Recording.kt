package dev.klepto.utrecorder.server

import java.time.Instant

/**
 * The mp4 recording of one [Demo], from the first attempt until the mp4 is archived or expired.
 *
 * @property id The local key. `0` for a recording that is not stored yet.
 * @property lane The queue lane the demo is recorded in.
 * @property state The stage of the recording.
 * @property attempts The number of recording attempts made.
 * @property error The message of the last failed attempt, or `null` if none failed.
 * @property file The file name of the mp4 in the cache directory, or `null` if the mp4 is not cached.
 * @property md5 The md5 checksum of the mp4, or `null` until it is recorded.
 * @property archiveId The Internet Archive item identifier, or `null` until the mp4 is archived.
 * @property collection The Internet Archive collection the mp4 was uploaded to, or `null` until it is archived.
 * @property recordedAt The time the mp4 was recorded, or `null` until it is recorded.
 * @property archivedAt The time the mp4 was archived, or `null` until it is archived.
 * @property accessedAt The time the mp4 was last recorded, served or cached again, or `null` if never.
 */
data class Recording(
    val id: Long = 0,
    val lane: RecordingLane,
    val state: RecordingState = RecordingState.PENDING,
    val attempts: Int = 0,
    val error: String? = null,
    val file: String? = null,
    val md5: String? = null,
    val archiveId: String? = null,
    val collection: String? = null,
    val recordedAt: Instant? = null,
    val archivedAt: Instant? = null,
    val accessedAt: Instant? = null,
)

/**
 * Stage of a [Recording].
 *
 * @property value The name the state is stored under.
 */
enum class RecordingState(
    val value: String,
) {
    /** Waiting in the queue for its next attempt. */
    PENDING("pending"),

    /** Being recorded. */
    RECORDING("recording"),

    /** Recorded to a cached mp4 that is not archived yet. */
    RECORDED("recorded"),

    /** Uploaded to the Internet Archive. */
    ARCHIVED("archived"),

    /** Out of attempts. */
    FAILED("failed"),

    /** Removed from the cache without ever being archived. */
    EXPIRED("expired"),
    ;

    companion object {
        /**
         * Returns the state stored under [value].
         */
        fun of(value: String): RecordingState = entries.first { it.value == value }
    }
}

/**
 * Queue lane a [Recording] waits in, chosen by the length of its demo.
 *
 * @property value The name the lane is stored under.
 */
enum class RecordingLane(
    val value: String,
) {
    /** Demos shorter than the long threshold. */
    SHORT("short"),

    /** Demos at least as long as the long threshold. */
    LONG("long"),
    ;

    companion object {
        /**
         * Returns the lane stored under [value].
         */
        fun of(value: String): RecordingLane = entries.first { it.value == value }
    }
}
