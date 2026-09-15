package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.core.DemoFetcher
import dev.klepto.utrecorder.core.UtRecorder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * The recorder CLI: a demo URL on the command line in, the path of the finished mp4 on standard out.
 */
object UtRecorderCli {
    /**
     * The name the produced mp4 takes, taken from the file name of [url] and sanitised, or the fallback when the URL
     * names nothing.
     */
    fun outputName(url: String): String {
        val tail = url.substringBefore('?')
        val scheme = tail.substringBefore("://", missingDelimiterValue = "")
        val hostless = if (scheme.isEmpty()) tail else tail.removePrefix("$scheme://")
        val held =
            when (val afterHost = hostless.substringAfter('/', missingDelimiterValue = "")) {
                "" -> ""
                else -> afterHost.substringAfter('/')
            }
        val decoded = URLDecoder.decode(held, StandardCharsets.UTF_8)
        val sanitised = decoded.lowercase().filter { it.isLetter() || it.isDigit() || it == '.' }
        return sanitised.ifEmpty { FALLBACK_NAME }
    }

    /**
     * The demo the [url] names, downloaded into the [directories]' demo cache when the cache holds none.
     */
    fun cachedDemo(
        url: String,
        directories: Directories = Directories.DEFAULT,
    ): Path = DemoFetcher(directories).fetch(url)

    /**
     * Records the demo [url] names and prints the mp4's path on standard out, reusing the cached demo when the cache
     * holds it, and letting any failure of the fetch or the recording reach the caller as the thrown error.
     *
     * @throws IllegalStateException when the demo cannot be fetched, resolved or recorded.
     */
    fun main(
        url: String,
        directories: Directories = Directories.DEFAULT,
    ) {
        val demo = cachedDemo(url, directories)
        println(UtRecorder(directories = directories).record(demo, outputName(url)))
    }

    /**
     * Runs the CLI against [arguments], printing usage and exiting when [arguments] hold no URL.
     */
    fun main(arguments: Array<String>) {
        if (arguments.isEmpty()) {
            System.err.println("Usage: utrecorder-cli <demo-url>")
            exitProcess(EXIT_FAILURE)
        }
        main(arguments.first())
    }

    /**
     * The status the CLI exits with when no URL arrives.
     */
    const val EXIT_FAILURE = 1

    /**
     * The name the mp4 takes when the URL names no file.
     */
    const val FALLBACK_NAME = "demo"
}
