package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.TimestampId
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtProcess
import dev.klepto.utrecorder.ut99.UtProcessBuilder
import dev.klepto.utrecorder.ut99.demo.UtDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Playback of a demo through the recorder console in the `System` directory of an installation.
 *
 * Create one with the constructor, run a playback with [play], and describe the run that plays it with [process].
 *
 * @property installation The installation the demo is staged into.
 * @property directories The directories the workspace of a playback is kept under.
 */
class DemoPlayback(
    private val installation: UtInstallation,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) {
    /**
     * Returns the result of [block], run while [demo] is staged in the installation's `System` directory.
     *
     * [block] launches the run that plays the staged demo, described by the [DemoPlaybackConfig] it receives. The
     * staged demo is named [prefix] followed by a unique id. The demo is read before anything is staged. The staged demo
     * is removed however [block] ends.
     *
     * @throws java.nio.file.NoSuchFileException If no file exists at [demo].
     * @throws IllegalStateException If the demo does not parse.
     */
    suspend fun <T> play(
        demo: Path,
        prefix: String,
        block: suspend (DemoPlaybackConfig) -> T,
    ): T {
        val parsed = UtDemo.read(demo)
        val description =
            DemoPlaybackConfig.of(
                prefix + IDS.next(),
                parsed.duration,
                parsed.packages,
                parsed.cap?.runStart,
                parsed.cap?.runStop,
            )
        val stagedDemo = installation.systemDirectory[description.demoFileName + ".dem"]
        try {
            demo.copy(stagedDemo) { "Demo file not found at $demo" }
            return block(description)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { stagedDemo.deleteIfExists() }
        }
    }

    /**
     * Returns the result of [block], run while [demo] is staged as for the [play] without a workspace, and a workspace
     * named [name] names its files in the installation's `System` directory.
     *
     * [block] receives the [DemoPlaybackConfig] of the run and the workspace.
     */
    suspend fun <T> play(
        demo: Path,
        prefix: String,
        name: String,
        block: suspend (DemoPlaybackConfig, Workspace) -> T,
    ): T =
        play(demo, prefix) { description ->
            workspace(name, installation.systemDirectory, directories) { system -> block(description, system) }
        }

    /**
     * Returns the unstarted run named [name] that plays [description] windowed at [viewport].
     *
     * The run starts from the playback inis of [DemoPlaybackResources] on the `Entry` map, with the values of
     * [description] in its game ini, and its exec script plays the demo on its own clock with `?timebased`.
     * [configure] sets the rest of the run after that. Lines it adds to `exec` run after the line that plays the demo.
     */
    fun process(
        name: String,
        description: DemoPlaybackConfig,
        viewport: DemoPlaybackViewport,
        configure: UtProcessBuilder.() -> Unit,
    ): UtProcess =
        installation.process(name) {
            map = "Entry"
            exec = listOf("${description.execLine}?timebased")
            windowed = true
            baseGameIni = DemoPlaybackResources.gameIni
            baseUserIni = DemoPlaybackResources.userIni
            gameIni { setGameValues(description, viewport) }
            configure()
        }

    /**
     * Sets the viewport manager, the values of [description] and the values of [viewport].
     */
    private fun UtIni.setGameValues(
        description: DemoPlaybackConfig,
        viewport: DemoPlaybackViewport,
    ) {
        this["Engine.Engine", "ViewportManager"] = installation.platform.client
        setValues(description.gameIniValues())
        setValues(viewport.gameIniValues())
    }

    private companion object {
        /**
         * The source of the ids that keep the names of staged demos apart.
         */
        val IDS = TimestampId()

        /**
         * Sets [values], given by section.
         */
        fun UtIni.setValues(values: Map<String, Map<String, String>>) {
            values.forEach { (section, entries) ->
                entries.forEach { (key, value) -> this[section, key] = value }
            }
        }
    }
}
