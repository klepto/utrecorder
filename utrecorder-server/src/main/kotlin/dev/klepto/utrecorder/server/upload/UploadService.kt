package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.io.digest
import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/**
 * [Service] that uploads the mp4 of every [Recorded] recording of [repository] through [uploader], confirms each
 * upload, and deletes the files of beaten recordings.
 *
 * Every recording of one player on one map is uploaded into the same item, named `<userId>-<mapHash>-<mapSlug>`, under
 * the file name `<capId>.mp4`. `<mapHash>` is the first 8 hexadecimal digits of the SHA-256 of the map name in UTF-8,
 * `<mapSlug>` is the map name in lowercase, and in `<userId>` and `<mapSlug>` every character outside `[a-z0-9._-]` is
 * replaced by `_`.
 *
 * It runs two loops side by side:
 * - Once per [ServerConfig.uploadInterval], each [Uploaded] recording whose checksum [uploader] confirms is set to
 *   [Archived], one at a time. One that is still not confirmed 24 hours after it was set to [Uploaded] is set to
 *   [Recorded] again, so it is uploaded again. Then, for every [Archived] recording, the file of each [Outdated]
 *   recording of the same item is deleted, and the [Recording.itemId] of that recording is cleared.
 * - The newest [Recorded] recording whose `<capId>.mp4` is in [ServerConfig.recordings], and that was recorded at least
 *   [ServerConfig.archiveDelay] ago, is uploaded into its item, the item is described, and the recording is set to
 *   [Uploaded] with the MD5 checksum of the mp4 and the item id. If [uploader] already holds a file with that checksum,
 *   the mp4 is not uploaded, the recording is set to [Archived] instead, and the next recording is taken at once. A
 *   recording that became [Outdated] during its upload keeps its status and gets only the checksum and the item id. Two
 *   uploads start one day divided by [ServerConfig.dailyUploads] apart. When there is nothing to upload, the loop looks
 *   again after [ServerConfig.uploadInterval].
 *
 * A check, an upload, a description or a delete that fails is logged, and is tried again later. A failed upload still
 * takes its place in the daily uploads.
 *
 * @property repository The recordings to upload.
 * @property uploader The remote storage the mp4 files are uploaded to.
 * @property config The recordings directory, the upload interval, the daily uploads and the archive delay.
 * @property clock The source of the time an unconfirmed upload and the archive delay are measured against.
 */
class UploadService(
    private val repository: RecordingRepository,
    private val uploader: MediaUploader,
    private val config: ServerConfig = ServerConfig(),
    private val clock: Clock = Clock.System,
) : Service {
    override suspend fun start() {
        coroutineScope {
            launch { archiveLoop() }
            uploadLoop()
        }
    }

    /**
     * Checks the [Uploaded] recordings, and deletes the files of the [Outdated] ones, once per
     * [ServerConfig.uploadInterval].
     */
    private suspend fun archiveLoop() {
        while (isRunning()) {
            archiveUploaded()
            deleteOutdated()
            delay(config.uploadInterval)
        }
    }

    /**
     * Uploads the newest recorded mp4, one at a time. Waits one day divided by [ServerConfig.dailyUploads] from the start
     * of an upload to the start of the next. If there is nothing to upload, waits [ServerConfig.uploadInterval] instead.
     */
    private suspend fun uploadLoop() {
        while (isRunning()) {
            val start = TimeSource.Monotonic.markNow()
            val wait = uploadNewest()
            delay(wait - start.elapsedNow())
        }
    }

    /**
     * Returns how long after its start the next upload may start, after uploading the newest recorded mp4. If there is
     * nothing to upload, returns [ServerConfig.uploadInterval] instead. If [uploader] already held the mp4, returns
     * [Duration.ZERO].
     */
    private suspend fun uploadNewest(): Duration =
        when (val recording = newestRecorded()) {
            null -> config.uploadInterval
            else -> if (upload(recording)) 1.days / config.dailyUploads else Duration.ZERO
        }

    /**
     * Sets every [Uploaded] recording of [repository] that [uploader] confirms to [Archived], one at a time.
     */
    private suspend fun archiveUploaded() {
        repository
            .all()
            .filter { it.status == Uploaded }
            .forEach { archive(it) }
    }

    /**
     * Sets [recording] to [Archived] if [uploader] holds a file with its checksum in its item. If it does not, and the
     * recording was set to [Uploaded] at least 24 hours ago, sets it to [Recorded] instead. If the check fails, logs it.
     */
    private suspend fun archive(recording: Recording) {
        val capId = recording.demo.capId
        val md5 = recording.md5 ?: return
        val itemId = recording.itemId ?: return
        suspendCatching { uploader.isUploaded(itemId, md5) }
            .onSuccess { if (it) setArchived(recording) else expire(recording) }
            .onFailure { log.warn(it) { "Could not check the upload of recording $capId" } }
    }

    private suspend fun setArchived(recording: Recording) {
        repository.finish(recording.demo.capId, Archived)
        log.info { "Archived: ${recording.demo}" }
    }

    /**
     * Sets [recording] to [Recorded] if it was set to [Uploaded] at least 24 hours ago.
     */
    private suspend fun expire(recording: Recording) {
        if (clock.now() - recording.lastUpdate < CONFIRMATION_TIMEOUT) return
        log.warn { "Uploading recording ${recording.demo.capId} again, its upload was not confirmed in $CONFIRMATION_TIMEOUT" }
        repository.finish(recording.demo.capId, Recorded)
    }

    /**
     * Deletes the file of every [Outdated] recording of [repository] whose item holds an [Archived] recording, one at a
     * time.
     */
    private suspend fun deleteOutdated() {
        val recordings = repository.all()
        val archived = recordings.filter { it.status == Archived }.mapNotNull { it.itemId }.toSet()
        recordings
            .filter { it.status == Outdated && it.itemId in archived }
            .forEach { delete(it) }
    }

    /**
     * Deletes the file of [recording] from its item, and clears its [Recording.itemId]. If the delete fails, logs it.
     */
    private suspend fun delete(recording: Recording) {
        val capId = recording.demo.capId
        val itemId = recording.itemId ?: return
        suspendCatching { uploader.delete(itemId, config.mp4(capId).name) }
            .onSuccess {
                repository.update(capId) { if (it.itemId == itemId) it.copy(itemId = null) else it }
                log.info { "Deleted the outdated upload: ${recording.demo}" }
            }.onFailure { log.warn(it) { "Could not delete the outdated upload of recording $capId" } }
    }

    /**
     * Returns the [Recorded] recording of [repository] with the newest demo whose mp4 exists and whose archive delay has
     * passed. If there is none, returns `null` instead.
     */
    private suspend fun newestRecorded(): Recording? =
        repository
            .all()
            .filter { it.status == Recorded && it.demo.userId != null && isDue(it) }
            .sortedByDescending { it.demo.timestamp }
            .firstOrNull { config.mp4(it.demo.capId).fileExists() }

    /**
     * Returns `true` if [recording] was recorded at least [ServerConfig.archiveDelay] ago, or has no
     * [Recording.recordedAt].
     */
    private fun isDue(recording: Recording): Boolean {
        val recordedAt = recording.recordedAt ?: return true
        return recordedAt + config.archiveDelay <= clock.now()
    }

    /**
     * Uploads the mp4 of [recording] into its item, describes the item, and sets [recording] to [Uploaded] with the
     * checksum of the mp4 and the item id. If [uploader] already holds a file with that checksum, sets [recording] to
     * [Archived] instead, and returns `false`. If [recording] is no longer [Recorded], stores only the checksum and the
     * item id. If any step fails, logs it and returns `true`.
     */
    private suspend fun upload(recording: Recording): Boolean {
        val capId = recording.demo.capId
        val itemId = itemId(recording.demo)
        return suspendCatching { uploadMp4(recording, itemId) }
            .onSuccess { (md5, uploaded) ->
                val status = if (uploaded) Uploaded else Archived
                repository.update(capId) {
                    if (it.status == Recorded) {
                        it.copy(status = status, md5 = md5, itemId = itemId)
                    } else {
                        it.copy(md5 = md5, itemId = itemId)
                    }
                }
                if (uploaded) {
                    log.info { "Finished uploading: ${recording.demo} (${uploader.downloadUrl(itemId, config.mp4(capId).name)})" }
                } else {
                    log.info { "Archived without uploading, the mp4 is already uploaded: ${recording.demo}" }
                }
            }.onFailure { log.warn(it) { "Could not upload recording $capId" } }
            .map { (_, uploaded) -> uploaded }
            .getOrDefault(true)
    }

    /**
     * Returns the MD5 checksum of the mp4 of [recording], and `true` if the mp4 was uploaded into the item [itemId].
     * If [uploader] already holds a file with that checksum, does not upload the mp4 and returns `false`. Describes the
     * item either way.
     */
    private suspend fun uploadMp4(
        recording: Recording,
        itemId: String,
    ): Pair<String, Boolean> {
        val mp4 = config.mp4(recording.demo.capId)
        val md5 = mp4.digest("MD5")
        val metadata = metadata(recording.demo)
        val uploaded = !uploader.isUploaded(itemId, md5)
        if (uploaded) {
            log.info { "Uploading: ${recording.demo}" }
            uploader.upload(itemId, mp4, metadata)
        }
        uploader.describe(itemId, metadata)
        return md5 to uploaded
    }

    /**
     * Returns the title, description and tags that describe the upload of [demo].
     */
    private fun metadata(demo: Demo): MediaMetadata =
        MediaMetadata(
            title = "${demo.map} - ${demo.formatCapTime()} by ${demo.player}",
            description =
                """
                Unreal Tournament 99 Speedrunning
                Player: ${demo.player}
                Map: ${demo.map}
                Time: ${demo.formatCapTime()}
                """.trimIndent(),
            tags = listOf("Video Games", "Unreal Tournament 99", "Speedrunning", "Bunny Track"),
        )

    private companion object {
        val CONFIRMATION_TIMEOUT = 24.hours

        val UNSAFE = Regex("[^a-z0-9._-]")

        val log = logger<UploadService>()

        /**
         * Returns the id of the item every recording of the player and map of [demo] is uploaded into.
         *
         * @throws IllegalStateException If [demo] has no [Demo.userId].
         */
        fun itemId(demo: Demo): String {
            val userId = checkNotNull(demo.userId) { "Demo ${demo.capId} has no user id" }
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(demo.map.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(8)
            return "${userId.replace(UNSAFE, "_")}-$hash-${demo.map.lowercase().replace(UNSAFE, "_")}"
        }
    }
}
