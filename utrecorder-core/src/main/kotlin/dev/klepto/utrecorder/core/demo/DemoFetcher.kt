package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.asDownloadListener
import dev.klepto.utrecorder.common.http.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.time.Duration.Companion.minutes

/**
 * Downloader of a demo over HTTP into a directory of the caller's choosing.
 *
 * @property directories The directories a download stages into before it completes.
 */
class DemoFetcher(
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) {
    /**
     * Returns the path the demo at [url] is downloaded to in [directory], named after the last segment of the path of
     * [url].
     *
     * [onProgress] receives how much of the demo is downloaded. It is called from any thread, and at least once with
     * `1.0` when the download is done.
     */
    suspend fun fetch(
        url: String,
        directory: Path,
        onProgress: ProgressListener = {},
    ): Path {
        val target = directory / URI(url).path.substringAfterLast('/')
        withContext(Dispatchers.IO) { directory.createDirectories() }
        Downloader(url, timeout = 10.minutes, directories = directories).download(target, onProgress.asDownloadListener())
        onProgress(1.0)
        return target
    }
}
