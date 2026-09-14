package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path

/**
 * The recording preparation of one demo: the hook artifacts and the OpenAL configuration extracted into the run's
 * instance workspace, where the passes' environments name them.
 *
 * @property installation The installation the passes run in, whose instance workspaces the files are extracted into.
 */
class RecordingProvision(
    private val installation: UtInstallation,
) {
    /**
     * Extracts the `uthooks` shim and, on Windows, the `utinject` launcher into [workspace] and returns their paths,
     * with the OpenAL wave configuration written beside the game binary.
     */
    fun hooks(
        workspace: Workspace,
        config: RecorderConfig,
    ): List<Path> = TODO()

    /**
     * Writes the OpenAL wave configuration into the instance workspace and returns the environment the sound pass
     * runs under, empty when the recording carries no audio.
     */
    fun openAl(config: RecorderConfig): Map<String, String> = TODO()
}
