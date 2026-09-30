package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.demo.DemoPlayback
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.core.demo.DemoPlaybackMarks
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaProgress
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import dev.klepto.utrecorder.ut99.UtProcessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import kotlin.time.DurationUnit

/**
 * The video capture of a recording: Unreal Tournament run with the hook library loaded, its clock stepped one frame at
 * a time, sending every rendered frame to a [FrameEncoder] on the loopback.
 *
 * A capture stages the demo through a [DemoPlayback], extracts the hooks into a workspace of its own, and plays the
 * demo windowed and without sound. The run is killed once ffmpeg has encoded no new frame for
 * [VideoCaptureConfig.idleTimeout], however long the whole run takes. The frames are encoded into a video file in a
 * workspace of the capture's own. The progress of a capture is the number of frames encoded out of the number the demo's duration makes at [VideoCaptureConfig.fps]. A run that logs no marks
 * fails with an [IllegalStateException] naming the missing mark and the exit code.
 *
 * @property config The video capture configuration, carrying the frame size, the fps, the idle timeout and the renderer
 * tuning.
 * @property installation The installation the capture runs in.
 * @property directories The directories the capture's workspace is kept under.
 * @property ffmpeg The ffmpeg that encodes the frames and reads the length of the video file.
 */
class FfmpegVideoCapture(
    private val config: VideoCaptureConfig,
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
                val frames = MutableStateFlow(0L)
                val onFrames = frameProgress(description, frames, onProgress)
                val encoder = FrameEncoder(config, DemoPlaybackConfig.STARTUP_TIMEOUT, target, ffmpeg, onFrames)
                val marks = DemoPlaybackMarks.parse(play(description, extractHooks(system), encoder, frames))
                track(target, marks, smoothing(description))
            }
        }

    /**
     * Returns the listener that publishes the frames encoded to [encoded], and reports them out of the frames
     * [description]'s demo makes to [onProgress].
     */
    private fun frameProgress(
        description: DemoPlaybackConfig,
        encoded: MutableStateFlow<Long>,
        onProgress: ProgressListener<Unit>,
    ): (MediaProgress) -> Unit {
        val frames = description.demoDuration.toDouble(DurationUnit.SECONDS) * config.fps
        return {
            encoded.value = it.frames
            onProgress(Progress(it.frames / frames))
        }
    }

    /**
     * Returns the result of the run of [description] with [hooks], launched inside [encoder]'s block. The run is
     * killed once [frames], the frames encoded so far, has not grown for [VideoCaptureConfig.idleTimeout].
     *
     * @throws java.util.concurrent.TimeoutException if no new frame is encoded for [VideoCaptureConfig.idleTimeout].
     */
    private suspend fun play(
        description: DemoPlaybackConfig,
        hooks: List<Path>,
        encoder: FrameEncoder,
        frames: StateFlow<Long>,
    ): UtProcessResult {
        val (result, _) =
            encoder.encode {
                withProgressTimeout(frames, config.idleTimeout) { process(description, port, hooks).launch() }
            }
        return result
    }

    /**
     * Returns the video track in [videoFile], ending at the end mark of [marks]. The track shows the recorder's
     * movement behind the demo by the movement delay of [smoothing].
     */
    private suspend fun track(
        videoFile: Path,
        marks: DemoPlaybackMarks,
        smoothing: RecorderSmoothing?,
    ): VideoTrack =
        VideoTrack(
            file = videoFile,
            length = ffmpeg.duration(videoFile),
            demoEnd = marks.end.demoTime.toDouble(DurationUnit.SECONDS),
            smoothingDelay = smoothing?.movement?.toDouble(DurationUnit.SECONDS) ?: 0.0,
        )

    /**
     * Returns the smoothing of the recorder for [description]'s demo. If [VideoCaptureConfig.smoothRecorder] is off,
     * returns `null` instead.
     */
    fun smoothing(description: DemoPlaybackConfig): RecorderSmoothing? =
        when {
            config.smoothRecorder -> RecorderSmoothing.of(description.demoFrameInterval)
            else -> null
        }

    /**
     * Extracts the `uthooks` hook library and, on Windows, the `utinject` injector into [system], and returns their
     * paths. [system] names the files in the installation's `System` directory.
     */
    suspend fun extractHooks(system: Workspace): List<Path> = ARTIFACTS.extractTo(system::get)

    /**
     * Returns the environment the video capture runs under: the port and fps the hook library reads, plus the Linux
     * environment on Linux.
     */
    fun environment(
        port: Int,
        hooks: List<Path>,
    ): Map<String, String> =
        buildMap {
            this["UTRECORDER_PORT"] = port.toString()
            this["UTRECORDER_FPS"] = config.fps.toString()
            if (installation.platform.operatingSystem != WINDOWS) putAll(linuxEnvironment(hooks))
        }

    /**
     * Returns the Linux part of the environment: the hook library of [hooks] preloaded, and the llvmpipe tuning of a
     * software-rendered capture.
     */
    private fun linuxEnvironment(hooks: List<Path>): Map<String, String> =
        buildMap {
            hookLibrary(hooks)?.let { this["LD_PRELOAD"] = it.absolutePathString() }
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
     * Returns the unstarted run that plays [description]: without sound, and playing the exec script. On Windows the
     * injector of [hooks] starts the game executable copy, and on Linux the copy starts directly. The HUD is hidden
     * when the demo loads a Bunny Track mod older than `UTBT_BTMod_v18`.
     */
    fun process(
        description: DemoPlaybackConfig,
        port: Int,
        hooks: List<Path>,
    ): UtProcess =
        playback.process(VIDEO_CAPTURE, description, config.viewport()) {
            injector(hooks)?.let { injector ->
                program = injector
                programArguments += hookLibrary(hooks)?.absolutePathString().orEmpty()
            }
            exec += execScript(description)
            sound = false
            environment += this@FfmpegVideoCapture.environment(port, hooks)
            if (hidesHud(description)) userIni { this["Botpack.ChallengeHUD", "bHideHUD"] = "True" }
        }

    /**
     * Returns the injector of [hooks] on Windows. On Linux, returns `null` instead.
     */
    private fun injector(hooks: List<Path>): Path? =
        when (installation.platform.operatingSystem) {
            WINDOWS -> hooks.firstOrNull { it.name.endsWith("utinject.exe") }
            else -> null
        }

    /**
     * Returns the lines the capture adds to the exec script after the line that plays [description]'s demo: the
     * console's trim command `DemoFrames <fps>`, `TimerReset <run start>`, which starts the trim at the Bunny Track
     * timer reset nearest the run start, `HideMessages`, which keeps chat and progress messages off the recorded HUD,
     * and the command of the recorder's [smoothing] when [VideoCaptureConfig.smoothRecorder] is on. If the demo shows
     * no run start, `TimerReset` is left out.
     */
    fun execScript(description: DemoPlaybackConfig): List<String> =
        buildList {
            this += "DemoFrames %.6f".format(Locale.ROOT, config.fps.toDouble())
            description.runStart?.let {
                this += "TimerReset %.6f".format(Locale.ROOT, it.toDouble(DurationUnit.SECONDS))
            }
            this += "HideMessages"
            smoothing(description)?.let { this += it.command() }
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
         * The base name of the hook library on either platform.
         */
        private const val HOOK_LIBRARY = "uthooks"

        /**
         * The oldest version of the Bunny Track mod whose HUD the capture records.
         */
        private const val BT_MOD_HUD_VERSION = 18

        /**
         * The native artifacts the hooks module embeds in its jar.
         */
        private val ARTIFACTS = EmbeddedArtifacts("native")

        /**
         * The name of a Bunny Track mod package, capturing its version.
         */
        private val BT_MOD = Regex("""UTBT_BTMod_v(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * Returns whether the demo of [description] loads a Bunny Track mod older than [BT_MOD_HUD_VERSION].
         */
        private fun hidesHud(description: DemoPlaybackConfig): Boolean =
            description.packages
                .mapNotNull { BT_MOD.matchEntire(it.name) }
                .any { it.groupValues[1].toInt() < BT_MOD_HUD_VERSION }

        /**
         * Returns the hook library of [hooks], or `null` if [hooks] holds none.
         */
        private fun hookLibrary(hooks: List<Path>): Path? = hooks.firstOrNull { it.name.contains(HOOK_LIBRARY) }
    }
}
