package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path

/**
 * One recording of a demo: the staged demo, the two passes and the mux, run inside a workspace, finishing at the mp4
 * in the output directory.
 *
 * Reached through [UtRecorder.record]; one recording per installation at a time.
 *
 * @property config The recording the passes and the mux are driven with.
 * @property installation The installation both passes run in.
 * @property directories The directories the recording caches its demo in and writes its mp4 to.
 */
class RecordingSession(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * Runs both passes against [demo], muxes their outputs into the mp4 named [name] and returns its path under the
     * output directory, the mp4 moved there before the [workspace] closes.
     *
     * @throws IllegalStateException when the frame pass or, when [RecorderConfig.audio] holds, the sound pass fails.
     */
    fun record(
        demo: Path,
        name: String,
        workspace: Workspace,
    ): Path = TODO()
}
