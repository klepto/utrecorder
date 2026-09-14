package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.ut99.UtIni
import kotlin.time.Duration

/**
 * Values of one demo run: the demo it stages, the console it plays through and the `UTRecorder.ini` settings it writes.
 *
 * Use [of] to derive one from a demo duration and a [RecorderConfig].
 *
 * @property stagedDemoName The name the demo is staged under in the installation's `System` directory, without
 * extension.
 * @property execLine The exec script line playing the staged demo.
 * @property console The value the run sets `[Engine.Engine] Console` to in its game-ini copy.
 * @property exitDelay The `ExitDelay` the run configures.
 * @property startupTimeout The `StartupTimeout` the run configures.
 * @property maxRunTime The `MaxRunSeconds` the run configures.
 */
data class PlaybackDescription(
    val stagedDemoName: String,
    val execLine: String,
    val console: String,
    val exitDelay: Duration,
    val startupTimeout: Duration,
    val maxRunTime: Duration,
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
         * Returns the description of a run staging its demo as [stagedDemoName] and playing a demo [demoDuration] long
         * with the given [config].
         */
        fun of(
            stagedDemoName: String,
            demoDuration: Duration,
            config: RecorderConfig,
        ): PlaybackDescription =
            PlaybackDescription(
                stagedDemoName = stagedDemoName,
                execLine = "demoplay $stagedDemoName",
                console = RECORDER_CONSOLE,
                exitDelay = config.exitDelay,
                startupTimeout = config.startupTimeout,
                maxRunTime = config.maxRunTime(demoDuration),
            )
    }
}
