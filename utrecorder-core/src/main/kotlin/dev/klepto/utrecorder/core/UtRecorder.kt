package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.demo.UtDemo
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration

/**
 * Recorder playing local demo files in an Unreal Tournament installation and reporting the demo-time range each run
 * played, and recording a demo into an mp4 through two passes and a mux.
 *
 * Use [playDemo] for a single-pass playback whose marks a caller reads, and [record] for the full recording.
 *
 * @property config The configuration demos are played and recorded with.
 */
class UtRecorder(
    private val config: RecorderConfig = RecorderConfig.DEFAULT,
) {
    /**
     * Plays the demo file at [demo] at normal speed with sound off and returns the demo-time range the game played, as
     * reported by the start and end marks in the run log.
     *
     * Reads the demo first, so a missing file and an unreadable demo fail before anything is launched. The staged copy
     * of the demo and `UTRecorder.ini` are removed afterwards whichever way the run ends. A nonzero exit code with both
     * marks present still returns them.
     *
     * @param timeout How long the game may run before it is killed. Derived from the demo just read when `null`.
     * @throws IllegalStateException when the run log lacks either mark, naming the missing mark and carrying the exit
     * code.
     */
    fun playDemo(
        demo: Path,
        timeout: Duration? = null,
    ): DemoMarks {
        val demoDuration = UtDemo.read(demo).duration
        val description = PlaybackDescription.of("$STAGED_DEMO_PREFIX${ids.next()}", demoDuration, config)
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
            description.recorderIni().write(recorderIni)
            val result = instance.start().await(runTimeout)
            return try {
                DemoMarks.parse(result.log)
            } catch (failure: IllegalStateException) {
                throw IllegalStateException("${failure.message} (exit code ${result.exitCode})", failure)
            }
        } finally {
            stagedDemo.deleteIfExists()
            recorderIni.deleteIfExists()
        }
    }

    /**
     * Records the demo at [url] and returns the mp4 produced, downloading it into the demo cache and provisioning the
     * installation when either is missing, so a URL alone is enough on a fresh machine.
     *
     * The two passes and the mux run inside a recording workspace; the finished mp4 is moved out to the output
     * directory before the workspace closes, and its diagnostics survive a throw. One recording per installation runs
     * at a time.
     *
     * @throws IllegalStateException when the demo cannot be fetched or resolved, or when a pass fails the recording,
     * which a failed sound pass always does.
     */
    fun record(url: String): Path = TODO()

    /**
     * Records the demo file at [demo] and returns the mp4 produced, provisioning the installation when it is missing.
     *
     * The primitive [record] calls with the demo it fetched; the two passes and the mux run inside a recording
     * workspace, and the finished mp4 is moved out to the output directory before the workspace closes.
     *
     * @throws IllegalStateException when the demo cannot be resolved, or when a pass fails the recording, which a
     * failed sound pass always does.
     */
    fun record(demo: Path): Path = TODO()

    companion object {
        private const val STAGED_DEMO_PREFIX = "ut-"
        private const val DEMO_EXTENSION = ".dem"
        private const val RECORDER_INI_NAME = "UTRecorder.ini"

        private val ids = TimestampId()
    }
}
