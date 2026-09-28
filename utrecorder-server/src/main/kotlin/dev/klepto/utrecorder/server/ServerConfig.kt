package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.server.demo.UtbtConfig
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Settings of the recording backend server.
 *
 * Use the constructor. A property that is not provided takes the value its key configures, or else its literal
 * default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the keys every nested configuration reads.
 * @property utbt The settings of the UTBT cap list, its cooldown and the demo downloads.
 * @property recordings The directory that holds one `<capId>.mp4` per recorded demo. If not provided, uses the
 * `recordings` subdirectory of the home directory that [reader] resolves.
 * @property longThreshold The longest cap time of a short demo. A demo with a longer cap time is a long demo.
 * @property maxAttempts How many times a demo is recorded before the server gives up on it.
 * @property cooldown How long a recorder waits after each recording.
 */
class ServerConfig(
    reader: ConfigReader = SystemConfigReader(),
    val utbt: UtbtConfig = UtbtConfig(reader),
    val recordings: Path =
        reader.get("server.recordings", DirectoriesConfig(reader).home["recordings"], String::toAbsolutePath),
    val longThreshold: Duration = reader.duration("server.long_threshold", 5.minutes),
    val maxAttempts: Int = reader.get("server.max_attempts", 3, String::toInt),
    val cooldown: Duration = reader.duration("server.cooldown", 1.minutes),
)
