@file:UseSerializers(InstantSerializer::class)

package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.database.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * The mp4 recording of one [Demo], from the first attempt until the mp4 is archived or expired.
 *
 * @property demoId The [Demo.id] of the recorded demo.
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
@Serializable
data class Recording(
    val demoId: String,
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
