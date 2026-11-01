package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.RecorderConfig
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.demo.DemoUnplayableException
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.ut99.UtPackageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Recorder of one demo at a time.
 *
 * The default implementation is [DefaultRecorderProvider].
 */
interface RecorderProvider {
    /**
     * Returns the result of recording [Demo.demoUrl] into an mp4 and a thumbnail named after [Demo.capId].
     *
     * On a [RecorderResult.Done], the mp4 and the thumbnail lie in one workspace directory that holds every file of the
     * recording, and the caller owns that directory. On any other result, and on cancellation, no file of the recording
     * is left under the workspace directory.
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
 * Each recording runs in a workspace of its own, which the workspaces of the [Recorder] nest in. The workspace is
 * deleted on any result other than [RecorderResult.Done], and on cancellation.
 *
 * @property config The configuration demos are recorded with.
 * @property directories The directories the workspace of each recording is kept under. If not provided, uses the
 * directories of [RecorderConfig.ut].
 */
class DefaultRecorderProvider(
    private val config: RecorderConfig = RecorderConfig(),
    private val directories: DirectoriesConfig = config.ut.directoriesConfig,
) : RecorderProvider {
    override suspend fun record(
        demo: Demo,
        onProgress: ProgressListener<RecorderStage>,
    ): RecorderResult =
        workspace(WORKSPACE_NAME, config = directories) {
            val result =
                try {
                    recordResult(demo, onProgress)
                } catch (cancellation: CancellationException) {
                    deleteOrLog(demo, cancellation)
                    throw cancellation
                }
            if (result !is RecorderResult.Done) deleteOrLog(demo)
            result
        }

    /**
     * Returns the result of recording [demo] with the core [Recorder], with every exception other than a
     * [CancellationException] returned as a result.
     */
    private suspend fun recordResult(
        demo: Demo,
        onProgress: ProgressListener<RecorderStage>,
    ): RecorderResult =
        try {
            val output = Recorder(config, directories).record(demo.demoUrl, demo.capId, onProgress)
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

    /**
     * Deletes this workspace, which holds the recording of [demo], even when the calling coroutine is cancelled. A delete
     * that fails is logged, and added as suppressed to [cancellation] when there is one.
     */
    private suspend fun Workspace.deleteOrLog(
        demo: Demo,
        cancellation: CancellationException? = null,
    ) {
        withContext(NonCancellable) {
            try {
                delete()
            } catch (failure: Exception) {
                cancellation?.addSuppressed(failure)
                log.warn(failure) { "Could not delete the workspace of recording ${demo.capId}" }
            }
        }
    }

    private companion object {
        /**
         * The name of the workspace of each recording.
         */
        const val WORKSPACE_NAME = "recording"

        val log = logger<DefaultRecorderProvider>()
    }
}
