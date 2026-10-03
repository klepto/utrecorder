package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.http.Downloader
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.part
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.core.RecorderStage.PreparingInstallation
import dev.klepto.utrecorder.core.RecorderStage.ResolvingPackages
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
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Recorder of Unreal Tournament demos in one installation.
 *
 * Create one with the constructor. Use [playDemo] to play a demo and read its marks, and [record] to record a demo
 * into an mp4 with a thumbnail.
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
     * installation. The staged copy of the demo is removed afterwards, whichever way the run ends. The marks are returned even when the game exits with a nonzero exit code.
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
                    gameIni {
                        description.gameIniValues().forEach { (section, values) ->
                            values.forEach { (key, value) -> this[section, key] = value }
                        }
                    }
                }
            DemoPlaybackMarks.parse(process.launch(timeout ?: description.timeout))
        }
    }

    /**
     * Returns the mp4 recorded from the demo at [url]. Its thumbnail lies beside it, named as the mp4 with a `.jpg`
     * extension.
     *
     * The demo is downloaded into the workspace of this recording and is kept there with the rest of its files.
     * [onProgress] receives the [FetchingDemo] stage first. Everything else is as for the [record] that takes a demo
     * file. Several recordings may share one installation.
     *
     * @param name The name of the mp4 and of its thumbnail without their extensions. If not provided, uses the file name
     * of the downloaded demo without its `.dem` extension.
     * @throws IllegalStateException If the demo cannot be fetched or resolved, if a capture fails the recording, if the
     * length of the mp4 does not match the run time the demo reports, or if the thumbnail cannot be written. A failed
     * audio capture always fails the recording.
     * @throws UtPackageException If no source serves one of the packages the demo names at the GUID it recorded.
     */
    suspend fun record(
        url: String,
        name: String? = null,
        onProgress: ProgressListener<RecorderStage> = {},
    ): Path =
        workspace("record", config = directories) {
            val demo = downloadDemo(url, FetchingDemo.start(onProgress))
            recordDemo(demo, name ?: demo.name.substringBeforeLast(DEMO_EXTENSION), onProgress)
        }

    /**
     * Returns the demo at [url] downloaded into this workspace, named after the last non-empty segment of the path of
     * [url].
     *
     * [onProgress] receives how much of the demo is downloaded, and `1.0` once the download is done.
     *
     * @throws IllegalArgumentException If [url] names no demo file.
     */
    private suspend fun Workspace.downloadDemo(
        url: String,
        onProgress: ProgressListener<Unit>,
    ): Path {
        val demo = directory / demoFileName(url)
        Downloader(url, timeout = 10.minutes, directories = directories).download(demo, onProgress)
        onProgress(Progress(1.0))
        return demo
    }

    /**
     * Returns the mp4 `<name>.mp4` recorded from the demo file at [demo]. Its thumbnail `<name>.jpg` lies beside it.
     *
     * The installation is provisioned and its maps are decompressed when either is missing. Every package the demo
     * names is installed at the GUID the demo recorded. The recorder's console package and HUD layout are installed
     * too. The mp4, its thumbnail and the files of both captures are kept in a workspace of their own, whether the recording succeeds
     * or fails. Several recordings may share one installation.
     *
     * [onProgress] receives each [RecorderStage] after [FetchingDemo] as it starts, and an estimate of how much of the
     * stage is done while it runs. It is called from any thread, and often, so it must return quickly.
     *
     * @param name The name of the mp4 and of its thumbnail without their extensions. If not provided, uses the file name
     * of [demo] without its `.dem` extension.
     * @throws IllegalStateException If the demo cannot be resolved, if a capture fails the recording, if the length of
     * the mp4 does not match the run time the demo reports, or if the thumbnail cannot be written. A failed audio
     * capture always fails the recording.
     * @throws UtPackageException If no source serves one of the packages the demo names at the GUID it recorded.
     */
    suspend fun record(
        demo: Path,
        name: String = demo.name.substringBeforeLast(DEMO_EXTENSION),
        onProgress: ProgressListener<RecorderStage> = {},
    ): Path = workspace("record", config = directories) { recordDemo(demo, name, onProgress) }

    /**
     * Returns the mp4 `<name>.mp4` recorded from the demo file at [demo], with its thumbnail `<name>.jpg` beside it,
     * inside this workspace.
     */
    private suspend fun Workspace.recordDemo(
        demo: Path,
        name: String,
        onProgress: ProgressListener<RecorderStage>,
    ): Path {
        val preparing = PreparingInstallation.start(onProgress)
        UtInstaller(config.ut).install(preparing.part(0.0, 0.8))
        val installation = UtInstallation(config.ut.gameDirectory, config.ut)
        DemoPlaybackUscript(installation).install()
        DemoPlaybackResources.installHudLayout(installation)
        UtMapInstaller(installation).install(preparing.part(0.8, 1.0))
        val parsed = UtDemo.read(demo)
        UtPackageInstaller(parsed.packages, installation, config.ut).install(ResolvingPackages.start(onProgress))
        val pipeline = RecorderPipeline(config.video, config.audio, installation, directories)
        return pipeline.record(demo, name, parsed.cap?.runTime, onProgress)
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

        /**
         * The characters a demo file name must not hold.
         */
        private const val UNSAFE_NAME_CHARACTERS = "/\\:\u0000"

        /**
         * Returns the last non-empty segment of the path of [url], URL-decoded. The query and fragment of [url] play no
         * part.
         *
         * @throws IllegalArgumentException If the path of [url] has no non-empty segment, or if the decoded segment is
         * `.` or `..` or holds a path separator, a `:` or a NUL character.
         */
        private fun demoFileName(url: String): String {
            val name =
                URI(url)
                    .rawPath
                    .orEmpty()
                    .split('/')
                    .lastOrNull(String::isNotEmpty)
                    ?.let { URLDecoder.decode(it.replace("+", "%2B"), UTF_8) }
            require(name != null && name != "." && name != ".." && name.none { it in UNSAFE_NAME_CHARACTERS }) {
                "Demo URL $url names no file"
            }
            return name
        }
    }
}
