package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.demo.Demo
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Returns `true` if the demo of this recording was a backlog demo when it was recorded: [Recording.recordedAt] minus
 * [Demo.timestamp] is more than [recentAge]. A recording without [Recording.recordedAt] is never a backlog recording.
 */
fun Recording.isBacklog(recentAge: Duration): Boolean {
    val recordedAt = recordedAt ?: return false
    return recordedAt - demo.timestamp > recentAge
}

/**
 * Returns `true` if the upload of this recording is due at [now]: it has no [Recording.recordedAt], or it is a backlog
 * recording as [isBacklog] with [ServerConfig.recordingRecentAge] says, or [Recording.recordedAt] plus
 * [ServerConfig.archiveDelay] is not after [now]. The status of the recording is not checked.
 */
fun Recording.isUploadDue(
    config: ServerConfig,
    now: Instant,
): Boolean {
    val recordedAt = recordedAt ?: return true
    return isBacklog(config.recordingRecentAge) || recordedAt + config.archiveDelay <= now
}
