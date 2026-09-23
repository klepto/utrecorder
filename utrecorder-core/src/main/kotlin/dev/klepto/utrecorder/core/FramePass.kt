package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.os.OperatingSystem
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import dev.klepto.utrecorder.ut99.UtProcessResult
import dev.klepto.utrecorder.ut99.demo.UtDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * @property directories The directories the pass's workspace is kept under.
 */
class FramePass(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) : FramePassLike {
    /**
     * Plays [demo] through the shim, the capture driven by [recorder]'s own [FrameRecorder.record] block, whose
     * capture the shim cuts at the source, and returns the marks the pass logged, read for the audio placement.
     *
     * Stages the demo into the installation's `System` directory beside the console settings the run reads, extracts
     * the hooks through [hooks] into a workspace of the pass, and plays the demo windowed and soundless under the
     * shim's environment, killed once [RecorderConfig.frameTimeout] has passed — the timeout covers the whole run.
     * [afterPlay] runs once the game has finished playing, still inside [recorder]'s block, before the capture is
     * finalised. The staged copy and the console settings are removed whichever way the run ends.
     *
     * @throws IllegalStateException when the pass logs no marks, naming the missing mark and carrying the exit code.
     */
    override suspend fun record(
        demo: Path,
        recorder: FrameRecorder,
        workspace: Workspace,
        afterPlay: suspend () -> Unit,
    ): DemoMarks {
        val demoDuration = UtDemo.read(demo).duration
        val description = PlaybackDescription.of(STAGED_DEMO_PREFIX + IDS.next(), demoDuration, config)
        val recorderPackage = RecorderPackage(installation)
        val stagedDemo = installation.systemDirectory[description.stagedDemoName + DEMO_EXTENSION]
        return workspace(FRAME_PASS, installation.systemDirectory, directories) { pass ->
            try {
                demo.copy(stagedDemo) { "Demo file not found at $demo" }
                withContext(Dispatchers.IO) { recorderPackage.configure(description.recorderIni()) }
                val hooks = hooks(pass)
                val (result, _) =
                    recorder.record {
                        val played = process(description, port, hooks).launch(config.frameTimeout)
                        afterPlay()
                        played
                    }
                marks(result)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    stagedDemo.deleteIfExists()
                    recorderPackage.clear()
                }
            }
        }
    }

    /**
     * Returns the marks [result] logged, naming its exit code when it logged none.
     */
    private fun marks(result: UtProcessResult): DemoMarks =
        try {
            DemoMarks.parse(result.log)
        } catch (failure: IllegalStateException) {
            throw IllegalStateException("${failure.message} (exit code ${result.exitCode})", failure)
        }

    /**
     * Extracts the `uthooks` shim and, on Windows, the `utinject` launcher into [pass] and returns their paths, named
     * by [pass] in the installation's `System` directory.
     */
    suspend fun hooks(pass: Workspace): List<Path> = ARTIFACTS.extractTo(pass::get)

    /**
     * The environment the frame pass runs under: the port and fps the shim reads, the hook library of [hooks]
     * preloaded on Linux, and the llvmpipe tuning of a software-rasterised pass.
     */
    fun environment(
        port: Int,
        hooks: List<Path>,
    ): Map<String, String> =
        buildMap {
            put("UTRECORDER_PORT", port.toString())
            put("UTRECORDER_FPS", config.fps.toString())
            if (installation.platform.operatingSystem != OperatingSystem.WINDOWS) {
                hooks
                    .firstOrNull { it.name.contains(HOOK_LIBRARY) }
                    ?.let { put("LD_PRELOAD", it.absolutePathString()) }
                put("LP_NUM_THREADS", threads().toString())
                if (config.renderGlthread) put("mesa_glthread", "true")
                if (config.renderNoError) put("MESA_NO_ERROR", "1")
                if (config.renderVectorWidth.isNotBlank()) put("LP_NATIVE_VECTOR_WIDTH", config.renderVectorWidth)
            }
        }

    /**
     * The unstarted run the pass plays its demo through: the injector of [hooks] wrapping the game executable copy on
     * Windows, the executable copy itself on Linux, the exec script the console plays and the windowed run. [record]
     * launches it, tests read the run it describes.
     */
    fun process(
        description: PlaybackDescription,
        port: Int,
        hooks: List<Path>,
    ): UtProcess {
        val process =
            installation.process(FRAME_PASS) {
                if (installation.platform.operatingSystem == OperatingSystem.WINDOWS) {
                    hooks
                        .firstOrNull { it.name.endsWith(INJECTOR) }
                        ?.let { injector ->
                            program = injector
                            programArguments +=
                                hooks
                                    .firstOrNull { it.name.contains(HOOK_LIBRARY) }
                                    ?.absolutePathString()
                                    .orEmpty()
                        }
                }
                exec = execScript(description)
                sound = false
                windowed = true
                environment += this@FramePass.environment(port, hooks)
                baseGameIni = RecorderSettings.gameIni
                baseUserIni = RecorderSettings.userIni
                gameIni {
                    this["Engine.Engine", "ViewportManager"] = description.client(installation.platform)
                    this["Engine.Engine", "Console"] = description.console

                    val values = RecordingDescription.framePass(config).clientValues()
                    values.forEach { (key, value) ->
                        this[RecordingDescription.WINDOWS_CLIENT, key] = value
                        this[RecordingDescription.SDL_CLIENT, key] = value
                    }
                }
            }

        return process
    }

    private fun threads(): Int =
        when {
            config.renderThreads > 0 -> config.renderThreads
            else -> Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }

    /**
     * The lines of the exec script the pass plays: the [PlaybackDescription.execLine] pinned to the stepped clock
     * with `?timebased`, the console's trim command holding `DemoFrames <fps>`, and `HideMessages` keeping chat and
     * progress messages off the recorded HUD.
     */
    fun execScript(description: PlaybackDescription): List<String> =
        listOf(
            "${description.execLine}?timebased",
            "DemoFrames ${config.fps.toDouble().format()}",
            HIDE_MESSAGES,
        )

    /**
     * The frame rate [value] written the way the console's exec command holds one.
     */
    private fun Double.format(): String = String.format(Locale.ROOT, "%.6f", this)

    companion object {
        /**
         * The console command that keeps chat and progress messages off the recorded HUD.
         */
        private const val HIDE_MESSAGES = "HideMessages"

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
         * The base name of the hook library, on either platform's artifact.
         */
        private const val HOOK_LIBRARY = "uthooks"

        /**
         * The name of the injector's artifact.
         */
        private const val INJECTOR = "utinject.exe"

        /**
         * The namespace the hook artifacts sit in.
         */
        private const val NAMESPACE = "native"

        /**
         * The native artifacts the hooks module embeds in its jar.
         */
        private val ARTIFACTS = EmbeddedArtifacts(NAMESPACE)

        /**
         * The source of the ids the stage names carry.
         */
        private val IDS = TimestampId()
    }
}
