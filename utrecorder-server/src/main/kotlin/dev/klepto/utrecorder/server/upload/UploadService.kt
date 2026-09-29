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
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/**
 * [Service] that uploads the mp4 of every [Recorded] recording of [repository] through [uploader], and confirms each
 * upload.
 *
 * It runs two loops side by side:
 * - Once per [ServerConfig.uploadInterval], each [Uploaded] recording whose checksum [uploader] confirms is set to
 *   [Archived], one at a time. One that is still not confirmed 24 hours after it was set to [Uploaded] is set to
 *   [Recorded] again, so it is uploaded again.
 * - The newest [Recorded] recording whose `<capId>.mp4` is in [ServerConfig.recordings] is uploaded into the item named
 *   by the cap id, and set to [Uploaded] with the MD5 checksum of the mp4. If [uploader] already holds a file with that
 *   checksum, the recording is set to [Archived] instead, and the next recording is taken at once. Two uploads start
 *   one day divided by [ServerConfig.dailyUploads] apart. When there is nothing to upload, the loop looks again after
 *   [ServerConfig.uploadInterval].
 *
 * A check or an upload that fails is logged, and the recording is tried again later. A failed upload still takes its
 * place in the daily uploads.
 *
 * @property repository The recordings to upload.
 * @property uploader The remote storage the mp4 files are uploaded to.
 * @property config The recordings directory, the upload interval and the daily uploads.
 * @property clock The source of the time an unconfirmed upload is measured against.
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
     * Checks the [Uploaded] recordings once per [ServerConfig.uploadInterval].
     */
    private suspend fun archiveLoop() {
        while (isRunning()) {
            archiveUploaded()
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
     * Sets [recording] to [Archived] if [uploader] holds a file with its checksum. If it does not, and the recording was
     * set to [Uploaded] at least 24 hours ago, sets it to [Recorded] instead. If the check fails, logs it.
     */
    private suspend fun archive(recording: Recording) {
        val capId = recording.demo.capId
        val md5 = recording.md5 ?: return
        suspendCatching { uploader.isUploaded(capId, md5) }
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
     * Returns the [Recorded] recording of [repository] with the newest demo whose mp4 exists. If there is none, returns
     * `null` instead.
     */
    private suspend fun newestRecorded(): Recording? =
        repository
            .all()
            .filter { it.status == Recorded }
            .sortedByDescending { it.demo.timestamp }
            .firstOrNull { config.mp4(it.demo.capId).fileExists() }

    /**
     * Uploads the mp4 of [recording] and sets it to [Uploaded] with the checksum of the mp4. If [uploader] already holds
     * a file with that checksum, sets [recording] to [Archived] with the checksum instead, and returns `false`. If the
     * check or the upload fails, logs it and returns `true`.
     */
    private suspend fun upload(recording: Recording): Boolean {
        val capId = recording.demo.capId
        return suspendCatching { uploadMp4(recording) }
            .onSuccess { (md5, uploaded) ->
                if (uploaded) {
                    repository.update(capId) { it.copy(status = Uploaded, md5 = md5) }
                    log.info { "Finished uploading: ${recording.demo} (${uploader.downloadUrl(capId, config.mp4(capId).name)})" }
                } else {
                    repository.update(capId) { it.copy(status = Archived, md5 = md5) }
                    log.info { "Archived without uploading, the mp4 is already uploaded: ${recording.demo}" }
                }
            }.onFailure { log.warn(it) { "Could not upload recording $capId" } }
            .map { (_, uploaded) -> uploaded }
            .getOrDefault(true)
    }

    /**
     * Returns the MD5 checksum of the mp4 of [recording], and `true` if the mp4 was uploaded. If [uploader] already
     * holds a file with that checksum, does not upload the mp4 and returns `false`.
     */
    private suspend fun uploadMp4(recording: Recording): Pair<String, Boolean> {
        val capId = recording.demo.capId
        val mp4 = config.mp4(capId)
        val md5 = mp4.digest("MD5")
        if (uploader.isUploaded(capId, md5)) return md5 to false
        log.info { "Uploading: ${recording.demo}" }
        uploader.upload(capId, mp4, metadata(recording.demo))
        return md5 to true
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

        val log = logger<UploadService>()
    }
}
