package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.core.UtRecorder
import java.nio.file.Path

/**
 * The recorder CLI: a demo URL on the command line in, the path of the finished mp4 on standard out.
 */
object UtRecorderCli {
    /**
     * Records the demo [url] names, printing the mp4 path, or prints usage and exits when [arguments] hold no URL.
     */
    fun main(arguments: Array<String>) {
        TODO()
    }

    /**
     * The name the produced mp4 takes, taken from the file name of [url] and sanitised, or the fallback.
     */
    fun outputName(url: String): String = TODO()

    /**
     * The demo the [url] names, downloaded into the demo cache when the cache holds none.
     */
    fun cachedDemo(url: String): Path = TODO()
}
