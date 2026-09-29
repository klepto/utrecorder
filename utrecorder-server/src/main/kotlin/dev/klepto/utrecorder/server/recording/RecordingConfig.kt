package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Settings of the recording loop.
 *
 * Use the constructor. A property that is not provided takes the value its `server.recording.*` key configures, or else
 * its literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.recording.*` keys.
 * @property maxAttempts How many times a demo is recorded before the server gives up on it.
 * @property cooldown How long a recorder waits after each recording.
 * @property recentAge The age up to which a demo is recent. An older demo is a backlog demo.
 * @property backlogBuffer The most recordings that may be recording or recorded without being uploaded when a backlog
 * demo is taken.
 */
class RecordingConfig(
    reader: ConfigReader = SystemConfigReader(),
    val maxAttempts: Int = reader.get("server.recording.max_attempts", 3, String::toInt),
    val cooldown: Duration = reader.duration("server.recording.cooldown", 1.minutes),
    val recentAge: Duration = reader.duration("server.recording.recent_age", 7.days),
    val backlogBuffer: Int = reader.get("server.recording.backlog_buffer", 1, String::toInt),
)
