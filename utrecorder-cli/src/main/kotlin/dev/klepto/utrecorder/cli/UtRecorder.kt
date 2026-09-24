package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.demo.DemoFetcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command-line recorder that records the demo a URL names and prints the path of the mp4 on standard out.
 *
 * Run it through [main] with the command-line arguments, or call the suspending [main] with a URL.
 */
object UtRecorder {
    /**
     * The status the CLI exits with when no URL is given.
     */
    private const val EXIT_FAILURE = 1

    /**
     * The name of the mp4 when the URL names no file.
     */
    private const val FALLBACK_NAME = "demo"

    /**
     * Returns the name of the mp4 recorded from the demo that [url] names.
     *
     * The name is the file name of [url] without its query string, decoded, lowercased and stripped of every
     * character other than a letter, a digit or a dot. If nothing is left, returns `demo` instead.
     */
    fun outputName(url: String): String =
        url
            .substringBefore('?')
            .substringAfter("://")
            .substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/')
            .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            .lowercase()
            .filter { it.isLetter() || it.isDigit() || it == '.' }
            .ifEmpty { FALLBACK_NAME }

    /**
     * Returns the path of the demo that [url] names in the demo cache of [directories], downloading the demo first if
     * the cache does not hold it.
     */
    suspend fun cachedDemo(
        url: String,
        directories: DirectoriesConfig = DirectoriesConfig(),
    ): Path = DemoFetcher(directories).fetch(url)

    /**
     * Records the demo that [url] names and prints the path of the mp4 on standard out.
     *
     * The demo is downloaded into the demo cache of [directories] if the cache does not hold it.
     *
     * @throws IllegalStateException If the demo cannot be fetched, resolved or recorded.
     */
    suspend fun main(
        url: String,
        directories: DirectoriesConfig = DirectoriesConfig(),
    ) {
        val demo = cachedDemo(url, directories)
        println(Recorder(directories = directories).record(demo, outputName(url)))
    }

    /**
     * Records the demo that the first of [arguments] names and prints the path of the mp4 on standard out.
     *
     * If [arguments] is empty, prints the usage on standard error and exits with status 1 instead. When the JVM shuts
     * down during a recording, the recording is cancelled and the shutdown waits for it to finish.
     */
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isEmpty()) {
            System.err.println("Usage: utrecorder-cli <demo-url>")
            exitProcess(EXIT_FAILURE)
        }
        val job = Job()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                job.cancel()
                runBlocking { job.join() }
            },
        )
        runBlocking(job) {
            main(arguments.first())
        }
    }
}
