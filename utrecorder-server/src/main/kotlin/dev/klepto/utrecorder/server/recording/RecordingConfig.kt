package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Settings of the recording loop.
 *
 * Use the constructor. A property that is not provided takes the value its `server.recording.*` key configures, or else
 * its literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.recording.*` keys.
 * @property longThreshold The longest cap time of a short demo. A demo with a longer cap time is a long demo.
 * @property maxAttempts How many times a demo is recorded before the server gives up on it.
 * @property cooldown How long a recorder waits after each recording.
 */
class RecordingConfig(
    reader: ConfigReader = SystemConfigReader(),
    val longThreshold: Duration = reader.duration("server.recording.long_threshold", 5.minutes),
    val maxAttempts: Int = reader.get("server.recording.max_attempts", 3, String::toInt),
    val cooldown: Duration = reader.duration("server.recording.cooldown", 1.minutes),
)
