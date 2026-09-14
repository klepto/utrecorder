package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import java.nio.file.Path

/**
 * The demo a recording plays, fetched over HTTP into the demo cache and reused from there.
 */
class DemoFetcher(
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * The demo [url] names, downloaded into the cache when it holds none, and the file name it is cached under.
     */
    fun fetch(url: String): Path = TODO()
}
