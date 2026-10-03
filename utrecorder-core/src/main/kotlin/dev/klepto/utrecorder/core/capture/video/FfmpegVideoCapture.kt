package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.capture.withProgressTimeout
import dev.klepto.utrecorder.core.demo.DemoPlayback
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.core.demo.DemoPlaybackHooks
import dev.klepto.utrecorder.core.demo.DemoPlaybackMarks
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import dev.klepto.utrecorder.ut99.UtProcessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.Locale
import kotlin.time.DurationUnit

/**
 * The logger of the video capture.
 */
private val log = logger<FfmpegVideoCapture>()

/**
 * The video capture of a recording: Unreal Tournament run with the hook library loaded, its clock stepped one frame at
 * a time, sending every rendered frame to a [FrameEncoder] on the loopback.
 *
 * A capture stages the demo through a [DemoPlayback], extracts the hooks into a workspace of its own, and plays the
 * demo windowed and without sound. The run is killed once ffmpeg has encoded no new frame for
 * [VideoCaptureConfig.idleTimeout], however long the whole run takes. The frames are encoded into a video file in a
 * workspace of the capture's own. The progress of a capture is the number of frames encoded out of the number
 * [DemoPlaybackConfig.runDuration] makes at [VideoCaptureConfig.fps]. Every line the hook library prints is logged at
 * DEBUG. A run that logs no marks fails with an [IllegalStateException] naming the missing mark and the exit code. A run whose hook library reports
 * that it stopped the frame capture fails with an [IllegalStateException] naming the reason it gave. Every run renders
 * and encodes in the [VideoFormat] that [VideoCaptureConfig.format] picks from [DemoPlaybackConfig.runDuration] and
 * [audioBitrate].
 *
 * @property config The video capture configuration, carrying the tiers, the fps, the idle timeout and the renderer
 * tuning.
 * @property audioBitrate The bitrate of the audio track the video is muxed with, in ffmpeg's notation, such as `128k`.
 * @property installation The installation the capture runs in.
 * @property directories The directories the capture's workspace is kept under.
 * @property ffmpeg The ffmpeg that encodes the frames and reads the length of the video file.
 */
class FfmpegVideoCapture(
    private val config: VideoCaptureConfig,
    private val audioBitrate: String,
    private val installation: UtInstallation,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
    private val ffmpeg: MediaTool = FfmpegMediaTool(),
) : VideoCapture {
    /**
     * The playback the capture stages its demo through and describes its run with.
     */
    private val playback = DemoPlayback(installation, directories)

    override suspend fun record(
        demo: Path,
        onProgress: ProgressListener<Unit>,
    ): VideoTrack =
        workspace(VIDEO_CAPTURE, config = directories) { output ->
            val target = output[VIDEO_NAME]
            playback.play(demo, STAGED_DEMO_PREFIX, VIDEO_CAPTURE) { description, system ->
                val format = config.format(description.runDuration(), audioBitrate)
                val frames = MutableStateFlow(0L)
                val onFrames = frameProgress(description, frames, onProgress)
                val encoder = FrameEncoder(config, format, DemoPlaybackConfig.STARTUP_TIMEOUT, target, ffmpeg, onFrames)
                val marks = DemoPlaybackMarks.parse(play(description, format, extractHooks(system), encoder, frames))
                track(target, format, marks)
            }
        }

    /**
     * Returns the listener that publishes the frames encoded to [encoded], and reports them out of the frames the run
     * of [description] makes to [onProgress].
     */
    private fun frameProgress(
        description: DemoPlaybackConfig,
        encoded: MutableStateFlow<Long>,
        onProgress: ProgressListener<Unit>,
    ): (MediaProgress) -> Unit {
        val frames = description.runDuration().toDouble(DurationUnit.SECONDS) * config.fps
        return {
            encoded.value = it.frames
            onProgress(Progress(it.frames / frames))
        }
    }

    /**
     * Returns the result of the run of [description] in [format] with [hooks], launched inside [encoder]'s block. The
     * run is killed once [frames], the frames encoded so far, has not grown for [VideoCaptureConfig.idleTimeout].
     *
     * @throws java.util.concurrent.TimeoutException if no new frame is encoded for [VideoCaptureConfig.idleTimeout].
     * @throws IllegalStateException if the hook library reports that it stopped the frame capture, naming its reason.
     */
    private suspend fun play(
        description: DemoPlaybackConfig,
        format: VideoFormat,
        hooks: List<Path>,
        encoder: FrameEncoder,
        frames: StateFlow<Long>,
    ): UtProcessResult {
        val stop = MutableStateFlow<String?>(null)
        val (result, _) =
            try {
                encoder.encode { launchWatched(process(description, format, port, hooks), frames, stop) }
            } catch (failure: Exception) {
                throw stopFailure(failure, stop.value)
            }
        stop.value?.let { error("Frame capture was stopped by the hook library: $it") }
        return result
    }

    /**
     * Returns the result of [run], killed once [frames] has not grown for [VideoCaptureConfig.idleTimeout]. Records in
     * [stop] the reason of a frame capture stop the hook library reports in the output of [run].
     */
    private suspend fun launchWatched(
        run: UtProcess,
        frames: StateFlow<Long>,
        stop: MutableStateFlow<String?>,
    ): UtProcessResult =
        coroutineScope {
            launch { watchStop(run, stop) }
            withProgressTimeout(frames, config.idleTimeout) { run.launch() }
        }

    /**
     * Returns the reason the hook library gives in [line], one line of the game output, for stopping the frame capture.
     * If [line] does not report a stop, returns `null` instead.
     */
    fun stopReason(line: String): String? = HOOK_STOP.matchEntire(line.trim())?.groupValues?.get(1)

    /**
     * Logs every line the hook library prints in the output of [run], and records in [stop] the reason of the last line
     * that reports a frame capture stop, until the output ends.
     */
    private suspend fun watchStop(
        run: UtProcess,
        stop: MutableStateFlow<String?>,
    ) {
        run.drain().collect { line ->
            DemoPlaybackHooks.message(line)?.let { message -> log.debug { message } }
            stopReason(line)?.let { stop.value = it }
        }
    }

    /**
     * Returns [failure] with [reason], the stop the hook library reported, named in its message. If [reason] is `null`
     * or [failure] is a cancellation, returns [failure] itself instead.
     */
    private fun stopFailure(
        failure: Throwable,
        reason: String?,
    ): Throwable =
        when {
            reason == null || failure is CancellationException -> failure
            else -> IllegalStateException("Frame capture was stopped by the hook library: $reason", failure)
        }

    /**
     * Returns the video track in [videoFile] recorded in [format], starting at the spawn mark of [marks].
     */
    private suspend fun track(
        videoFile: Path,
        format: VideoFormat,
        marks: DemoPlaybackMarks,
    ): VideoTrack =
        VideoTrack(
            file = videoFile,
            length = ffmpeg.duration(videoFile),
            format = format,
            demoStart = marks.spawn?.demoTime?.toDouble(DurationUnit.SECONDS),
        )

    /**
     * Extracts the `uthooks` hook library and, on Windows, the `utinject` injector into [system], and returns their
     * paths. [system] names the files in the installation's `System` directory.
     */
    suspend fun extractHooks(system: Workspace): List<Path> = DemoPlaybackHooks.extract(system)

    /**
     * Returns the environment the video capture runs under: the port, the render fps and the frame size of [format] the
     * hook library reads, plus the Linux environment on Linux.
     */
    fun environment(
        port: Int,
        format: VideoFormat,
    ): Map<String, String> =
        buildMap {
            this["UTRECORDER_PORT"] = port.toString()
            this["UTRECORDER_FPS"] = config.renderFps().toString()
            this["UTRECORDER_WIDTH"] = format.width.toString()
            this["UTRECORDER_HEIGHT"] = format.height.toString()
            if (installation.platform.operatingSystem != WINDOWS) putAll(linuxEnvironment())
        }

    /**
     * Returns the Linux part of the environment: `SWRAST_NO_PRESENT`, which stops llvmpipe from presenting frames to
     * Xvfb, and the llvmpipe tuning of a software-rendered capture.
     */
    private fun linuxEnvironment(): Map<String, String> =
        buildMap {
            this["SWRAST_NO_PRESENT"] = "1"
            this["LP_NUM_THREADS"] = threads().toString()
            if (config.linuxMesa.glthread) this["mesa_glthread"] = "true"
            if (config.linuxMesa.noError) this["MESA_NO_ERROR"] = "1"
            if (config.linuxMesa.vectorWidth.isNotBlank()) this["LP_NATIVE_VECTOR_WIDTH"] = config.linuxMesa.vectorWidth
        }

    /**
     * Returns the number of llvmpipe threads: the configured count, or the processor count when none is configured.
     */
    private fun threads(): Int =
        when {
            config.linuxMesa.threads > 0 -> config.linuxMesa.threads
            else -> Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }

    /**
     * Returns the unstarted run that plays [description] at the frame size of [format]: without sound, and playing the
     * exec script. On Windows the injector of [hooks] starts the game executable copy, and on Linux the copy starts
     * directly. The HUD is hidden when the demo loads a Bunny Track mod older than `UTBT_BTMod_v18`.
     */
    fun process(
        description: DemoPlaybackConfig,
        format: VideoFormat,
        port: Int,
        hooks: List<Path>,
    ): UtProcess =
        playback.process(VIDEO_CAPTURE, description, config.viewport(format)) {
            DemoPlaybackHooks.inject(this, hooks)
            exec += execScript(description)
            sound = false
            environment += this@FfmpegVideoCapture.environment(port, format)
            if (hidesHud(description)) userIni { this["Botpack.ChallengeHUD", "bHideHUD"] = "True" }
        }

    /**
     * Returns the lines the capture adds to the exec script after the line that plays [description]'s demo:
     * `SeekStart`, which skips the demo ahead to just before the run start once the ticks settle, the console's trim
     * command `DemoFrames <render fps>`, `TimerReset <run start>`, which starts the trim at the Bunny Track
     * timer reset nearest the run start, `TimerStop <run stop>`, which ends the run one second after the Bunny Track
     * timer stops, `HideMessages`, which keeps chat and progress messages off the recorded HUD,
     * and `SmoothRecorder`, which smooths the movement and view of the player who recorded the demo, when
     * [VideoCaptureConfig.smoothRecorder] is on. If the demo shows
     * no run start or no run stop, `TimerReset` or `TimerStop` is left out.
     */
    fun execScript(description: DemoPlaybackConfig): List<String> =
        buildList {
            this += "SeekStart"
            this += "DemoFrames %.6f".format(Locale.ROOT, config.renderFps().toDouble())
            description.runStart?.let {
                this += "TimerReset %.6f".format(Locale.ROOT, it.toDouble(DurationUnit.SECONDS))
            }
            description.runStop?.let {
                this += "TimerStop %.6f".format(Locale.ROOT, it.toDouble(DurationUnit.SECONDS))
            }
            this += "HideMessages"
            if (config.smoothRecorder) this += "SmoothRecorder"
        }

    companion object {
        /**
         * The name of the video capture's workspace and of its run.
         */
        private const val VIDEO_CAPTURE = "video-capture"

        /**
         * The prefix of the name the video capture stages a demo under.
         */
        private const val STAGED_DEMO_PREFIX = "fp-"

        /**
         * The name of the video file the frames are encoded into.
         */
        private const val VIDEO_NAME = "video.mp4"

        /**
         * The oldest version of the Bunny Track mod whose HUD the capture records.
         */
        private const val BT_MOD_HUD_VERSION = 18

        /**
         * The name of a Bunny Track mod package, capturing its version.
         */
        private val BT_MOD = Regex("""UTBT_BTMod_v(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * A line of the hook library that reports a frame capture stop, capturing its reason.
         */
        private val HOOK_STOP = Regex("""uthooks: (.*, capture stopped)""")

        /**
         * Returns whether the demo of [description] loads a Bunny Track mod older than [BT_MOD_HUD_VERSION].
         */
        private fun hidesHud(description: DemoPlaybackConfig): Boolean =
            description.packages
                .mapNotNull { BT_MOD.matchEntire(it.name) }
                .any { it.groupValues[1].toInt() < BT_MOD_HUD_VERSION }
    }
}
