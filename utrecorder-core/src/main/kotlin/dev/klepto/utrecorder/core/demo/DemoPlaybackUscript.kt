package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.replaceIfChanged
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path

/**
 * The recorder's UnrealScript package, `UTRecorder.u`, in the `System` directory of an installation.
 *
 * The package holds the console that plays a demo, exits the process when the demo ends, and logs the start and end
 * marks. The console runs the exec commands of a run, the seek to the run start included, whether `bAutoExit` is on or
 * not. It logs the start and end marks, unlinks the warp zones and exits only when the game ini of a run turns on its
 * `bAutoExit`.
 *
 * Create one with the constructor.
 *
 * @property installation The installation the package is written into.
 */
class DemoPlaybackUscript(
    private val installation: UtInstallation,
) {
    /**
     * Returns the paths of the package's artifacts after writing them into the installation's `System` directory.
     *
     * A file of the same name is replaced only when it holds other bytes.
     */
    suspend fun install(): List<Path> =
        ARTIFACTS.names.map { name ->
            installation.systemDirectory[name].replaceIfChanged(ARTIFACTS.read(name), installation.config.directoriesConfig)
        }

    private companion object {
        /**
         * The UnrealScript artifacts embedded on the classpath.
         */
        val ARTIFACTS = EmbeddedArtifacts("unrealscript")
    }
}
