package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.ut99.UtPackage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of one run that plays a demo through the recorder console.
 *
 * Use [of] to create one from a demo file name, a demo duration, the packages the demo loads and the demo times its
 * run starts and stops at.
 *
 * @property demoFileName The file name of the demo in the installation's `System` directory, without extension.
 * @property demoDuration How long the demo runs.
 * @property packages The packages the demo loads.
 * @property runStart The demo time the run the demo records starts at, or `null` if the demo does not show one.
 * @property runStop The demo time the Bunny Track timer of the run stops at, or `null` if the demo does not show one.
 * @property execLine The exec script line playing the demo.
 * @property console The console class the run sets `[Engine.Engine] Console` to.
 * @property exitDelay The `ExitDelay` the run sets for the recorder console.
 * @property startupTimeout The `StartupTimeout` the run sets for the recorder console.
 * @property maxRunTime The `MaxRunSeconds` the run sets for the recorder console.
 * @property timeout How long the run may take before the game is killed.
 */
data class DemoPlaybackConfig(
    val demoFileName: String,
    val demoDuration: Duration,
    val packages: List<UtPackage>,
    val runStart: Duration?,
    val runStop: Duration?,
    val execLine: String,
    val console: String,
    val exitDelay: Duration,
    val startupTimeout: Duration,
    val maxRunTime: Duration,
    val timeout: Duration,
) {
    /**
     * Returns how long the run the demo records lasts in demo time, from [runStart] to [runStop]. If the demo does not
     * show one of them, the start or the end of the demo stands in for it.
     */
    fun runDuration(): Duration = (runStop ?: demoDuration) - (runStart ?: Duration.ZERO)

    /**
     * Returns the game ini values of the run by section: [console] as the console of the engine, and the settings of
     * the recorder console in its own section, with every duration in whole seconds.
     */
    fun gameIniValues(): Map<String, Map<String, String>> =
        mapOf(
            "Engine.Engine" to mapOf("Console" to console),
            RECORDER_CONSOLE to
                mapOf(
                    "bAutoExit" to "True",
                    "bStopDemoFirst" to "True",
                    "ExitDelay" to exitDelay.inWholeSeconds.toString(),
                    "StartupTimeout" to startupTimeout.inWholeSeconds.toString(),
                    "MaxRunSeconds" to maxRunTime.inWholeSeconds.toString(),
                ),
        )

    companion object {
        private const val RECORDER_CONSOLE = "UTRecorder.UTRecorderConsole"

        /**
         * How long after startup the game waits for a demo spectator before quitting.
         */
        val STARTUP_TIMEOUT: Duration = 90.seconds

        /**
         * How much longer than the demo itself a run may take, covering menu, level load and teardown.
         */
        private val MAX_RUN_MARGIN = 120.seconds

        /**
         * How much longer than the maximum run time a hung game is given before it is killed.
         */
        private val TIMEOUT_GRACE = 60.seconds

        /**
         * How long the game keeps running after the demo hits its last frame.
         */
        private val EXIT_DELAY = Duration.ZERO

        /**
         * Returns the settings of a run that plays the demo file [demoFileName], which is [demoDuration] long, loads
         * [packages] and records a run that starts at [runStart] and stops at [runStop].
         */
        fun of(
            demoFileName: String,
            demoDuration: Duration,
            packages: List<UtPackage> = emptyList(),
            runStart: Duration? = null,
            runStop: Duration? = null,
        ): DemoPlaybackConfig =
            DemoPlaybackConfig(
                demoFileName = demoFileName,
                demoDuration = demoDuration,
                packages = packages,
                runStart = runStart,
                runStop = runStop,
                execLine = "demoplay $demoFileName",
                console = RECORDER_CONSOLE,
                exitDelay = EXIT_DELAY,
                startupTimeout = STARTUP_TIMEOUT,
                maxRunTime = demoDuration + MAX_RUN_MARGIN,
                timeout = demoDuration + MAX_RUN_MARGIN + TIMEOUT_GRACE,
            )
    }
}
