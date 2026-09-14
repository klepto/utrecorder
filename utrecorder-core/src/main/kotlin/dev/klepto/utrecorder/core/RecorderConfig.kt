package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.ut99.UtConfig
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration of a [UtRecorder]: the installation it plays demos in and the time limits of a run.
 *
 * Use [DEFAULT] for the configuration the current process works against, or [fromProperties] to build one from a
 * given [PropertySource].
 *
 * @property installationDirectory The Unreal Tournament installation demos are played in, holding the compiled
 * `UTRecorder.u` package.
 * @property startupTimeout How long after startup the game waits for a demo spectator before quitting, catching a bad
 * demo name. Written as `StartupTimeout`.
 * @property maxRunMargin How much longer than the demo itself a run may take, covering menu, level load and teardown.
 * Written as `MaxRunSeconds` together with the demo duration.
 * @property timeoutGrace How much longer than `MaxRunSeconds` a hung game is given before it is killed.
 * @property exitDelay How long the game keeps running after the demo hits its last frame. Written as `ExitDelay`.
 */
data class RecorderConfig(
    val installationDirectory: Path,
    val startupTimeout: Duration = DEFAULT_STARTUP_TIMEOUT,
    val maxRunMargin: Duration = DEFAULT_MAX_RUN_MARGIN,
    val timeoutGrace: Duration = DEFAULT_TIMEOUT_GRACE,
    val exitDelay: Duration = DEFAULT_EXIT_DELAY,
) {
    /**
     * Returns the `MaxRunSeconds` a run playing a demo [demoDuration] long is configured with.
     */
    fun maxRunTime(demoDuration: Duration): Duration = demoDuration + maxRunMargin

    /**
     * Returns how long a run playing a demo [demoDuration] long may take before the game is killed.
     */
    fun timeout(demoDuration: Duration): Duration = maxRunTime(demoDuration) + timeoutGrace

    companion object {
        private val DEFAULT_STARTUP_TIMEOUT = 90.seconds
        private val DEFAULT_MAX_RUN_MARGIN = 120.seconds
        private val DEFAULT_TIMEOUT_GRACE = 60.seconds
        private val DEFAULT_EXIT_DELAY = 5.seconds

        /**
         * The configuration a caller that names none works against.
         */
        val DEFAULT: RecorderConfig by lazy { fromProperties() }

        /**
         * Returns the configuration [properties] holds, falling back to the Unreal Tournament installation under the
         * installs directory of [directories].
         */
        fun fromProperties(
            properties: PropertySource = PropertySource.DEFAULT,
            directories: Directories = Directories.DEFAULT,
        ): RecorderConfig =
            RecorderConfig(
                installationDirectory =
                    properties.get("core.installation_directory") { it.toAbsolutePath() }
                        ?: directories.installs[UtConfig.GAME_DIRECTORY],
                startupTimeout = properties.duration("core.startup_timeout") ?: DEFAULT_STARTUP_TIMEOUT,
                maxRunMargin = properties.duration("core.max_run_margin") ?: DEFAULT_MAX_RUN_MARGIN,
                timeoutGrace = properties.duration("core.timeout_grace") ?: DEFAULT_TIMEOUT_GRACE,
                exitDelay = properties.duration("core.exit_delay") ?: DEFAULT_EXIT_DELAY,
            )
    }
}
