package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.capture.withProgressTimeout
import dev.klepto.utrecorder.core.demo.DemoPlayback
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.core.demo.DemoPlaybackHooks
import dev.klepto.utrecorder.core.demo.DemoPlaybackMark
import dev.klepto.utrecorder.core.demo.DemoPlaybackMarks
import dev.klepto.utrecorder.core.demo.DemoPlaybackRenderDevice
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The audio capture of a recording: Unreal Tournament run with sound on and the hook library loaded, writing a wav
 * through the OpenAL wave output, its clock stepped one mixer period of the wav at a time.
 *
 * Use the constructor to create one. A capture stages the demo through a [DemoPlayback], extracts the hooks into a
 * workspace of its own, and plays the demo windowed and with sound. The run is killed once its wav has not grown for
 * [AudioCaptureConfig.idleTimeout], however long the whole run takes. The wav is placed by the marks the run logged and
 * moved into a workspace of the capture's own. The progress of a capture is the seconds of audio in the wav out of
 * [DemoPlaybackConfig.runDuration], read once a second while the run plays. Every line the hook library prints is logged
 * at DEBUG. A run that produces no wav, no marks, or marks outside its wav fails with an
 * [IllegalStateException].
 *
 * @property config The audio capture configuration, carrying the sound viewport, its frame rate limit and the idle
 * timeout.
 * @property installation The installation the capture runs in.
 * @property directories The directories the capture's workspace is kept under.
 * @property ffmpeg The ffmpeg that reads the length of the wav.
 */
class OpenalAudioCapture(
    private val config: AudioCaptureConfig,
    private val installation: UtInstallation,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
) : AudioCapture {
    /**
     * The playback the capture stages its demo through and describes its run with.
     */
    private val playback = DemoPlayback(installation, directories)

    override suspend fun record(
        demo: Path,
        onProgress: ProgressListener<Unit>,
    ): AudioTrack =
        workspace(AUDIO_CAPTURE, config = directories) { output ->
            playback.play(demo, STAGED_DEMO_PREFIX, AUDIO_CAPTURE) { description, system ->
                val wav = system[WAVE_NAME]
                val run = process(description, openAl(system), DemoPlaybackHooks.extract(system))
                val written = MutableStateFlow(0L)
                val result =
                    coroutineScope {
                        val progress = launch { reportProgress(wav, description, written, onProgress) }
                        launch { logHookMessages(run) }
                        withProgressTimeout(written, config.idleTimeout) { run.launch() }.also { progress.cancel() }
                    }
                val track = track(wav, result.log, ffmpeg.duration(wav))
                track.copy(file = wav.move(output[WAVE_NAME]))
            }
        }

    /**
     * Logs every line the hook library prints in the output of [run], until the output ends.
     */
    private suspend fun logHookMessages(run: UtProcess) {
        run.drain().collect { line -> DemoPlaybackHooks.message(line)?.let { message -> log.debug { message } } }
    }

    /**
     * Reads the size of [wav] once a second until cancelled. Publishes the size to [written], and reports the seconds
     * of audio in [wav] out of the duration of the run of [description] to [onProgress]. While [wav] does not exist, the
     * size is `0`.
     */
    private suspend fun reportProgress(
        wav: Path,
        description: DemoPlaybackConfig,
        written: MutableStateFlow<Long>,
        onProgress: ProgressListener<Unit>,
    ) {
        val runSeconds = description.runDuration().toDouble(DurationUnit.SECONDS)
        while (true) {
            val bytes = withContext(Dispatchers.IO) { wav.toFile().length() }
            written.value = bytes
            onProgress(Progress(bytes / WAVE_BYTES_PER_SECOND / runSeconds))
            delay(1.seconds)
        }
    }

    /**
     * Returns the OpenAL environment the run starts with: the wave driver, and the path of the OpenAL configuration
     * this function writes into [system].
     *
     * The configuration names the wav as [system] names it, relative to the game binary the run starts in.
     */
    suspend fun openAl(system: Workspace): Map<String, String> {
        val conf = system["alsoft.conf"]
        withContext(Dispatchers.IO) { conf.writeText(waveConf(system[WAVE_NAME].name)) }
        return mapOf(
            "ALSOFT_CONF" to conf.absolutePathString(),
            "ALSOFT_DRIVERS" to "wave",
            "ALSOFT_LOGLEVEL" to "2",
        )
    }

    /**
     * Returns the environment the hook library reads: the sample rate of the wav and the samples of one mixer period,
     * which put the hook library in audio mode.
     */
    fun environment(): Map<String, String> =
        mapOf(
            "UTRECORDER_AUDIO_RATE" to WAVE_RATE.toString(),
            "UTRECORDER_AUDIO_PERIOD" to WAVE_PERIOD.toString(),
        )

    /**
     * Returns the environment the run adds on Linux: `SWRAST_NO_PRESENT`, which stops llvmpipe from presenting frames to
     * Xvfb, and `LP_NUM_THREADS` of `0`, which makes llvmpipe rasterize on the game thread instead of waking a raster
     * thread for each host core.
     */
    fun linuxEnvironment(): Map<String, String> = mapOf("SWRAST_NO_PRESENT" to "1", "LP_NUM_THREADS" to "0")

    /**
     * Returns the values the run sets in the section of [DemoPlaybackRenderDevice.OPENGL] on top of the viewport's: the
     * render quality settings that change no actor, all off.
     */
    private fun renderQualityValues(): Map<String, String> =
        mapOf(
            "UseAA" to "False",
            "DetailTextures" to "False",
            "UseHDTextures" to "False",
            "UseTrilinear" to "False",
        )

    /**
     * Returns the unstarted run that plays [description] under [openAlEnvironment] with the hook library of [hooks]
     * loaded: with sound, and playing the exec script. The run's game ini gets [renderQualityValues] in the section of
     * [DemoPlaybackRenderDevice.OPENGL]. On Linux, the run also gets [linuxEnvironment].
     */
    fun process(
        description: DemoPlaybackConfig,
        openAlEnvironment: Map<String, String>,
        hooks: List<Path>,
    ): UtProcess =
        playback.process(AUDIO_CAPTURE, description, config.viewport()) {
            DemoPlaybackHooks.inject(this, hooks)
            exec += execScript(description)
            sound = true
            gameIni {
                renderQualityValues().forEach { (key, value) -> this[DemoPlaybackRenderDevice.OPENGL.section, key] = value }
            }
            environment += openAlEnvironment
            environment += this@OpenalAudioCapture.environment()
            if (installation.platform.operatingSystem != WINDOWS) environment += this@OpenalAudioCapture.linuxEnvironment()
        }

    /**
     * Returns the audio track in [wavFile], placed by the marks the run logged in [log].
     *
     * The hook library reports the position of the wav output as the date of the game, counted from [WAVE_EPOCH], so
     * the date of a mark is its position in the wav once the hook library is armed, which it is from the spawn mark
     * on. The track starts at the spawn mark and runs to the end mark. [wavDuration] is the length of the wav in
     * seconds.
     *
     * @throws IllegalStateException when [wavFile] does not exist, [log] holds no start, spawn or end mark, or the
     * marks fall outside the wav.
     */
    suspend fun track(
        wavFile: Path,
        log: List<String>,
        wavDuration: Double,
    ): AudioTrack {
        check(withContext(Dispatchers.IO) { wavFile.exists() }) { "The audio capture produced no $WAVE_NAME" }

        val marks = DemoPlaybackMarks.parse(log)
        val spawn = checkNotNull(marks.spawn) { "Run log holds no spawn mark" }
        val start = wavePosition(spawn)
        val end = wavePosition(marks.end)
        val span = marks.end.demoTime - spawn.demoTime
        check(start >= 0 && end <= wavDuration && span > Duration.ZERO) { "The audio capture's marks fall outside $WAVE_NAME" }

        return AudioTrack(
            file = wavFile,
            offset = start,
            length = end - start,
            demoStart = spawn.demoTime.toDouble(DurationUnit.SECONDS),
        )
    }

    /**
     * Returns the lines the capture adds to the exec script after the line that plays [description]'s demo:
     * `SeekStart`, which pauses the demo until the ticks settle, then seeks two seconds before the run start, or to the
     * demo start without one, `DemoFrames <mixer periods per second>`, which turns on the spawn check that arms the hook
     * library and logs the spawn mark, `TimerReset <run start>`, which makes the console signal the spawn when the demo
     * reaches the run start, `TimerStop <run stop>`, which ends the run one second after the Bunny Track timer stops,
     * and `DisableRendering` when the config turns the audio capture's rendering off. If the demo shows no run start or
     * no run stop, `TimerReset` or `TimerStop` is left out.
     */
    fun execScript(description: DemoPlaybackConfig): List<String> =
        buildList {
            this += "SeekStart"
            this += "DemoFrames %.6f".format(Locale.ROOT, WAVE_RATE.toDouble() / WAVE_PERIOD)
            this += description.timerExecLines()
            if (!config.render) this += "DisableRendering"
        }

    companion object {
        /**
         * The name of the audio capture's workspace and of its run.
         */
        private const val AUDIO_CAPTURE = "audio-capture"

        /**
         * The prefix of the name the audio capture stages a demo under.
         */
        private const val STAGED_DEMO_PREFIX = "sp-"

        /**
         * The name of the wav the OpenAL wave output writes.
         */
        private const val WAVE_NAME = "audio.wav"

        /**
         * The sample rate of the wav.
         */
        private const val WAVE_RATE = 44100

        /**
         * The samples the OpenAL mixer renders at a time, which are also the samples of one clock step of the run.
         */
        private const val WAVE_PERIOD = 512

        /**
         * The bytes the OpenAL wave output writes for each second of audio: [WAVE_RATE] stereo int16 samples.
         */
        private const val WAVE_BYTES_PER_SECOND = WAVE_RATE * 2 * 2.0

        /**
         * The date the hook library counts the position of the wav output from.
         */
        private val WAVE_EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0)

        /**
         * The logger of the audio capture.
         */
        private val log = logger<OpenalAudioCapture>()

        /**
         * Returns the position of [mark] in the wav, in seconds: the time from [WAVE_EPOCH] to the date of [mark].
         */
        private fun wavePosition(mark: DemoPlaybackMark): Double {
            val epoch = WAVE_EPOCH.atZone(ZoneId.systemDefault()).toInstant()
            return ChronoUnit.MILLIS.between(epoch, mark.wallTime) / 1000.0
        }

        /**
         * Returns the OpenAL configuration that writes stereo int16 wave output to [wavName], a path relative to the
         * game binary, one mixer period of [WAVE_PERIOD] samples at a time.
         */
        private fun waveConf(wavName: String): String =
            "[general]\ndrivers = wave\nfrequency = $WAVE_RATE\nchannels = stereo\nsample-type = int16\n" +
                "period_size = $WAVE_PERIOD\n\n[wave]\nfile = $wavName\n"
    }
}
