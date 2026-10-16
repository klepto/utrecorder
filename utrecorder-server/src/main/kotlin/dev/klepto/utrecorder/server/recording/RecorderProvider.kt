package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.RecorderConfig
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.demo.DemoUnplayableException
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.ut99.UtPackageException
import kotlinx.coroutines.CancellationException

/**
 * Recorder of one demo at a time.
 *
 * The default implementation is [DefaultRecorderProvider].
 */
interface RecorderProvider {
    /**
     * Returns the result of recording [Demo.demoUrl] into an mp4 and a thumbnail named after [Demo.capId]. The mp4 and
     * the thumbnail of a [RecorderResult.Done] lie inside the workspace of their recording.
     *
     * [onProgress] receives each progress report of the recording.
     */
    suspend fun record(
        demo: Demo,
        onProgress: ProgressListener<RecorderStage> = {},
    ): RecorderResult
}

/**
 * [RecorderProvider] backed by the core [Recorder].
 *
 * @property config The configuration demos are recorded with.
 */
class DefaultRecorderProvider(
    private val config: RecorderConfig = RecorderConfig(),
) : RecorderProvider {
    override suspend fun record(
        demo: Demo,
        onProgress: ProgressListener<RecorderStage>,
    ): RecorderResult =
        try {
            val output = Recorder(config).record(demo.demoUrl, demo.capId, onProgress)
            RecorderResult.Done(output.mp4, output.thumbnail, output.video)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: UtPackageException) {
            RecorderResult.Unresolvable(exception)
        } catch (exception: DemoUnplayableException) {
            RecorderResult.Unplayable(exception)
        } catch (exception: Exception) {
            RecorderResult.Failed(exception)
        }
}
