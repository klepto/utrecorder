package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import dev.klepto.utrecorder.ut99.demo.UtDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The sound pass of a recording: a UT99 run at real speed with sound on, writing a wav through OpenAL's wave output,
 * whose placement the demo marks of its own log settle.
 *
 * @property config The recording the pass runs for, carrying the sound-pass viewport, its frame rate cap and the
 * audio toggle.
 * @property installation The installation the pass runs in.
 * @property directories The directories the pass stages its demo in and reads its output from.
 */
class SoundPass(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) : SoundPassLike {
    /**
     * Plays [demo] with sound on and returns the [SoundTrack] the pass produced, placed by its own marks.
     *
     * Stages the demo into the installation's `System` directory beside the console settings the run reads and plays
     * it windowed and sounding under the OpenAL environment [openAl] writes into a workspace of the pass, whose wav
     * [track] places from the run's marks. The wav's duration is probed for the placement and the wav is moved into
     * [workspace] when the run ends; the staged copy and the console settings are removed whichever way it does. A
     * recording without audio fails without launching anything.
     *
     * @throws IllegalStateException when the pass was launched with no audio, or the pass produces no wav, no marks, or
     * marks that fall outside its wav.
     */
    override suspend fun record(
        demo: Path,
        workspace: Workspace,
    ): SoundTrack {
        check(config.audio) { "The recording carries no audio, not launching the sound pass" }

        val demoDuration = UtDemo.read(demo).duration
        val description = PlaybackDescription.of(STAGED_DEMO_PREFIX + IDS.next(), demoDuration, config)
        val recorderPackage = RecorderPackage(installation)
        val stagedDemo = installation.systemDirectory[description.stagedDemoName + DEMO_EXTENSION]
        return workspace(SOUND_PASS, installation.systemDirectory, directories) { pass ->
            try {
                demo.copy(stagedDemo) { "Demo file not found at $demo" }
                withContext(Dispatchers.IO) { recorderPackage.configure(description.recorderIni()) }
                val result = process(description, demoDuration, openAl(pass)).launch(config.timeout(demoDuration))
                val wav = pass[WAVE_NAME]
                val track = track(wav, result.log, Ffmpeg().duration(wav))
                track.copy(file = wav.move(workspace[WAVE_NAME]))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    stagedDemo.deleteIfExists()
                    recorderPackage.clear()
                }
            }
        }
    }

    /**
     * Writes the OpenAL wave configuration into [pass] and returns the environment the pass's run starts with, naming
     * that configuration and the wave driver.
     *
     * The configuration names the wav as [pass] names it in the installation's `System` directory, relative to the
     * game binary the run starts in.
     */
    suspend fun openAl(pass: Workspace): Map<String, String> {
        val conf = pass[WAVE_CONF]
        withContext(Dispatchers.IO) { conf.writeText(waveConf(pass[WAVE_NAME].name)) }
        return mapOf(
            "ALSOFT_CONF" to conf.absolutePathString(),
            "ALSOFT_DRIVERS" to "wave",
            "ALSOFT_LOGLEVEL" to "2",
        )
    }

    /**
     * Returns the sound run of [description] under [openAlEnvironment]: windowed and sounding, playing the exec script
     * with the game ini of the pass.
     */
    private fun process(
        description: PlaybackDescription,
        demoDuration: Duration,
        openAlEnvironment: Map<String, String>,
    ): UtProcess =
        installation.process(SOUND_PASS) {
            exec = execScript(description)
            sound = true
            windowed = true
            environment += openAlEnvironment
            baseGameIni = RecorderSettings.gameIni
            baseUserIni = RecorderSettings.userIni
            gameIni {
                this["Engine.Engine", "ViewportManager"] = description.client(installation.platform)
                this["Engine.Engine", "Console"] = description.console

                gameIni(demoDuration).forEach { (section, values) ->
                    values.forEach { (key, value) -> this[section, key] = value }
                }
            }
        }

    /**
     * Returns the sound track the pass produced: the wav at [wavFile], placed by the marks its run logged in [log].
     *
     * The wav's length is read for its `opened`-time, the wall-clock time the run opened it at, so the mark wall times
     * the run logged become offsets into the wav, and the track's length is the wall time between the two marks, the
     * run's own audio, leaving the wav's head and the run's trailing exit delay outside the track. Marks bounding more
     * demo time than the wav holds, or starting before the wav, fail the recording. Reached by [record] and by tests
     * standing in for a completed run.
     *
     * @throws IllegalStateException when [wavFile] holds nothing, [log] holds no start mark, no end mark, or marks
     * outside the wav, naming what was missing.
     */
    suspend fun track(
        wavFile: Path,
        log: List<String>,
        wavDuration: Double,
    ): SoundTrack {
        check(withContext(Dispatchers.IO) { wavFile.exists() }) { "The sound pass produced no $WAVE_NAME" }

        val marks = DemoMarks.parse(log)
        val opened = withContext(Dispatchers.IO) { wavFile.getLastModifiedTime() }.toMillis() / 1000.0 - wavDuration
        val offset = marks.start.wallTime.toEpochMilli() / 1000.0 - opened
        val span = marks.end.demoTime - marks.start.demoTime
        val length = (marks.end.wallTime.toEpochMilli() - marks.start.wallTime.toEpochMilli()) / 1000.0
        check(offset >= 0) { "The sound pass's marks fall outside $WAVE_NAME" }
        check(span > Duration.ZERO) { "The sound pass's marks fall outside $WAVE_NAME" }

        return SoundTrack(
            file = wavFile,
            offset = offset,
            length = length,
            demoEnd = marks.end.demoTime.toDouble(DurationUnit.SECONDS),
            demoSpan = span.toDouble(DurationUnit.SECONDS),
        )
    }

    /**
     * Returns the game-ini values the pass holds: the sound viewport and its frame rate cap in each of the client
     * sections, and the cap in the render device section.
     */
    fun gameIni(demoDuration: Duration): Map<String, Map<String, String>> =
        mapOf(
            WINDOWS_CLIENT to clientValues(),
            SDL_CLIENT to clientValues(),
            RENDER_DEVICE to renderDeviceValues(),
        )

    /**
     * Returns the lines of the exec script the pass plays: the [PlaybackDescription.execLine] pinned to the demo's own
     * clock with `?timebased`, and `DisableRendering` when the config turns the sound pass's rendering off.
     */
    fun execScript(description: PlaybackDescription): List<String> {
        val lines = mutableListOf("${description.execLine}?timebased")
        if (!config.soundRender) lines += DISABLE_RENDERING
        return lines
    }

    /**
     * Returns the values the pass sets in each of its client sections.
     */
    private fun clientValues(): Map<String, String> =
        mapOf(
            "WindowedViewportX" to config.soundWidth.toString(),
            "WindowedViewportY" to config.soundHeight.toString(),
            "FullscreenViewportX" to config.soundWidth.toString(),
            "FullscreenViewportY" to config.soundHeight.toString(),
            "FrameRateLimit" to rate(config.soundFps.toDouble()),
        )

    /**
     * Returns the values the pass sets in the `[OpenGLDrv.OpenGLRenderDevice]` section.
     */
    private fun renderDeviceValues(): Map<String, String> = mapOf("FrameRateLimit" to rate(config.soundFps.toDouble()))

    /**
     * The frame rate [value] written the way the game's ini holds one.
     */
    private fun rate(value: Double) = String.format(Locale.ROOT, "%f", value)

    companion object {
        /**
         * The client section the Windows build configures.
         */
        private const val WINDOWS_CLIENT = "WinDrv.WindowsClient"

        /**
         * The client section the Linux build configures.
         */
        private const val SDL_CLIENT = "SDLDrv.SDLClient"

        /**
         * The render device section the sound pass caps.
         */
        private const val RENDER_DEVICE = "OpenGLDrv.OpenGLRenderDevice"

        /**
         * The console command turning the sound pass's rendering off.
         */
        private const val DISABLE_RENDERING = "DisableRendering"

        /**
         * The name the OpenAL wave output writes.
         */
        private const val WAVE_NAME = "audio.wav"

        /**
         * The name of the OpenAL configuration the sound pass reads.
         */
        private const val WAVE_CONF = "alsoft.conf"

        /**
         * Returns the OpenAL wave configuration writing stereo int16 output to [wavName], a path relative to the game
         * binary.
         */
        private fun waveConf(wavName: String): String =
            "[general]\ndrivers = wave\nfrequency = 44100\nchannels = stereo\nsample-type = int16\n\n[wave]\nfile = $wavName\n"

        /**
         * The name the sound pass stages a demo under.
         */
        private const val STAGED_DEMO_PREFIX = "sp-"

        /**
         * The suffix a staged demo file carries.
         */
        private const val DEMO_EXTENSION = ".dem"

        /**
         * The name the sound pass's instance workspace carries.
         */
        private const val SOUND_PASS = "sound-pass"

        /**
         * The source of the ids the stage names carry.
         */
        private val IDS = TimestampId()
    }
}
