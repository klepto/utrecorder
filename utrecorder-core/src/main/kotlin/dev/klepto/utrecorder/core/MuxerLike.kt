package dev.klepto.utrecorder.core

import java.nio.file.Path

/**
 * The pieces of [Muxer] the session reaches, narrow so unit tests hand a stand-in.
 */
interface MuxerLike {
    /**
     * Merges [video] with [track] into [target] and returns [target], the video stream copied and the audio placed.
     */
    suspend fun merge(
        video: Path,
        track: SoundTrack,
        target: Path,
    ): Path
}
