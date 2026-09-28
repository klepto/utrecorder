package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.RecorderConfig
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.ut99.UtPackageException
import kotlinx.coroutines.CancellationException

/**
 * Recorder of one demo at a time.
 *
 * The default implementation is [DefaultCaptureProvider].
 */
interface CaptureProvider {
    /**
     * Returns the result of recording [Demo.demoUrl] into an mp4 named after [Demo.capId]. The mp4 of a
     * [CaptureResult.Done] lies inside the workspace of its recording.
     *
     * [onProgress] receives each progress report of the recording.
     */
    suspend fun record(
        demo: Demo,
        onProgress: ProgressListener<RecorderStage> = {},
    ): CaptureResult
}

/**
 * [CaptureProvider] backed by the core [Recorder].
 *
 * @property config The configuration demos are recorded with.
 */
class DefaultCaptureProvider(
    private val config: RecorderConfig = RecorderConfig(),
) : CaptureProvider {
    override suspend fun record(
        demo: Demo,
        onProgress: ProgressListener<RecorderStage>,
    ): CaptureResult =
        try {
            val recording = Recorder(config).record(demo.demoUrl, demo.capId, onProgress)
            CaptureResult.Done(demo, recording)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: UtPackageException) {
            CaptureResult.Unresolvable(demo, exception)
        } catch (exception: Exception) {
            CaptureResult.Failed(demo, exception)
        }
}
