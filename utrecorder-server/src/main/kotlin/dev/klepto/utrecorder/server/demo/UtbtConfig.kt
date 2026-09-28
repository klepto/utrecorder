package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Settings of the UTBT cap list and demo downloads.
 *
 * Use the constructor. A property that is not provided takes the value its `server.utbt.*` key configures, or else its
 * literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.utbt.*` keys.
 * @property apiUrl The base URL of the UTBT API that serves the cap list.
 * @property demoUrl The base URL a demo downloads from, followed by its cap id.
 * @property cooldown How often the fetcher polls offset 0 for new demos, and how long it waits when it has no demo to emit.
 */
class UtbtConfig(
    reader: ConfigReader = SystemConfigReader(),
    val apiUrl: String = reader.get("server.utbt.api_url", "https://gateway.utbt.net"),
    val demoUrl: String = reader.get("server.utbt.demo_url", "http://api.utbt.net/demos"),
    val cooldown: Duration = reader.duration("server.utbt.cooldown", 1.minutes),
)
