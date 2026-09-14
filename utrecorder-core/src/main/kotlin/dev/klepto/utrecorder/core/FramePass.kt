package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path

/**
 * The frame pass of a recording: UT99 launched through the shim, its clock stepped one frame at a time, streaming
 * every rendered frame to a [FrameRecorder] listening on the loopback.
 *
 * @property config The recording the pass runs for, carrying the frame size, the fps and the shim environment.
 * @property installation The installation the pass runs in.
 * @property directories The directories the pass stages its demo in.
 */
class FramePass(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * Plays [demo] through the shim into [recorder], whose capture the shim cut at the source, and returns the marks
     * the pass logged, read for the audio placement.
     *
     * @throws IllegalStateException when the pass logs no marks.
     */
    fun record(
        demo: Path,
        recorder: FrameRecorder,
        workspace: Workspace,
    ): DemoMarks = TODO()

    /**
     * The environment the frame pass runs under: the port and fps the shim reads, the hook library preloaded on
     * Linux, and the llvmpipe tuning of a software-rasterised pass.
     */
    fun environment(port: Int): Map<String, String> = TODO()
}
