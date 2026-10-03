package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.ut99.UtPackageException
import java.nio.file.Path

/**
 * How one capture of a demo ended.
 */
sealed interface RecorderResult {
    /**
     * The demo was captured.
     *
     * @property mp4 The captured mp4, inside the workspace of the capture.
     * @property thumbnail The thumbnail of [mp4], beside it.
     */
    data class Done(
        val mp4: Path,
        val thumbnail: Path,
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
}
