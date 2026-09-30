package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of the recording backend server.
 *
 * Use the constructor. A property that is not provided takes the value its `server.*` key configures, or else its
 * literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.*` keys and of [directories].
 * @property directories The home directory and the workspace directory that scratch work, such as a download, runs in.
 * @property recordings The directory that holds one `<capId>.mp4` per recorded demo. If not provided, uses the
 * `recordings` subdirectory of [DirectoriesConfig.home].
 * @property database The SQLite database file of the server. If not provided, uses `database/server.db` under
 * [DirectoriesConfig.home].
 * @property uploadInterval How long the upload loop waits between two passes over the uploaded recordings, and between
 * two looks for a recording to upload when there is none.
 * @property dailyUploads The most uploads the upload loop starts in one day, spread evenly across the day.
 * @property cacheTtl How long the mp4 of an archived recording stays in [recordings] after it was last viewed.
 * @property cacheMaxBytes The size in bytes the mp4 files in [recordings] may take before they are deleted down to
 * [cacheTargetBytes]. Configured in gigabytes.
 * @property cacheTargetBytes The size in bytes the mp4 files in [recordings] are deleted down to once they take
 * more than [cacheMaxBytes]. Configured in gigabytes.
 * @property port The port the HTTP server listens on.
 * @property recordingMaxAttempts How many times a demo is recorded before the server gives up on it.
 * @property recordingCooldown How long a recorder waits after each recording.
 * @property recordingRecentAge The age up to which a demo is recent. An older demo is a backlog demo.
 * @property recordingBacklogBuffer The most recordings that may be recording or recorded without being uploaded when a
 * backlog demo is taken.
 * @property utbtApiUrl The base URL of the UTBT API that serves the cap list.
 * @property utbtDemoUrl The base URL a demo downloads from, followed by its cap id.
 * @property utbtCooldown How long the sync waits between two reads of the cap list.
 * @property archiveAccessKey The S3 access key of the Internet Archive account, or `null` if it is not configured.
 * @property archiveSecretKey The S3 secret key of the Internet Archive account, or `null` if it is not configured.
 * @property archiveCollection The Internet Archive collection every uploaded item is placed in.
 * @property archivePrefix The prefix of every Internet Archive item identifier, followed by a hyphen and the item id.
 */
class ServerConfig(
    reader: ConfigReader = SystemConfigReader(),
    val directories: DirectoriesConfig = DirectoriesConfig(reader),
    val recordings: Path = reader.get("server.recordings", directories.home["recordings"], String::toAbsolutePath),
    val database: Path = reader.get("server.database", directories.home["database/server.db"], String::toAbsolutePath),
    val uploadInterval: Duration = reader.duration("server.upload_interval", 1.minutes),
    val dailyUploads: Int = reader.get("server.daily_uploads", 200, String::toInt),
    val cacheTtl: Duration = reader.duration("server.cache_ttl", 7.days),
    val cacheMaxBytes: Long = reader.get("server.cache_max_gigabytes", 100L, String::toLong) * BYTES_PER_GIGABYTE,
    val cacheTargetBytes: Long = reader.get("server.cache_target_gigabytes", 50L, String::toLong) * BYTES_PER_GIGABYTE,
    val port: Int = reader.get("server.port", 8080, String::toInt),
    val recordingMaxAttempts: Int = reader.get("server.recording.max_attempts", 3, String::toInt),
    val recordingCooldown: Duration = reader.duration("server.recording.cooldown", 10.seconds),
    val recordingRecentAge: Duration = reader.duration("server.recording.recent_age", 7.days),
    val recordingBacklogBuffer: Int = reader.get("server.recording.backlog_buffer", 1, String::toInt),
    val utbtApiUrl: String = reader.get("server.utbt.api_url", "https://gateway.utbt.net"),
    val utbtDemoUrl: String = reader.get("server.utbt.demo_url", "http://api.utbt.net/demos"),
    val utbtCooldown: Duration = reader.duration("server.utbt.cooldown", 10.seconds),
    val archiveAccessKey: String? = reader["server.archive.access_key"],
    val archiveSecretKey: String? = reader["server.archive.secret_key"],
    val archiveCollection: String = reader.get("server.archive.collection", "test_collection"),
    val archivePrefix: String = reader.get("server.archive.prefix", "ut99-bt"),
) {
    /**
     * Returns the file in [recordings] that holds the mp4 of the recording [capId].
     */
    fun mp4(capId: String): Path = recordings["$capId.mp4"]

    private companion object {
        const val BYTES_PER_GIGABYTE = 1_073_741_824L
    }
}
