package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtInstaller
import dev.klepto.utrecorder.ut99.UtMapInstaller
import dev.klepto.utrecorder.ut99.UtPackageException
import dev.klepto.utrecorder.ut99.UtPackageInstaller
import dev.klepto.utrecorder.ut99.demo.UtDemo
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.time.Duration

/**
 * Recorder playing local demo files in an Unreal Tournament installation and reporting the demo-time range each run
 * played, and recording a demo into an mp4 through two passes and a mux.
 *
 * Use [playDemo] for a single-pass playback whose marks a caller reads, and [record] for the full recording.
 *
 * @property config The configuration demos are played and recorded with.
 * @property directories The directories the demo cache, the workspaces and the output live under.
 */
class UtRecorder(
    private val config: RecorderConfig = RecorderConfig.DEFAULT,
    private val directories: Directories = Directories.DEFAULT,
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
    fun record(url: String): Path {
        val demo = DemoFetcher(directories).fetch(url)
        return record(demo)
    }

    /**
     * Records the demo file at [demo] and returns the mp4 produced, provisioning the installation and decompressing
     * its maps when either is missing, fetching the packages the demo names into it, and naming the mp4 [name] instead
     * of the demo's own.
     *
     * The primitive the URL [record] calls with the demo it fetched and the mp4 name the URL sanitises; the demo is
     * read once and every package it names is fetched into the installation at the GUID the demo recorded, so playback
     * resolves every package the demo loads, and the two passes and the mux run inside a recording workspace, the
     * finished mp4 moved out to the output directory before the workspace closes.
     *
     * @throws IllegalStateException when the demo cannot be resolved, or when a pass fails the recording, which a
     * failed sound pass always does.
     * @throws UtPackageException when no source serves one of the packages the demo names at the GUID it recorded.
     */
    fun record(
        demo: Path,
        name: String = demo.name.substringBeforeLast(DEMO_EXTENSION),
    ): Path {
        val utConfig = UtConfig(directories = directories, gameDirectory = config.installationDirectory)
        UtInstaller(utConfig).install()
        val installation = UtInstallation(config.installationDirectory, directories)
        UtMapInstaller(installation).install()
        UtPackageInstaller(UtDemo.read(demo).packages, installation, utConfig).install()
        return workspace(RECORD_WORKSPACE, directories = directories) {
            RecordingSession(config, installation, directories).record(demo, name, this)
        }
    }

    companion object {
        /**
         * The prefix a played demo is staged under.
         */
        private const val STAGED_DEMO_PREFIX = "ut-"

        /**
         * The extension a demo file carries.
         */
        private const val DEMO_EXTENSION = ".dem"

        /**
         * The name the recorder ini of a playback run carries.
         */
        private const val RECORDER_INI_NAME = "UTRecorder.ini"

        /**
         * The name the recording workspace carries.
         */
        private const val RECORD_WORKSPACE = "record"

        /**
         * The source of the ids the stage names carry.
         */
        private val ids = TimestampId()
    }
}
