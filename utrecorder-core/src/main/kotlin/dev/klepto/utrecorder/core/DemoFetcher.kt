package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.DEMO_FETCH_TIMEOUT
import dev.klepto.utrecorder.common.io.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

/**
 * The demo a recording plays, fetched over HTTP into the demo cache and reused from there.
 */
class DemoFetcher(
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * The demo [url] names, downloaded into the cache when it holds none, and the file name it is cached under.
     */
    suspend fun fetch(url: String): Path =
        cachePath(url).also { cached ->
            val cachedAlready =
                withContext(Dispatchers.IO) {
                    cached.exists().also { found -> if (!found) cached.parent.createDirectories() }
                }
            if (cachedAlready) return cached
            Downloader(url, timeout = DEMO_FETCH_TIMEOUT, directories = directories).download(cached)
        }

    /**
     * The path under the cache directory the demo [url] is stored at, named after its file name.
     */
    private fun cachePath(url: String): Path = directories.cache / "demos" / URI(url).path.substringAfterLast('/')
}
