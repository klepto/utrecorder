package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * The recorder's UnrealScript package, `UTRecorder.u`, and its settings file in the `System` directory of an
 * installation.
 *
 * The package holds the console that plays a demo, exits the process when the demo ends, and logs the start and end
 * marks. The console does nothing until [configure] writes its settings.
 *
 * Create one with the constructor.
 *
 * @property installation The installation the package is written into.
 */
class DemoPlaybackUscript(
    private val installation: UtInstallation,
) {
    /**
     * The path of the console's settings file in the installation's `System` directory.
     */
    private val iniPath: Path = installation.systemDirectory[INI_NAME]

    /**
     * Returns the paths of the package's artifacts after writing them into the installation's `System` directory.
     * Files of the same name are overwritten.
     */
    suspend fun install(): List<Path> = ARTIFACTS.extractTo(installation.systemDirectory)

    /**
     * Returns the path of the console's settings file after writing [ini] to it.
     */
    suspend fun configure(ini: UtIni): Path = iniPath.also { ini.write(it) }

    /**
     * Deletes the console's settings file from the installation, if it exists.
     */
    suspend fun clear() {
        withContext(Dispatchers.IO) { iniPath.deleteIfExists() }
    }

    companion object {
        /**
         * The file name of the console's settings file, named after the package.
         */
        const val INI_NAME = "UTRecorder.ini"

        /**
         * The UnrealScript artifacts embedded on the classpath.
         */
        private val ARTIFACTS = EmbeddedArtifacts("unrealscript")
    }
}
