package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.core.RecorderConfig
import dev.klepto.utrecorder.ut99.UtConfig
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of the recording backend server.
 *
 * Use the constructor. A property that is not provided takes the value its `server.*` key configures, or else its
 * literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.*` keys, of [directories] and of [recorder].
 * @property directories The home directory and the workspace directory that scratch work, such as a download, runs in.
 * @property recorder The configuration demos are recorded with. If not provided, reads [reader] and installs the game
 * under [directories].
 * @property recordings The directory that holds one `<capId>.mp4` per recorded demo. If not provided, uses the
 * `recordings` subdirectory of [DirectoriesConfig.home].
 * @property thumbnails The directory that holds one `<capId>.jpg` per recorded demo. If not provided, uses the
 * `thumbnails` subdirectory of [DirectoriesConfig.home].
 * @property database The SQLite database file of the server. If not provided, uses `database/server.db` under
 * [DirectoriesConfig.home].
 * @property uploadInterval How long the upload loop waits between two passes over the uploaded recordings, and between
 * two looks for a recording to upload when there is none.
 * @property dailyUploads The most uploads the upload loop starts in one day, spread evenly across the day. Also the pace
 * backlog demos are recorded at, together with the recordings of recent demos.
 * @property archiveDelay How long after it was recorded a recording waits before it is uploaded.
 * @property cacheTtl How long the mp4 of an archived recording stays in [recordings] after it was recorded or last
 * viewed, whichever is later, or of an outdated recording after it became outdated.
 * @property cacheMinFreeBytes The usable bytes of the filesystem holding [recordings] below which mp4 files in
 * [recordings] are deleted until it has [cacheTargetFreeBytes]. Configured in gigabytes.
 * @property cacheTargetFreeBytes The usable bytes of the filesystem holding [recordings] that mp4 files in [recordings]
 * are deleted up to once it has less than [cacheMinFreeBytes]. Meant to be at least [cacheMinFreeBytes], which is not
 * enforced. Configured in gigabytes.
 * @property viewFlushInterval How long the views of the recordings are counted in memory before they are written to the
 * store.
 * @property port The port the HTTP server listens on.
 * @property trustForwardedHeaders Whether the HTTP server takes the origin of a request from its `X-Forwarded-*`
 * headers. Enable it only when a proxy in front of the server sets those headers.
 * @property recordingMaxAttempts How many times a demo is recorded before the server gives up on it, and how many server
 * stops during its recording the server takes before it gives up on it.
 * @property recordingCooldown How long a recorder waits after each recording.
 * @property recordingRetryInterval How long the server waits between two passes that record again every recording it gave
 * up on.
 * @property recordingRecentAge The age up to which a demo is recent. An older demo is a backlog demo.
 * @property recordingBacklogBuffer The most recordings that may be recording, or recorded at least [archiveDelay] ago
 * without being uploaded, when a backlog demo is taken.
 * @property recordingMinFreeBytes The usable bytes of the filesystem holding [DirectoriesConfig.workspace] below which
 * no recorder takes a demo. Meant to be less than [cacheMinFreeBytes], which is not enforced. Configured in gigabytes.
 * @property recordingFreeSpaceInterval How long a recorder waits between two reads of the usable bytes while they are
 * below [recordingMinFreeBytes].
 * @property utbtApiUrl The base URL of the UTBT API that serves the cap list.
 * @property utbtDemoUrl The base URL a demo downloads from, followed by its cap id.
 * @property utbtCooldown How long the sync waits between two ticks. Each tick reads up to two pages of the cap list.
 * @property utbtPageLimit The most caps the sync reads in one page of the cap list.
 * @property utbtTailStride The offset of the first page of a tail scan of the cap list, and the step from the offset of
 * each page of the scan to the next.
 * @property utbtTailInterval The time from the start of one tail scan of the cap list to the start of the next.
 * @property archiveAccessKey The S3 access key of the Internet Archive account, or `null` if it is not configured.
 * @property archiveSecretKey The S3 secret key of the Internet Archive account, or `null` if it is not configured.
 * @property archiveCollection The Internet Archive collection every uploaded item is placed in.
 * @property archivePrefix The prefix of every Internet Archive item identifier, followed by a hyphen and the item id.
 */
class ServerConfig(
    reader: ConfigReader = SystemConfigReader(),
    val directories: DirectoriesConfig = DirectoriesConfig(reader),
    val recorder: RecorderConfig = RecorderConfig(reader, UtConfig(reader, directories)),
    val recordings: Path = reader.get("server.recordings", directories.home["recordings"], String::toAbsolutePath),
    val thumbnails: Path = reader.get("server.thumbnails", directories.home["thumbnails"], String::toAbsolutePath),
    val database: Path = reader.get("server.database", directories.home["database/server.db"], String::toAbsolutePath),
    val uploadInterval: Duration = reader.duration("server.upload_interval", 1.minutes),
    val dailyUploads: Int = reader.get("server.daily_uploads", 200, String::toInt),
    val archiveDelay: Duration = reader.duration("server.archive_delay", 2.days),
    val cacheTtl: Duration = reader.duration("server.cache_ttl", 4.days),
    val cacheMinFreeBytes: Long = reader.get("server.cache_min_free_gigabytes", 15L, String::toLong) * BYTES_PER_GIGABYTE,
    val cacheTargetFreeBytes: Long = reader.get("server.cache_target_free_gigabytes", 20L, String::toLong) * BYTES_PER_GIGABYTE,
    val viewFlushInterval: Duration = reader.duration("server.view_flush_interval", 5.minutes),
    val port: Int = reader.get("server.port", 8080, String::toInt),
    val trustForwardedHeaders: Boolean = reader.get("server.trust_forwarded_headers", false, String::toBoolean),
    val recordingMaxAttempts: Int = reader.get("server.recording.max_attempts", 3, String::toInt),
    val recordingCooldown: Duration = reader.duration("server.recording.cooldown", 10.seconds),
    val recordingRetryInterval: Duration = reader.duration("server.recording.retry_interval", 12.hours),
    val recordingRecentAge: Duration = reader.duration("server.recording.recent_age", 1.days),
    val recordingBacklogBuffer: Int = reader.get("server.recording.backlog_buffer", 20, String::toInt),
    val recordingMinFreeBytes: Long = reader.get("server.recording.min_free_gigabytes", 5L, String::toLong) * BYTES_PER_GIGABYTE,
    val recordingFreeSpaceInterval: Duration = reader.duration("server.recording.free_space_interval", 1.minutes),
    val utbtApiUrl: String = reader.get("server.utbt.api_url", "https://gateway.utbt.net"),
    val utbtDemoUrl: String = reader.get("server.utbt.demo_url", "http://api.utbt.net/demos"),
    val utbtCooldown: Duration = reader.duration("server.utbt.cooldown", 1.minutes),
    val utbtPageLimit: Int = reader.get("server.utbt.page_limit", 1000, String::toInt),
    val utbtTailStride: Int = reader.get("server.utbt.tail_stride", 500, String::toInt),
    val utbtTailInterval: Duration = reader.duration("server.utbt.tail_interval", 3.days),
    val archiveAccessKey: String? = reader["server.archive.access_key"],
    val archiveSecretKey: String? = reader["server.archive.secret_key"],
    val archiveCollection: String = reader.get("server.archive.collection", "test_collection"),
    val archivePrefix: String = reader.get("server.archive.prefix", "ut99-bt"),
) {
    /**
     * Returns the file in [recordings] that holds the mp4 of the recording [capId].
     */
    fun mp4(capId: String): Path = recordings["$capId.mp4"]

    /**
     * Returns the file in [thumbnails] that holds the thumbnail of the recording [capId].
     */
    fun thumbnail(capId: String): Path = thumbnails["$capId.jpg"]

    private companion object {
        const val BYTES_PER_GIGABYTE = 1_073_741_824L
    }
}
