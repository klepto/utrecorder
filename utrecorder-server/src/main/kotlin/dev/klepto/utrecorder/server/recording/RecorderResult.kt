package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.ut99.UtPackageException
import java.nio.file.Path

/**
 * How one capture of a demo ended.
 */
sealed interface RecorderResult {
    /**
     * The status of the recording once this capture is its last one.
     */
    val status: RecordingStatus

    /**
     * The demo was captured.
     *
     * @property mp4 The captured mp4, inside the workspace of the capture.
     */
    data class Done(
        val mp4: Path,
    ) : RecorderResult {
        override val status: RecordingStatus get() = RecordingStatus.Recorded
    }

    /**
     * The capture failed, and the demo may be captured again.
     *
     * @property exception The failure of the capture.
     */
    data class Failed(
        val exception: Exception,
    ) : RecorderResult {
        override val status: RecordingStatus get() = RecordingStatus.Failed
    }

    /**
     * The demo names a package that no source holds, so no later capture can succeed.
     *
     * @property exception The failure that names the package.
     */
    data class Unresolvable(
        val exception: UtPackageException,
    ) : RecorderResult {
        override val status: RecordingStatus get() = RecordingStatus.Unresolvable
    }
}
