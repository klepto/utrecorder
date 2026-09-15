package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.demo.UtDemo
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The sound pass of a recording: a UT99 run at real speed with sound on, writing a wav through OpenAL's wave output,
 * whose placement the demo marks of its own log settle.
 *
 * @property config The recording the pass runs for, carrying the sound-pass viewport, its frame rate cap and the
 * audio toggle.
 * @property installation The installation the pass runs in.
 * @property openAlEnvironment The OpenAL environment the pass's run starts with, naming the wave configuration and
 * the wave driver; empty when the recording carries no audio.
 * @property directories The directories the pass stages its demo in and reads its output from.
 */
class SoundPass(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val openAlEnvironment: Map<String, String> = emptyMap(),
    private val directories: Directories = Directories.DEFAULT,
) : SoundPassLike {
    /**
     * Plays [demo] with sound on and returns the [SoundTrack] the pass produced, placed by its own marks.
     *
     * Stages the demo into the installation's `System` directory and plays it windowed and sounding under the OpenAL
     * environment, whose wav [track] places from the run's marks. The wav's duration is probed for the placement and
     * the wav is moved into [workspace] when the run ends; the staged copy is removed whichever way it does. No OpenAL
     * environment fails the recording without launching anything.
     *
     * @throws IllegalStateException when the pass was launched with no audio, or the pass produces no wav, no marks, or
     * marks that fall outside its wav.
     */
    override fun record(
        demo: Path,
        workspace: Workspace,
    ): SoundTrack {
        check(openAlEnvironment.isNotEmpty()) { "The recording carries no audio, not launching the sound pass" }

        val demoDuration = UtDemo.read(demo).duration
        val description = PlaybackDescription.of(STAGED_DEMO_PREFIX + IDS.next(), demoDuration, config)
        val stagedDemo = installation.systemDirectory[description.stagedDemoName + DEMO_EXTENSION]
        try {
            demo.copy(stagedDemo) { "Demo file not found at $demo" }
            val instance =
                installation.instance(SOUND_PASS) {
                    exec = execScript(description)
                    sound = true
                    windowed = true
                    environment += openAlEnvironment
                    gameIni {
                        gameIni(demoDuration).forEach { (section, values) ->
                            values.forEach { (key, value) -> this[section, key] = value }
                        }
                    }
                }
            val result = instance.start().await(config.timeout(demoDuration))
            val wav = installation.systemDirectory[WAVE_NAME]
            val track = track(wav, result.log, Ffmpeg().duration(wav))
            val placedWav = workspace.file(WAVE_NAME)
            wav.move(placedWav)
            return track.copy(file = placedWav)
        } finally {
            stagedDemo.deleteIfExists()
            installation.systemDirectory[WAVE_NAME].deleteIfExists()
        }
    }

    /**
     * Returns the sound track the pass produced: the wav at [wavFile], placed by the marks its run logged in [log].
     *
     * The wav's length is read for its `opened`-time, the wall-clock time the run opened it at, so the mark wall times
     * the run logged become offsets into the wav. Marks bounding more demo time than the wav holds, or starting before
     * the wav, fail the recording. Reached by [record] and by tests standing in for a completed run.
     *
     * @throws IllegalStateException when [wavFile] holds nothing, [log] holds no start mark, no end mark, or marks
     * outside the wav, naming what was missing.
     */
    fun track(
        wavFile: Path,
        log: List<String>,
        wavDuration: Double,
    ): SoundTrack {
        check(wavFile.exists()) { "The sound pass produced no $WAVE_NAME" }

        val marks = DemoMarks.parse(log)
        val opened = wavFile.getLastModifiedTime().toMillis() / 1000.0 - wavDuration
        val offset = marks.start.wallTime.epochSecond - opened
        val span = marks.end.demoTime - marks.start.demoTime
        check(offset >= 0) { "The sound pass's marks fall outside $WAVE_NAME" }
        check(span > Duration.ZERO) { "The sound pass's marks fall outside $WAVE_NAME" }

        return SoundTrack(
            file = wavFile,
            offset = offset,
            length = wavDuration,
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
