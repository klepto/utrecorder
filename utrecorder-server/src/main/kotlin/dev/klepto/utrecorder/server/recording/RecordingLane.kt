package dev.klepto.utrecorder.server.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Queue lane a [Recording] waits in, chosen by the length of its demo.
 */
@Serializable
enum class RecordingLane {
    /** Demos shorter than the long threshold. */
    @SerialName("short")
    SHORT,

    /** Demos at least as long as the long threshold. */
    @SerialName("long")
    LONG,
}
