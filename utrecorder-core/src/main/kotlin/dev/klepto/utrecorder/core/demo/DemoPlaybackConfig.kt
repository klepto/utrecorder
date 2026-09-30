package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtPackage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of one run that plays a demo through the recorder console.
 *
 * Use [of] to create one from a demo file name, a demo duration, the packages the demo loads, its frame interval and
 * the demo times its run starts and stops at.
 *
 * @property demoFileName The file name of the demo in the installation's `System` directory, without extension.
 * @property demoDuration How long the demo runs.
 * @property packages The packages the demo loads.
 * @property demoFrameInterval The median demo time between two demo frames, or `null` if the demo does not show one.
 * @property runStart The demo time the run the demo records starts at, or `null` if the demo does not show one.
 * @property runStop The demo time the Bunny Track timer of the run stops at, or `null` if the demo does not show one.
 * @property execLine The exec script line playing the demo.
 * @property console The console class the run sets `[Engine.Engine] Console` to.
 * @property exitDelay The `ExitDelay` the run writes to `UTRecorder.ini`.
 * @property startupTimeout The `StartupTimeout` the run writes to `UTRecorder.ini`.
 * @property maxRunTime The `MaxRunSeconds` the run writes to `UTRecorder.ini`.
 * @property timeout How long the run may take before the game is killed.
 */
data class DemoPlaybackConfig(
    val demoFileName: String,
    val demoDuration: Duration,
    val packages: List<UtPackage>,
    val demoFrameInterval: Duration?,
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
     * Returns the `UTRecorder.ini` configuring this run, with every duration in whole seconds.
     */
    fun recorderIni(): UtIni =
        UtIni("[$RECORDER_CONSOLE]\n").apply {
            this[RECORDER_CONSOLE, "bAutoExit"] = "True"
            this[RECORDER_CONSOLE, "bStopDemoFirst"] = "True"
            this[RECORDER_CONSOLE, "ExitDelay"] = exitDelay.inWholeSeconds.toString()
            this[RECORDER_CONSOLE, "StartupTimeout"] = startupTimeout.inWholeSeconds.toString()
            this[RECORDER_CONSOLE, "MaxRunSeconds"] = maxRunTime.inWholeSeconds.toString()
        }

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
        private val EXIT_DELAY = 5.seconds

        /**
         * Returns the settings of a run that plays the demo file [demoFileName], which is [demoDuration] long, loads
         * [packages], holds demo frames [demoFrameInterval] apart and records a run that starts at [runStart] and
         * stops at [runStop].
         */
        fun of(
            demoFileName: String,
            demoDuration: Duration,
            packages: List<UtPackage> = emptyList(),
            demoFrameInterval: Duration? = null,
            runStart: Duration? = null,
            runStop: Duration? = null,
        ): DemoPlaybackConfig =
            DemoPlaybackConfig(
                demoFileName = demoFileName,
                demoDuration = demoDuration,
                packages = packages,
                demoFrameInterval = demoFrameInterval,
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
