// The file holds the playback description together with the builder behind it, rather than one declaration.
@file:Suppress("MatchingDeclarationName", "filename")

package dev.klepto.utrecorder.core

import kotlin.time.Duration

/**
 * What one marks run plays and is configured with, derived from the demo it plays so that a test can pin it down
 * without starting the game.
 *
 * @property stagedDemoName The run-unique name the demo is staged under in the installation's `System` directory,
 * without extension.
 * @property execLine The exec script line playing the staged demo.
 * @property console The value the run points `[Engine.Engine] Console` at in its game-ini copy.
 * @property autoExit Whether the run configures `bAutoExit`.
 * @property stopDemoFirst Whether the run configures `bStopDemoFirst`.
 * @property exitDelay The `ExitDelay` the run configures.
 * @property startupTimeout The `StartupTimeout` the run configures.
 * @property maxRunSeconds The `MaxRunSeconds` the run configures.
 */
internal data class PlaybackDescription(
    val stagedDemoName: String,
    val execLine: String,
    val console: String,
    val autoExit: Boolean,
    val stopDemoFirst: Boolean,
    val exitDelay: Duration,
    val startupTimeout: Duration,
    val maxRunSeconds: Duration,
)

/**
 * The console one marks run points `[Engine.Engine] Console` at in its game-ini copy.
 */
private const val RECORDER_CONSOLE = "UTRecorder.UTRecorderConsole"

/**
 * Returns what a run staging the demo as [stagedDemoName] and playing a demo [demoDuration] long plays and is
 * configured with.
 */
internal fun playbackDescription(
    stagedDemoName: String,
    demoDuration: Duration,
    config: RecorderConfig,
): PlaybackDescription =
    PlaybackDescription(
        stagedDemoName = stagedDemoName,
        execLine = "demoplay $stagedDemoName",
        console = RECORDER_CONSOLE,
        autoExit = true,
        stopDemoFirst = true,
        exitDelay = config.exitDelay,
        startupTimeout = config.startupTimeout,
        maxRunSeconds = config.maxRunSeconds(demoDuration),
    )
