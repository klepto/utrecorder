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
import kotlinx.coroutines.flow.getAndUpdate
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
     * The status the CLI exits with when its arguments are invalid.
     */
    private const val EXIT_USAGE = 2

    /**
     * The usage text, printed for `--help` and after an invalid argument.
     */
    private const val USAGE =
        "Usage: utrecorder-cli [-h | --help] <demo-url>\n" +
            "Records the Unreal Tournament demo that <demo-url> names to an mp4 and prints the path of the mp4 on " +
            "standard out; the log goes to standard error. -h and --help print this text. Exits with status 0 when " +
            "the recording succeeds, 1 when it fails and 2 when the arguments are invalid."

    /**
     * The name of the mp4 when the URL names no file.
     */
    private const val FALLBACK_NAME = "demo"

    /**
     * The extension of a demo file, lowercased.
     */
    private const val DEMO_EXTENSION = ".dem"

    private val log = logger<UtRecorder>()

    /**
     * What the command-line arguments ask the CLI to do.
     */
    sealed interface Invocation {
        /**
         * A request to print the usage.
         */
        data object Help : Invocation

        /**
         * A request to record the demo that [url] names.
         *
         * @property url The demo URL.
         */
        data class Record(
            val url: String,
        ) : Invocation

        /**
         * Arguments the CLI rejects.
         *
         * @property message Why the arguments are rejected.
         */
        data class Invalid(
            val message: String,
        ) : Invocation
    }

    /**
     * Returns what [arguments] ask the CLI to do.
     *
     * `--help` or `-h` anywhere among [arguments] asks for the usage. Otherwise, [arguments] must be a single demo URL.
     * Any other argument that starts with `-` is an unknown option. If [arguments] holds an unknown option, no
     * argument, more than one argument or a URL that [outputName] rejects, returns [Invocation.Invalid] instead.
     */
    fun parseArguments(arguments: List<String>): Invocation {
        val option = arguments.firstOrNull { it.startsWith('-') }
        return when {
            arguments.any { it == "--help" || it == "-h" } -> Invocation.Help
            option != null -> Invocation.Invalid("Unknown option $option")
            arguments.isEmpty() -> Invocation.Invalid("No demo URL given")
            arguments.size > 1 -> Invocation.Invalid("Expected one demo URL, got ${arguments.size} arguments")
            else -> parseUrl(arguments.single())
        }
    }

    /**
     * Returns a request to record [url]. If [outputName] rejects [url], returns [Invocation.Invalid] instead.
     */
    private fun parseUrl(url: String): Invocation =
        runCatching { outputName(url) }
            .fold({ Invocation.Record(url) }, { Invocation.Invalid(it.message.orEmpty()) })

    /**
     * Returns the name of the mp4 recorded from the demo that [url] names, without the `.mp4` extension.
     *
     * The name starts from the part of [url] after the scheme, the host and the first path segment, without the query
     * string. That part is URL-decoded and lowercased, and a trailing `.dem` is removed. Then every character other than
     * a letter, a digit, a dot, a hyphen or an underscore is removed, the slashes between segments included. If nothing
     * is left, returns `demo` instead. A [url] without a scheme is read the same way, its first segment taken as the
     * host.
     *
     * @throws IllegalArgumentException If [url] holds a malformed percent escape.
     */
    fun outputName(url: String): String =
        url
            .substringBefore('?')
            .substringAfter("://")
            .substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/')
            .let { decode(url, it) }
            .lowercase()
            .removeSuffix(DEMO_EXTENSION)
            .filter { it.isLetter() || it.isDigit() || it == '.' || it == '-' || it == '_' }
            .ifEmpty { FALLBACK_NAME }

    /**
     * Returns [path], a part of [url], URL-decoded.
     *
     * @throws IllegalArgumentException If [path] holds a malformed percent escape.
     */
    private fun decode(
        url: String,
        path: String,
    ): String =
        runCatching { URLDecoder.decode(path, StandardCharsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException("Demo URL $url holds a malformed percent escape", it) }

    /**
     * Returns the line that logs [progress]: the stage followed by how much of it is done, in whole percent.
     */
    fun progressLine(progress: Progress<RecorderStage>): String =
        "%s %d%%".format(Locale.ROOT, progress.state, (progress.progress * 100).toInt())

    /**
     * Records the demo that [url] names and prints the path of the mp4 on standard out.
     *
     * The mp4 is named by [outputName]. The demo is downloaded into the workspace of the recording under [directories],
     * and is kept there with the mp4. Standard out receives only the path of the mp4, once the recording is done. The
     * progress of the recording is logged when a stage starts, every 5 seconds while the stage runs, and once more when
     * the stage finishes. Each stage and whole percent is logged once.
     *
     * @throws IllegalArgumentException If [url] holds a malformed percent escape, or if [url] names no demo file.
     * @throws java.io.IOException If the connection that downloads the demo fails or times out.
     * @throws IllegalStateException If the server answers the demo download with a status other than success, if the demo
     * cannot be resolved, if a capture fails the recording, if the length of the mp4 does not match the run time the demo
     * reports, or if the thumbnail cannot be written.
     * @throws dev.klepto.utrecorder.ut99.UtPackageException If no source serves one of the packages the demo names.
     */
    suspend fun main(
        url: String,
        directories: DirectoriesConfig = DirectoriesConfig(),
    ) {
        val progress = MutableStateFlow<Progress<RecorderStage>?>(null)
        val progressLog = ProgressLog<RecorderStage> { log.info { progressLine(it) } }
        val mp4 =
            coroutineScope {
                val progressLogger = launch { logProgress(progress, progressLog) }
                Recorder(directories = directories)
                    .record(url, outputName(url)) { report(progress, progressLog, it) }
                    .also { progressLogger.cancel() }
            }
        progress.value?.let { progressLog.log(it) }
        println(mp4)
    }

    /**
     * Stores [next] in [progress]. If [next] starts a new stage, logs the last progress of the stage that finished to
     * [progressLog].
     */
    private fun report(
        progress: MutableStateFlow<Progress<RecorderStage>?>,
        progressLog: ProgressLog<RecorderStage>,
        next: Progress<RecorderStage>,
    ) {
        progress
            .getAndUpdate { next }
            ?.takeIf { it.state != next.state }
            ?.let { progressLog.log(it) }
    }

    /**
     * Logs the latest value of [progress] to [progressLog] when its stage changes, and every 5 seconds after that
     * until the stage changes again. Never returns; cancel it to stop logging.
     */
    private suspend fun logProgress(
        progress: StateFlow<Progress<RecorderStage>?>,
        progressLog: ProgressLog<RecorderStage>,
    ) {
        progress
            .filterNotNull()
            .distinctUntilChangedBy { it.state }
            .collectLatest {
                while (true) {
                    progress.value?.let { progressLog.log(it) }
                    delay(5.seconds)
                }
            }
    }

    /**
     * Records the demo that [arguments] name and prints the path of the mp4 on standard out.
     *
     * [arguments] are read by [parseArguments]. If they ask for the usage, prints it on standard out and returns
     * instead. If they are invalid, prints why and the usage on standard error and exits with status 2 instead. If the
     * recording fails, the exception is thrown out of this function, so the JVM prints it on standard error and exits
     * with status 1. When the JVM shuts down during a recording, the recording is cancelled, the shutdown waits for it
     * to finish, and the cancellation is logged.
     */
    @JvmStatic
    fun main(arguments: Array<String>) {
        val url =
            when (val invocation = parseArguments(arguments.asList())) {
                Invocation.Help -> {
                    println(USAGE)
                    return
                }

                is Invocation.Invalid -> {
                    System.err.println(invocation.message)
                    System.err.println(USAGE)
                    exitProcess(EXIT_USAGE)
                }

                is Invocation.Record -> {
                    invocation.url
                }
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
                main(url)
            }
        } catch (e: CancellationException) {
            log.info { "Cancelled the recording: ${e.message}" }
        }
    }
}
