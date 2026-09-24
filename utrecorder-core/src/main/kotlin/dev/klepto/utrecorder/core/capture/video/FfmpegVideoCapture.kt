package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.demo.DemoPlayback
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.core.demo.DemoPlaybackMarks
import dev.klepto.utrecorder.core.media.FfmpegMediaTool
import dev.klepto.utrecorder.core.media.MediaTool
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import dev.klepto.utrecorder.ut99.UtProcessResult
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
 * demo windowed and without sound. The run is killed once [VideoCaptureConfig.timeout] has passed, counted over the
 * whole run. The frames are encoded into a video file in a workspace of the capture's own. A run that logs no marks
 * fails with an [IllegalStateException] naming the missing mark and the exit code.
 *
 * @property config The video capture configuration, carrying the frame size, the fps, the timeout and the renderer
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

    override suspend fun record(demo: Path): VideoTrack =
        workspace(VIDEO_CAPTURE, config = directories) { output ->
            val encoder = FrameEncoder(config, DemoPlaybackConfig.STARTUP_TIMEOUT, output[VIDEO_NAME], ffmpeg)
            val marks =
                playback.play(demo, STAGED_DEMO_PREFIX, VIDEO_CAPTURE) { description, system ->
                    DemoPlaybackMarks.parse(play(description, extractHooks(system), encoder))
                }
            track(encoder.target, marks)
        }

    /**
     * Returns the result of the run of [description] with [hooks], launched inside [encoder]'s block.
     */
    private suspend fun play(
        description: DemoPlaybackConfig,
        hooks: List<Path>,
        encoder: FrameEncoder,
    ): UtProcessResult {
        val (result, _) = encoder.encode { process(description, port, hooks).launch(config.timeout) }
        return result
    }

    /**
     * Returns the video track in [videoFile], ending at the end mark of [marks].
     */
    private suspend fun track(
        videoFile: Path,
        marks: DemoPlaybackMarks,
    ): VideoTrack =
        VideoTrack(
            file = videoFile,
            length = ffmpeg.duration(videoFile),
            demoEnd = marks.end.demoTime.toDouble(DurationUnit.SECONDS),
        )

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
     * injector of [hooks] starts the game executable copy, and on Linux the copy starts directly.
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
            exec += execScript()
            sound = false
            environment += this@FfmpegVideoCapture.environment(port, hooks)
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
     * Returns the lines the capture adds to the exec script after the line that plays the demo: the console's trim
     * command `DemoFrames <fps>`, and `HideMessages`, which keeps chat and progress messages off the recorded HUD.
     */
    fun execScript(): List<String> =
        listOf(
            "DemoFrames %.6f".format(Locale.ROOT, config.fps.toDouble()),
            "HideMessages",
        )

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
         * The native artifacts the hooks module embeds in its jar.
         */
        private val ARTIFACTS = EmbeddedArtifacts("native")

        /**
         * Returns the hook library of [hooks], or `null` if [hooks] holds none.
         */
        private fun hookLibrary(hooks: List<Path>): Path? = hooks.firstOrNull { it.name.contains(HOOK_LIBRARY) }
    }
}
