package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.demo.UtDemo
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * The recorder. Hand it a local demo file and it plays it once in the game and reports the demo-time range the game
 * actually played.
 *
 * @property config What the recorder plays demos with. Kept private: callers configure through [RecorderConfig].
 */
class UtRecorder(
    private val config: RecorderConfig = RecorderConfig.DEFAULT,
) {
    /**
     * Plays the demo file at [demo] at normal speed with sound off and returns the demo-time range the game actually
     * played, as reported by the start and end marks in the run log.
     *
     * Reads the demo up front: a missing or unreadable file, and an unreadable demo, fail before anything is
     * launched. Stages the demo into the installation under a run-unique name, plays it, and removes the staged copy
     * and `UTRecorder.ini` afterwards whichever way the run ends.
     *
     * Marks are the ground truth: a nonzero exit code with both marks present still returns them.
     *
     * @param timeout How long the game may run before it is killed. Derived from the demo just read when `null`.
     * @throws IllegalStateException When the run log lacks either mark, naming the missing mark and carrying the exit
     * code.
     */
    fun playDemo(
        demo: Path,
        timeout: Duration? = null,
    ): DemoMarks {
        val demoDuration = UtDemo.read(demo).duration
        val description = playbackDescription("$STAGED_DEMO_PREFIX${ids.next()}", demoDuration, config)
        val runTimeout = timeout ?: config.timeout(demoDuration)
        val installation = UtInstallation(config.installationDirectory)
        val instance =
            installation.instance("play-demo") {
                exec = listOf(description.execLine)
                sound = false
                windowed = true
                gameIni { this["Engine.Engine", "Console"] = description.console }
            }
        val stagedDemo = installation.systemDirectory[description.stagedDemoName + DEMO_EXTENSION]
        val recorderIni = installation.systemDirectory[RECORDER_INI_NAME]
        try {
            demo.copy(stagedDemo) { "Demo file not found at $demo" }
            recorderIni.writeText(recorderIniText(description), UtIni.CHARSET)
            val result = instance.start().await(runTimeout)
            return try {
                parseMarks(result.log)
            } catch (failure: IllegalStateException) {
                throw IllegalStateException("${failure.message} (exit code ${result.exitCode})", failure)
            }
        } finally {
            stagedDemo.deleteIfExists()
            recorderIni.deleteIfExists()
        }
    }

    /**
     * Returns the contents of the `UTRecorder.ini` the run described by [description] writes.
     */
    private fun recorderIniText(description: PlaybackDescription): String =
        listOf(
            "[$RECORDER_INI_SECTION]",
            "bAutoExit=${iniBoolean(description.autoExit)}",
            "bStopDemoFirst=${iniBoolean(description.stopDemoFirst)}",
            "ExitDelay=${description.exitDelay.inWholeSeconds}",
            "StartupTimeout=${description.startupTimeout.inWholeSeconds}",
            "MaxRunSeconds=${description.maxRunSeconds.inWholeSeconds}",
        ).joinToString("\n", postfix = "\n")

    /**
     * Returns the value an Unreal Tournament configuration file holds for the given [value].
     */
    private fun iniBoolean(value: Boolean): String =
        when {
            value -> "True"
            else -> "False"
        }

    companion object {
        /**
         * The prefix every staged demo name begins with, followed by the id of the run.
         */
        private const val STAGED_DEMO_PREFIX = "ut-"

        /**
         * The extension a staged demo is stored under, so the game reads it from its `System` directory.
         */
        private const val DEMO_EXTENSION = ".dem"

        /**
         * The name of the configuration the run writes next to the staged demo and removes afterwards.
         */
        private const val RECORDER_INI_NAME = "UTRecorder.ini"

        /**
         * The section of `UTRecorder.ini` holding the run, naming the class the values configure.
         */
        private const val RECORDER_INI_SECTION = "UTRecorder.UTRecorderConsole"

        /**
         * The ids of every marks run, so that two runs staging within one second keep their demos apart.
         */
        private val ids = TimestampId()
    }
}
