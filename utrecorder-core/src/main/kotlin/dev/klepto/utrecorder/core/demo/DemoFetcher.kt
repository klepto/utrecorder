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
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

/**
 * Cache of demos downloaded over HTTP, which downloads a demo once and reuses the cached file after that.
 *
 * @property directories The directories holding the cache that demos are stored in.
 */
class DemoFetcher(
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) {
    /**
     * Returns the path of the demo that [url] names in the cache, downloading the demo first if the cache does not hold
     * it.
     *
     * The demo is cached under the last segment of the path of [url]. [onProgress] receives how much of the demo is
     * downloaded. It is called from any thread, and at least once with `1.0` when the demo is in the cache.
     */
    suspend fun fetch(
        url: String,
        onProgress: ProgressListener = {},
    ): Path =
        cachePath(url).also { cached ->
            if (!withContext(Dispatchers.IO) { cached.exists() }) download(url, cached, onProgress)
            onProgress(1.0)
        }

    /**
     * Returns the path in the cache of the demo that [url] names.
     */
    private fun cachePath(url: String): Path = directories.cache / "demos" / URI(url).path.substringAfterLast('/')

    /**
     * Downloads the demo that [url] names to [cached], creating the directory that holds it first, and reports progress
     * to [onProgress].
     */
    private suspend fun download(
        url: String,
        cached: Path,
        onProgress: ProgressListener,
    ) {
        withContext(Dispatchers.IO) { cached.parent.createDirectories() }
        val downloader = Downloader(url, timeout = 10.minutes, directories = directories)
        downloader.download(cached, onProgress.asDownloadListener())
    }
}
