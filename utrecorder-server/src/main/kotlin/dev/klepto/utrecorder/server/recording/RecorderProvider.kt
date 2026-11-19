package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatchingIo
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.RecorderConfig
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.demo.DemoUnavailableException
import dev.klepto.utrecorder.core.demo.DemoUnplayableException
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.ut99.UtPackageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

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

    /**
     * Deletes the workspace directories that recordings of an earlier process left behind. Call it once, before the
     * first [record].
     *
     * A delete that fails is logged, and does not throw. By default, deletes nothing.
     */
    suspend fun deleteOrphanedWorkspaces() {}
}

/**
 * [RecorderProvider] backed by the core [Recorder].
 *
 * Each recording runs in a workspace of its own, which the workspaces of the [Recorder] nest in. The workspace is
 * deleted on any result other than [RecorderResult.Done], and on cancellation. Its directory is the entry
 * `recording-<id>` of [DirectoriesConfig.workspace], and [deleteOrphanedWorkspaces] deletes every entry named
 * `recording-*` there.
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

    override suspend fun deleteOrphanedWorkspaces() {
        orphanedWorkspacesCatching()
            .onFailure { log.warn(it) { "Could not list the orphaned workspaces in ${directories.workspace}" } }
            .getOrDefault(emptyList())
            .forEach { it.deleteOrphanOrLog() }
    }

    /**
     * Returns the outcome of listing every entry of [DirectoriesConfig.workspace] that is named like the workspace of a
     * recording. If the directory does not exist, returns an empty list.
     */
    private suspend fun orphanedWorkspacesCatching(): Result<List<Path>> =
        withContext(Dispatchers.IO) {
            suspendCatchingIo {
                when {
                    directories.workspace.isDirectory() -> directories.workspace.listDirectoryEntries("$WORKSPACE_NAME-*")
                    else -> emptyList()
                }
            }
        }

    /**
     * Deletes this orphaned workspace and everything under it. A delete that fails is logged.
     */
    private suspend fun Path.deleteOrphanOrLog() {
        deleteCatching()
            .onSuccess { log.info { "Deleted the orphaned workspace $this" } }
            .onFailure { log.warn(it) { "Could not delete the orphaned workspace $this" } }
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
        } catch (exception: DemoUnavailableException) {
            RecorderResult.Unavailable(exception)
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
