package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.ut99.UtPackageException
import java.nio.file.Path

/**
 * How one recording attempt of a demo ended.
 */
sealed interface CaptureResult {
    /**
     * The demo that was recorded.
     */
    val demo: Demo

    /**
     * The demo was recorded.
     *
     * @property mp4 The recorded mp4.
     */
    data class Done(
        override val demo: Demo,
        val mp4: Path,
    ) : CaptureResult

    /**
     * The attempt failed, and the demo may be recorded again.
     *
     * @property exception The failure of the attempt.
     */
    data class Failed(
        override val demo: Demo,
        val exception: Exception,
    ) : CaptureResult

    /**
     * The demo names a package that no source holds, so no later attempt can succeed.
     *
     * @property exception The failure that names the package.
     */
    data class Unresolvable(
        override val demo: Demo,
        val exception: UtPackageException,
    ) : CaptureResult
}
