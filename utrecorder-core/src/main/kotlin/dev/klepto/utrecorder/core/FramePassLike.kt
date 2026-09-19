package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.Workspace
import java.nio.file.Path

/**
 * The pieces of [FramePass] the session reaches, narrow so unit tests hand a stand-in.
 */
interface FramePassLike {
    /**
     * Plays [demo] through the shim, the capture driven by [recorder]'s own [FrameRecorder.record] block, and returns
     * the marks the pass logged, read for the audio placement.
     *
     * [afterPlay] runs once the game has finished playing but still inside [recorder]'s block, before the capture is
     * finalised, so a caller that must join other work against the same capture window — the sound pass's own thread
     * — can do so before [FrameRecorder.record]'s post-block checks run.
     */
    suspend fun record(
        demo: Path,
        recorder: FrameRecorder,
        workspace: Workspace,
        afterPlay: suspend () -> Unit = {},
    ): DemoMarks
}
