package dev.klepto.utrecorder.server.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stage of a [Recording].
 *
 * @property value The name the state is reported under.
 */
@Serializable
enum class RecordingState(
    val value: String,
) {
    /** Waiting in the queue for its next attempt. */
    @SerialName("pending")
    PENDING("pending"),

    /** Being recorded. */
    @SerialName("recording")
    RECORDING("recording"),

    /** Recorded to a cached mp4 that is not archived yet. */
    @SerialName("recorded")
    RECORDED("recorded"),

    /** Uploaded to the Internet Archive. */
    @SerialName("archived")
    ARCHIVED("archived"),

    /** Out of attempts. */
    @SerialName("failed")
    FAILED("failed"),

    /** Removed from the cache without ever being archived. */
    @SerialName("expired")
    EXPIRED("expired"),
}
