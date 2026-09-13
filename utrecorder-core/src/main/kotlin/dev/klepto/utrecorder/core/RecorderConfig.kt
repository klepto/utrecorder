package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the recorder needs from its environment.
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
    fun maxRunSeconds(demoDuration: Duration): Duration = demoDuration + maxRunMargin

    /**
     * Returns how long a run playing a demo [demoDuration] long may take before the game is killed: the demo itself,
     * [maxRunMargin] for menu, level load and teardown, and [timeoutGrace] before a hung game is killed.
     */
    fun timeout(demoDuration: Duration): Duration = maxRunSeconds(demoDuration) + timeoutGrace

    companion object {
        /**
         * The name the installation takes within the installs directory, mirroring the game directory of `UtConfig`.
         */
        private const val INSTALLATION_DIRECTORY = "ut99"

        /**
         * How long the game waits for a demo spectator by default.
         */
        private val DEFAULT_STARTUP_TIMEOUT = 90.seconds

        /**
         * How much longer than the demo a run may take by default.
         */
        private val DEFAULT_MAX_RUN_MARGIN = 120.seconds

        /**
         * How much longer than `MaxRunSeconds` a hung game is given by default.
         */
        private val DEFAULT_TIMEOUT_GRACE = 60.seconds

        /**
         * How long the game keeps running after the last demo frame by default.
         */
        private val DEFAULT_EXIT_DELAY = 5.seconds

        /**
         * The configuration a caller that names none works against. Resolved on first use rather than at class load,
         * so that the order in which classes happen to load cannot decide what a process records with.
         */
        val DEFAULT: RecorderConfig by lazy { fromProperties() }

        /**
         * Builds the configuration [properties] holds, against the given [directories].
         */
        fun fromProperties(
            properties: PropertySource = PropertySource.DEFAULT,
            directories: Directories = Directories.DEFAULT,
        ): RecorderConfig =
            RecorderConfig(
                installationDirectory =
                    properties.get("core.installation_directory") { it.toAbsolutePath() }
                        ?: directories.installs[INSTALLATION_DIRECTORY],
                startupTimeout = properties.get("core.startup_timeout") { Duration.parse(it) } ?: DEFAULT_STARTUP_TIMEOUT,
                maxRunMargin = properties.get("core.max_run_margin") { Duration.parse(it) } ?: DEFAULT_MAX_RUN_MARGIN,
                timeoutGrace = properties.get("core.timeout_grace") { Duration.parse(it) } ?: DEFAULT_TIMEOUT_GRACE,
                exitDelay = properties.get("core.exit_delay") { Duration.parse(it) } ?: DEFAULT_EXIT_DELAY,
            )
    }
}
