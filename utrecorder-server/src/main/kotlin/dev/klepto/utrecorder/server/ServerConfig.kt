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

/**
 * Settings of the recording backend server.
 *
 * Use the constructor. A property that is not provided takes the value its key configures, or else its literal
 * default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.*` keys and of the keys every nested configuration reads.
 * @property directories The application directories the database and the cache directory default to.
 * @property port The HTTP port the server listens on.
 * @property database The SQLite database file.
 * @property cache The directory of recorded mp4 files.
 * @property longThreshold The demo duration from which a demo is recorded in the long lane.
 * @property maxAttempts The number of recording attempts after which a recording fails for good.
 * @property cacheTtl The time a cached mp4 is kept without being accessed.
 * @property pollInterval The time between two polls of the demo source.
 * @property backfillCooldown The time between two backfill requests to the demo source.
 * @property sourceUrl The base URL of the API that lists new demos.
 * @property demoUrl The base URL demo files are downloaded from.
 * @property archive The settings of the Internet Archive uploader.
 */
class ServerConfig(
    reader: ConfigReader = SystemConfigReader(),
    val directories: DirectoriesConfig = DirectoriesConfig(reader),
    val port: Int = reader.get("server.port", 8080, String::toInt),
    val database: Path = reader.get("server.database", directories.home["server.db"], String::toAbsolutePath),
    val cache: Path = reader.get("server.cache", directories.home["recordings"], String::toAbsolutePath),
    val longThreshold: Duration = reader.duration("server.long_threshold", 5.minutes),
    val maxAttempts: Int = reader.get("server.max_attempts", 3, String::toInt),
    val cacheTtl: Duration = reader.duration("server.cache_ttl", 30.days),
    val pollInterval: Duration = reader.duration("server.poll_interval", 10.minutes),
    val backfillCooldown: Duration = reader.duration("server.backfill_cooldown", 5.minutes),
    val sourceUrl: String = reader.get("server.source_url", "https://gateway.utbt.net"),
    val demoUrl: String = reader.get("server.demo_url", "http://api.utbt.net/demos"),
    val archive: ArchiveConfig = ArchiveConfig(reader),
)

/**
 * Settings of the Internet Archive uploader.
 *
 * Use the constructor. A property that is not provided takes the value its key configures, or else its literal
 * default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.archive.*` keys.
 * @property accessKey The S3 access key of the Internet Archive account, or `null` if none is configured.
 * @property secretKey The S3 secret key of the Internet Archive account, or `null` if none is configured.
 * @property collection The collection mp4 files are uploaded to.
 * @property prefix The prefix of every item identifier.
 */
class ArchiveConfig(
    reader: ConfigReader = SystemConfigReader(),
    val accessKey: String? = reader["server.archive.access_key"],
    val secretKey: String? = reader["server.archive.secret_key"],
    val collection: String = reader.get("server.archive.collection", "test_collection"),
    val prefix: String = reader.get("server.archive.prefix", "utrecorder"),
) {
    /**
     * Whether both keys are configured, so the uploader runs.
     */
    val enabled: Boolean
        get() = accessKey != null && secretKey != null
}
