package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtProcessBuilder
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

/**
 * The `uthooks` hook library and, on Windows, the `utinject` injector, which a demo playback loads into the game.
 *
 * Use [extract] to write them into the `System` directory of an installation, and [inject] to make a run load the hook
 * library.
 */
object DemoPlaybackHooks {
    /**
     * The base name of the hook library on either platform.
     */
    private const val HOOK_LIBRARY = "uthooks"

    /**
     * The file name of the injector.
     */
    private const val INJECTOR = "utinject.exe"

    /**
     * The native artifacts the hooks module embeds in its jar.
     */
    private val ARTIFACTS = EmbeddedArtifacts("native")

    /**
     * Extracts the hook library and, on Windows, the injector into [system], and returns their paths. [system] names
     * the files in the installation's `System` directory.
     */
    suspend fun extract(system: Workspace): List<Path> = ARTIFACTS.extractTo(system::get)

    /**
     * Makes the run that [builder] describes load the hook library of [hooks].
     *
     * On Windows the injector of [hooks] starts the game executable copy and loads the library into it. On Linux the
     * library is preloaded. If [hooks] holds no hook library, leaves [builder] unchanged.
     */
    fun inject(
        builder: UtProcessBuilder,
        hooks: List<Path>,
    ) {
        val library = hooks.firstOrNull { it.name.contains(HOOK_LIBRARY) } ?: return
        when (builder.installation.platform.operatingSystem) {
            WINDOWS -> hooks.firstOrNull { it.name.endsWith(INJECTOR) }?.let { builder.startThrough(it, library) }
            else -> builder.environment["LD_PRELOAD"] = library.absolutePathString()
        }
    }

    /**
     * Makes the run start through [injector], which loads [library] into the game executable copy.
     */
    private fun UtProcessBuilder.startThrough(
        injector: Path,
        library: Path,
    ) {
        program = injector
        programArguments += library.absolutePathString()
    }
}
