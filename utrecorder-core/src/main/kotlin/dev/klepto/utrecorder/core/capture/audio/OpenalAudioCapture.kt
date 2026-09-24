package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.demo.DemoPlayback
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.core.demo.DemoPlaybackMarks
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The audio capture of a recording: Unreal Tournament run at real speed with sound on, writing a wav through the OpenAL
 * wave output.
 *
 * Use the constructor to create one. A capture stages the demo through a [DemoPlayback], and plays the demo windowed
 * and with sound. The run is killed once [DemoPlaybackConfig.timeout] has passed. The wav is placed by the marks the
 * run logged and moved into a workspace of the capture's own. The progress of a capture is the seconds of audio in
 * the wav out of the demo's duration, read once a second while the run plays. A run that produces no wav, no marks, or
 * marks outside its wav fails with an [IllegalStateException].
 *
 * @property config The audio capture configuration, carrying the sound viewport and its frame rate limit.
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
        onProgress: ProgressListener,
    ): AudioTrack =
        workspace(AUDIO_CAPTURE, config = directories) { output ->
            playback.play(demo, STAGED_DEMO_PREFIX, AUDIO_CAPTURE) { description, system ->
                val wav = system[WAVE_NAME]
                val result =
                    coroutineScope {
                        val progress = launch { reportProgress(wav, description, onProgress) }
                        process(description, openAl(system)).launch(description.timeout).also { progress.cancel() }
                    }
                val track = track(wav, result.log, ffmpeg.duration(wav))
                track.copy(file = wav.move(output[WAVE_NAME]))
            }
        }

    /**
     * Reports the seconds of audio in [wav] out of the duration of [description]'s demo to [onProgress], once a second
     * until cancelled. While [wav] does not exist, reports `0.0`.
     */
    private suspend fun reportProgress(
        wav: Path,
        description: DemoPlaybackConfig,
        onProgress: ProgressListener,
    ) {
        val demoSeconds = description.demoDuration.toDouble(DurationUnit.SECONDS)
        while (true) {
            val bytes = withContext(Dispatchers.IO) { wav.toFile().length() }
            onProgress(bytes / WAVE_BYTES_PER_SECOND / demoSeconds)
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
     * Returns the unstarted run that plays [description] under [openAlEnvironment]: with sound, and playing the exec
     * script.
     */
    private fun process(
        description: DemoPlaybackConfig,
        openAlEnvironment: Map<String, String>,
    ): UtProcess =
        playback.process(AUDIO_CAPTURE, description, config.viewport()) {
            exec += execScript()
            sound = true
            environment += openAlEnvironment
        }

    /**
     * Returns the audio track in [wavFile], placed by the marks the run logged in [log].
     *
     * [wavDuration] is the length of the wav in seconds. The wav was opened [wavDuration] before its last-modified
     * time, and the track starts as far into the wav as the start mark's wall time lies after that. The track runs for
     * the wall time between the two marks.
     *
     * @throws IllegalStateException when [wavFile] does not exist, [log] holds no start mark or no end mark, or the
     * marks fall outside the wav.
     */
    suspend fun track(
        wavFile: Path,
        log: List<String>,
        wavDuration: Double,
    ): AudioTrack {
        check(withContext(Dispatchers.IO) { wavFile.exists() }) { "The audio capture produced no $WAVE_NAME" }

        val marks = DemoPlaybackMarks.parse(log)
        val opened = withContext(Dispatchers.IO) { wavFile.getLastModifiedTime() }.toMillis() / 1000.0 - wavDuration
        val offset = marks.start.wallTime.toEpochMilli() / 1000.0 - opened
        val span = marks.end.demoTime - marks.start.demoTime
        val length = (marks.end.wallTime.toEpochMilli() - marks.start.wallTime.toEpochMilli()) / 1000.0
        check(offset >= 0) { "The audio capture's marks fall outside $WAVE_NAME" }
        check(span > Duration.ZERO) { "The audio capture's marks fall outside $WAVE_NAME" }

        return AudioTrack(
            file = wavFile,
            offset = offset,
            length = length,
            demoEnd = marks.end.demoTime.toDouble(DurationUnit.SECONDS),
        )
    }

    /**
     * Returns the lines the capture adds to the exec script after the line that plays the demo: `RewindStart`, which
     * seeks back to the demo's start once the ticks settle, and `DisableRendering` when the config turns the audio
     * capture's rendering off.
     */
    fun execScript(): List<String> =
        buildList {
            this += "RewindStart"
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
         * The bytes the OpenAL wave output writes for each second of audio: 44100 stereo int16 samples.
         */
        private const val WAVE_BYTES_PER_SECOND = 44100.0 * 2 * 2

        /**
         * Returns the OpenAL configuration that writes stereo int16 wave output to [wavName], a path relative to the
         * game binary.
         */
        private fun waveConf(wavName: String): String =
            "[general]\ndrivers = wave\nfrequency = 44100\nchannels = stereo\nsample-type = int16\n\n[wave]\nfile = $wavName\n"
    }
}
