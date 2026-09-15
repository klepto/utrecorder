package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.Workspace
import java.nio.file.Path

/**
 * The pieces of [FramePass] the session reaches, narrow so unit tests hand a stand-in.
 */
interface FramePassLike {
    /**
     * Plays [demo] through the shim into [recorder] and returns the marks the pass logged, read for the audio
     * placement.
     */
    fun record(
        demo: Path,
        recorder: FrameRecorder,
        workspace: Workspace,
    ): DemoMarks
}
