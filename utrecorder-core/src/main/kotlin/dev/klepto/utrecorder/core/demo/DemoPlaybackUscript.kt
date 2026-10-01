package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path

/**
 * The recorder's UnrealScript package, `UTRecorder.u`, in the `System` directory of an installation.
 *
 * The package holds the console that plays a demo, exits the process when the demo ends, and logs the start and end
 * marks. The console does nothing until the game ini of a run turns on its `bAutoExit`.
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
     * Files of the same name are overwritten.
     */
    suspend fun install(): List<Path> = ARTIFACTS.extractTo(installation.systemDirectory)

    private companion object {
        /**
         * The UnrealScript artifacts embedded on the classpath.
         */
        private val ARTIFACTS = EmbeddedArtifacts("unrealscript")
    }
}
