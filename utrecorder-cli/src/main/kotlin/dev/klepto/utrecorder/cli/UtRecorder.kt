package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.RecorderStage
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Command-line recorder that records the demo a URL names and prints the path of the mp4 on standard out. While it
 * records, it logs the progress of the recording.
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

    private val log = logger<UtRecorder>()

    /**
     * Returns the name of the mp4 recorded from the demo that [url] names.
     *
     * The name is the file name of [url] without its query string, decoded, lowercased and stripped of every
     * character other than a letter, a digit, a dot, a hyphen or an underscore. If nothing is left, returns `demo`
     * instead.
     */
    fun outputName(url: String): String =
        url
            .substringBefore('?')
            .substringAfter("://")
            .substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/')
            .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            .lowercase()
            .filter { it.isLetter() || it.isDigit() || it == '.' || it == '-' || it == '_' }
            .ifEmpty { FALLBACK_NAME }

    /**
     * Returns the line that logs [progress]: the stage followed by how much of it is done, in whole percent.
     */
    fun progressLine(progress: Progress<RecorderStage>): String =
        "%s %d%%".format(Locale.ROOT, progress.state, (progress.progress * 100).toInt())

    /**
     * Records the demo that [url] names and prints the path of the mp4 on standard out.
     *
     * The demo is downloaded into the demo cache of [directories] if the cache does not hold it. The progress of the
     * recording is logged when a stage starts, every 5 seconds while the stage runs, and once more when the stage
     * finishes.
     *
     * @throws IllegalStateException If the demo cannot be fetched, resolved or recorded.
     */
    suspend fun main(
        url: String,
        directories: DirectoriesConfig = DirectoriesConfig(),
    ) {
        val progress = MutableStateFlow<Progress<RecorderStage>?>(null)
        val mp4 =
            coroutineScope {
                val progressLogger = launch { logProgress(progress) }
                Recorder(directories = directories)
                    .record(url, outputName(url)) { report(progress, it) }
                    .also { progressLogger.cancel() }
            }
        progress.value?.let { log.info { progressLine(it) } }
        println(mp4)
    }

    /**
     * Stores [next] in [progress]. If [next] starts a new stage, first logs the last progress of the stage that
     * finished.
     */
    private fun report(
        progress: MutableStateFlow<Progress<RecorderStage>?>,
        next: Progress<RecorderStage>,
    ) {
        progress.value
            ?.takeIf { it.state != next.state }
            ?.let { log.info { progressLine(it) } }
        progress.value = next
    }

    /**
     * Logs the latest value of [progress] when its stage changes, and every 5 seconds after that
     * until the stage changes again. Never returns; cancel it to stop logging.
     */
    private suspend fun logProgress(progress: StateFlow<Progress<RecorderStage>?>) {
        progress
            .filterNotNull()
            .distinctUntilChangedBy { it.state }
            .collectLatest {
                while (true) {
                    progress.value?.let { log.info { progressLine(it) } }
                    delay(5.seconds)
                }
            }
    }

    /**
     * Records the demo that the first of [arguments] names and prints the path of the mp4 on standard out.
     *
     * If [arguments] is empty, prints the usage on standard error and exits with status 1 instead. When the JVM shuts
     * down during a recording, the recording is cancelled, the shutdown waits for it to finish, and the cancellation is
     * logged.
     */
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.isEmpty()) {
            System.err.println("Usage: utrecorder-cli <demo-url>")
            exitProcess(EXIT_FAILURE)
        }
        val job = Job()
        val mainThread = Thread.currentThread()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                job.cancel()
                mainThread.join()
            },
        )
        try {
            runBlocking(job) {
                main(arguments.first())
            }
        } catch (e: CancellationException) {
            log.info { "Cancelled the recording: ${e.message}" }
        }
    }
}
