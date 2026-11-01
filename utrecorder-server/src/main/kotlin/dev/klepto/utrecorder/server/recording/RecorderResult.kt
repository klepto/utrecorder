package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.core.capture.video.VideoStream
import dev.klepto.utrecorder.core.demo.DemoUnplayableException
import dev.klepto.utrecorder.ut99.UtPackageException
import java.nio.file.Path

/**
 * How one capture of a demo ended.
 */
sealed interface RecorderResult {
    /**
     * The demo was captured.
     *
     * The directory that holds [mp4] is the workspace of the capture, which holds every file of it. The receiver of
     * this result owns that directory, and deletes it once it is done with [mp4].
     *
     * @property mp4 The captured mp4, inside the workspace of the capture.
     * @property thumbnail The thumbnail of [mp4], beside it.
     * @property video The frame size and the frame rate of the video of [mp4].
     */
    data class Done(
        val mp4: Path,
        val thumbnail: Path,
        val video: VideoStream,
    ) : RecorderResult

    /**
     * The capture failed, and the demo may be captured again.
     *
     * @property exception The failure of the capture.
     */
    data class Failed(
        val exception: Exception,
    ) : RecorderResult

    /**
     * The demo names a package that no source holds, so no later capture can succeed.
     *
     * @property exception The failure that names the package.
     */
    data class Unresolvable(
        val exception: UtPackageException,
    ) : RecorderResult

    /**
     * The game reported that it cannot play the demo, so no later capture can succeed.
     *
     * @property exception The failure that names the report of the game.
     */
    data class Unplayable(
        val exception: DemoUnplayableException,
    ) : RecorderResult
}
