package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.ut99.UtInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

/**
 * The recording preparation: the hook artifacts and the OpenAL configuration extracted into the run's instance
 * workspace, where the passes' environments name them.
 *
 * Construct one per recording, then extract the hook artifacts through [hooks] before the frame pass starts, and
 * provision the sound pass through [openAl].
 *
 * @property installation The installation the passes run in, whose instance workspace the files are extracted into.
 * @property config The recording the wave configuration is provisioned for.
 */
class RecordingProvision(
    val installation: UtInstallation,
    private val config: RecorderConfig,
) {
    /**
     * The instance workspace the hook artifacts and the wave configuration are extracted into, opened on first use
     * since opening one is disk IO and this class is constructed outside a suspend context.
     */
    private var workspaceInstance: Workspace? = null

    /**
     * Returns the instance workspace, opening it the first time either [hooks] or [openAl] is called.
     */
    private suspend fun workspace(): Workspace =
        workspaceInstance ?: Workspace.open(PROVISION, installation.systemDirectory).also { workspaceInstance = it }

    /**
     * Extracts the `uthooks` shim and, on Windows, the `utinject` launcher into the instance workspace and returns
     * their paths, with the OpenAL wave configuration written beside the game binary.
     */
    suspend fun hooks(): List<Path> = ARTIFACTS.extractTo(workspace().directory)

    /**
     * Writes the OpenAL wave configuration into the instance workspace and returns the environment the sound pass
     * runs under, empty when the recording carries no audio.
     */
    suspend fun openAl(): Map<String, String> {
        if (!config.audio) return emptyMap()
        val confPath = workspace().file(WAVE_CONF)
        withContext(Dispatchers.IO) { confPath.writeText(WAVE_CONF_CONTENT) }
        return mapOf(
            "ALSOFT_CONF" to confPath.absolutePathString(),
            "ALSOFT_DRIVERS" to "wave",
            "ALSOFT_LOGLEVEL" to "2",
        )
    }

    companion object {
        /**
         * The name the provision workspace carries.
         */
        private const val PROVISION = "provision"

        /**
         * The name of the OpenAL configuration the sound pass reads.
         */
        private const val WAVE_CONF = "alsoft.conf"

        /**
         * The content of the wave configuration: the wave driver, stereo int16 output, and the relative wave file
         * path beside the game binary the working directory is.
         */
        private const val WAVE_CONF_CONTENT =
            "[general]\ndrivers = wave\nfrequency = 44100\nchannels = stereo\nsample-type = int16\n\n[wave]\nfile = audio.wav\n"

        /**
         * The native artifacts the hooks module embeds in its jar.
         */
        private val ARTIFACTS = EmbeddedArtifacts(NAMESPACE)

        /**
         * The namespace the hook artifacts sit in.
         */
        private const val NAMESPACE = "native"
    }
}
