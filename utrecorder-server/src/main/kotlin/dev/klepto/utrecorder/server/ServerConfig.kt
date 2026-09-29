package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.server.demo.UtbtConfig
import dev.klepto.utrecorder.server.recording.RecordingConfig
import dev.klepto.utrecorder.server.upload.ArchiveConfig
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
 * @param reader The source of the keys every nested configuration reads.
 * @property utbt The settings of the UTBT cap list, its cooldown and the demo downloads.
 * @property recording The settings of the recording loop: the attempts, the cooldown, the recent age and the backlog
 * buffer.
 * @property archive The settings of the Internet Archive account: the keys, the collection and the identifier prefix.
 * @property directories The home directory and the workspace directory that scratch work, such as a download, runs in.
 * @property recordings The directory that holds one `<capId>.mp4` per recorded demo. If not provided, uses the
 * `recordings` subdirectory of [DirectoriesConfig.home].
 * @property database The SQLite database file of the server. If not provided, uses `database/server.db` under
 * [DirectoriesConfig.home].
 * @property uploadInterval How long the upload loop waits between two passes over the uploaded recordings, and between
 * two looks for a recording to upload when there is none.
 * @property dailyUploads The most uploads the upload loop starts in one day, spread evenly across the day.
 * @property cacheTtl How long the mp4 of an archived recording stays in [recordings] after it was last viewed.
 */
class ServerConfig(
    reader: ConfigReader = SystemConfigReader(),
    val utbt: UtbtConfig = UtbtConfig(reader),
    val recording: RecordingConfig = RecordingConfig(reader),
    val archive: ArchiveConfig = ArchiveConfig(reader),
    val directories: DirectoriesConfig = DirectoriesConfig(reader),
    val recordings: Path = reader.get("server.recordings", directories.home["recordings"], String::toAbsolutePath),
    val database: Path = reader.get("server.database", directories.home["database/server.db"], String::toAbsolutePath),
    val uploadInterval: Duration = reader.duration("server.upload_interval", 1.minutes),
    val dailyUploads: Int = reader.get("server.daily_uploads", 200, String::toInt),
    val cacheTtl: Duration = reader.duration("server.cache_ttl", 7.days),
)
