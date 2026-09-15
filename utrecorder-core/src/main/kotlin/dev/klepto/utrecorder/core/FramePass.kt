package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.os.OperatingSystem
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtInstance
import dev.klepto.utrecorder.ut99.demo.UtDemo
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name

/**
 * The frame pass of a recording: UT99 launched through the shim, its clock stepped one frame at a time, streaming
 * every rendered frame to a [FrameRecorder] listening on the loopback.
 *
 * @property config The recording the pass runs for, carrying the frame size, the fps and the shim environment.
 * @property installation The installation the pass runs in.
 * @property hooksPaths The hook artifacts the provision stage extracted into the workspace.
 * @property directories The directories the pass stages its demo in.
 */
class FramePass(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val hooksPaths: List<Path>,
    private val directories: Directories = Directories.DEFAULT,
) : FramePassLike {
    /**
     * Plays [demo] through the shim into [recorder], whose capture the shim cut at the source, and returns the marks
     * the pass logged, read for the audio placement.
     *
     * Starts the capture listening, stages the demo into the installation's `System` directory and plays it windowed
     * and soundless under the shim's environment, killed once [RecorderConfig.frameTimeout] has passed. The staged
     * copy is removed whichever way the run ends.
     *
     * @throws IllegalStateException when the pass logs no marks, naming the missing mark and carrying the exit code.
     */
    override fun record(
        demo: Path,
        recorder: FrameRecorder,
        workspace: Workspace,
    ): DemoMarks {
        val demoDuration = UtDemo.read(demo).duration
        val description = PlaybackDescription.of(STAGED_DEMO_PREFIX + IDS.next(), demoDuration, config)
        val stagedDemo = installation.systemDirectory[description.stagedDemoName + DEMO_EXTENSION]
        try {
            demo.copy(stagedDemo) { "Demo file not found at $demo" }
            val result = launch(workspace, description, recorder.start()).start().await(config.frameTimeout)
            return try {
                DemoMarks.parse(result.log)
            } catch (failure: IllegalStateException) {
                throw IllegalStateException("${failure.message} (exit code ${result.exitCode})", failure)
            }
        } finally {
            stagedDemo.deleteIfExists()
        }
    }

    /**
     * The environment the frame pass runs under: the port and fps the shim reads, the hook library preloaded on
     * Linux, and the llvmpipe tuning of a software-rasterised pass.
     */
    fun environment(port: Int): Map<String, String> =
        buildMap {
            put("UTRECORDER_PORT", port.toString())
            put("UTRECORDER_FPS", config.fps.toString())
            if (installation.platform.operatingSystem != OperatingSystem.WINDOWS) {
                hooksPaths
                    .firstOrNull { it.name.startsWith(HOOK_LIBRARY_PREFIX) }
                    ?.let { put("LD_PRELOAD", it.absolutePathString()) }
                put("LP_NUM_THREADS", threads().toString())
                if (config.renderGlthread) put("mesa_glthread", "true")
                if (config.renderNoError) put("MESA_NO_ERROR", "1")
                if (config.renderVectorWidth.isNotBlank()) put("LP_NATIVE_VECTOR_WIDTH", config.renderVectorWidth)
            }
        }

    /**
     * The unstarted run the pass plays its demo through: the injector wrapping the game executable copy on Windows,
     * the executable copy itself on Linux, the exec script the console plays and the windowed run. Reached the way
     * [UtInstance] is reached everywhere; [record] starts and awaits it, tests read the run it describes.
     */
    fun launch(
        workspace: Workspace,
        description: PlaybackDescription,
        port: Int,
    ): UtInstance {
        val instance =
            installation.instance(FRAME_PASS) {
                if (installation.platform.operatingSystem == OperatingSystem.WINDOWS) {
                    hooksPaths
                        .firstOrNull { it.name == INJECTOR_SUFFIX }
                        ?.let { injector ->
                            executable = injector
                            arguments +=
                                hooksPaths
                                    .firstOrNull { it.name.startsWith(HOOK_LIBRARY_PREFIX) }
                                    ?.absolutePathString()
                                    .orEmpty()
                        }
                }
                exec = execScript(description)
                sound = false
                windowed = true
                environment += this@FramePass.environment(port)
                gameIni {
                    val values = RecordingDescription.framePass(config).clientValues()
                    values.forEach { (key, value) ->
                        this[RecordingDescription.WINDOWS_CLIENT, key] = value
                        this[RecordingDescription.SDL_CLIENT, key] = value
                    }
                }
            }

        return instance
    }

    private fun threads(): Int =
        when {
            config.renderThreads > 0 -> config.renderThreads
            else -> Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }

    /**
     * The lines of the exec script the pass plays: the [PlaybackDescription.execLine] pinned to the stepped clock
     * with `?timebased`, and the console's trim command holding `DemoFrames <fps>`.
     */
    fun execScript(description: PlaybackDescription): List<String> =
        listOf(
            "${description.execLine}?timebased",
            "DemoFrames ${config.fps.toDouble().format()}",
        )

    /**
     * The frame rate [value] written the way the console's exec command holds one.
     */
    private fun Double.format(): String = String.format(Locale.ROOT, "%.6f", this)

    companion object {
        /**
         * The name the frame pass's instance workspace carries.
         */
        private const val FRAME_PASS = "frame-pass"

        /**
         * The prefix the frame pass stages a demo under.
         */
        private const val STAGED_DEMO_PREFIX = "fp-"

        /**
         * The suffix a staged demo file carries.
         */
        private const val DEMO_EXTENSION = ".dem"

        /**
         * The prefix of the hook library file name, on either platform's artifact.
         */
        private const val HOOK_LIBRARY_PREFIX = "uthooks"

        /**
         * The suffix of the injector's file name.
         */
        private const val INJECTOR_SUFFIX = "utinject.exe"

        /**
         * The source of the ids the stage names carry.
         */
        private val IDS = TimestampId()
    }
}
