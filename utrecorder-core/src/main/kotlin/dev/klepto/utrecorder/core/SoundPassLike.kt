package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.Workspace
import java.nio.file.Path

/**
 * The pieces of [SoundPass] the session reaches, narrow so unit tests hand a stand-in.
 */
interface SoundPassLike {
    /**
     * Plays [demo] with sound on and returns the [SoundTrack] the pass produced, placed by its own marks.
     */
    fun record(
        demo: Path,
        workspace: Workspace,
    ): SoundTrack
}
