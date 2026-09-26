package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.part
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.core.RecorderStage.PreparingInstallation
import dev.klepto.utrecorder.core.RecorderStage.ResolvingPackages
import dev.klepto.utrecorder.core.demo.DemoFetcher
import dev.klepto.utrecorder.core.demo.DemoPlayback
import dev.klepto.utrecorder.core.demo.DemoPlaybackMarks
import dev.klepto.utrecorder.core.demo.DemoPlaybackResources
import dev.klepto.utrecorder.core.demo.DemoPlaybackUscript
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtInstaller
import dev.klepto.utrecorder.ut99.UtMapInstaller
import dev.klepto.utrecorder.ut99.UtPackageException
import dev.klepto.utrecorder.ut99.UtPackageInstaller
import dev.klepto.utrecorder.ut99.demo.UtDemo
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.time.Duration

/**
 * Recorder of Unreal Tournament demos in one installation.
 *
 * Create one with the constructor. Use [playDemo] to play a demo and read its marks, and [record] to record a demo
 * into an mp4.
 *
 * @property config The configuration demos are played and recorded with.
 * @property directories The directories the workspaces are kept under.
 */
class Recorder(
    private val config: RecorderConfig = RecorderConfig(),
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) {
    /**
     * Returns the marks of one playback of the demo file at [demo], at normal speed with sound off.
     *
     * The demo is read before the game is launched. The recorder's console package is installed into the
     * installation. The staged copy of the demo and `UTRecorder.ini` are removed afterwards, whichever way the run
     * ends. The marks are returned even when the game exits with a nonzero exit code.
     *
     * @param timeout How long the game may run before it is killed. If not provided, derives it from the length of the
     * demo.
     * @throws java.nio.file.NoSuchFileException If no file exists at [demo].
     * @throws IllegalStateException If the demo does not parse, or if the run log holds no start mark or no end mark.
     * The message of a missing mark names the mark and the exit code.
     */
    suspend fun playDemo(
        demo: Path,
        timeout: Duration? = null,
    ): DemoPlaybackMarks {
        val installation = UtInstallation(config.ut.gameDirectory, config.ut)
        return DemoPlayback(installation).play(demo, STAGED_DEMO_PREFIX) { description ->
            DemoPlaybackUscript(installation).install()
            val process =
                installation.process("play-demo") {
                    exec = listOf(description.execLine)
                    sound = false
                    windowed = true
                    gameIni { this["Engine.Engine", "Console"] = description.console }
                }
            DemoPlaybackMarks.parse(process.launch(timeout ?: description.timeout))
        }
    }

    /**
     * Returns the mp4 recorded from the demo at [url].
     *
     * The demo is downloaded into the workspace of this recording, so it is deleted along with it. [onProgress]
     * receives the [FetchingDemo] stage first. Everything else is as for the [record] that takes a demo file. Several
     * recordings may share one installation.
     *
     * @param name The name of the mp4 without its extension. If not provided, uses the file name of the downloaded demo
     * without its `.dem` extension.
     * @throws IllegalStateException If the demo cannot be fetched or resolved, or if a capture fails the recording. A
     * failed audio capture always fails the recording.
     * @throws UtPackageException If no source serves one of the packages the demo names at the GUID it recorded.
     */
    suspend fun record(
        url: String,
        name: String? = null,
        onProgress: (RecorderProgress) -> Unit = {},
    ): Path =
        workspace("record", config = directories) {
            val demo = DemoFetcher(directories).fetch(url, directory, FetchingDemo.start(onProgress))
            recordDemo(demo, name ?: demo.name.substringBeforeLast(DEMO_EXTENSION), onProgress)
        }

    /**
     * Returns the mp4 `<name>.mp4` recorded from the demo file at [demo].
     *
     * The installation is provisioned and its maps are decompressed when either is missing. Every package the demo
     * names is installed at the GUID the demo recorded. The recorder's console package and HUD layout are installed
     * too. The mp4 and the files of both captures are kept in a workspace of their own, whether the recording succeeds
     * or fails. Several recordings may share one installation.
     *
     * [onProgress] receives each [RecorderStage] after [FetchingDemo] as it starts, and an estimate of how much of the
     * stage is done while it runs. It is called from any thread, and often, so it must return quickly.
     *
     * @param name The name of the mp4 without its extension. If not provided, uses the file name of [demo] without its
     * `.dem` extension.
     * @throws IllegalStateException If the demo cannot be resolved, or if a capture fails the recording. A failed audio
     * capture always fails the recording.
     * @throws UtPackageException If no source serves one of the packages the demo names at the GUID it recorded.
     */
    suspend fun record(
        demo: Path,
        name: String = demo.name.substringBeforeLast(DEMO_EXTENSION),
        onProgress: (RecorderProgress) -> Unit = {},
    ): Path = workspace("record", config = directories) { recordDemo(demo, name, onProgress) }

    /**
     * Returns the mp4 `<name>.mp4` recorded from the demo file at [demo], inside this workspace.
     */
    private suspend fun Workspace.recordDemo(
        demo: Path,
        name: String,
        onProgress: (RecorderProgress) -> Unit,
    ): Path {
        val preparing = PreparingInstallation.start(onProgress)
        UtInstaller(config.ut).install(preparing.part(0.0, 0.8))
        val installation = UtInstallation(config.ut.gameDirectory, config.ut)
        DemoPlaybackUscript(installation).install()
        DemoPlaybackResources.installHudLayout(installation)
        UtMapInstaller(installation).install(preparing.part(0.8, 1.0))
        val packages = UtDemo.read(demo).packages
        UtPackageInstaller(packages, installation, config.ut).install(ResolvingPackages.start(onProgress))
        return RecorderPipeline(config.video, config.audio, installation, directories).record(demo, name, onProgress)
    }

    companion object {
        /**
         * The prefix of the name a played demo is staged under.
         */
        private const val STAGED_DEMO_PREFIX = "ut-"

        /**
         * The extension of a demo file.
         */
        private const val DEMO_EXTENSION = ".dem"
    }
}
