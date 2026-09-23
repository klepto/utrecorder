package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.log.logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.streams.asByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.time.Duration

/**
 * A callback receiving download progress: the bytes [received] so far and the [total] size, `-1` when unknown.
 */
typealias DownloadListener = (received: Long, total: Long) -> Unit

/**
 * A file fetched over HTTP, verified against a hash and written out only once it is complete.
 *
 * Construct one per URL, then call [download] to write the file to a path.
 *
 * @property url The URL of the file to download.
 * @property hash The expected hash of the file, compared case-insensitively, or `null` to skip verification.
 * @property algorithm The hash algorithm name to use for checking the downloaded file (e.g. "md5", "sha-256").
 * @property timeout The maximum time to wait between reads before the connection is considered stalled.
 * @property directories The directories holding the workspace a download is written in until it is complete.
 */
class Downloader(
    private val url: String,
    private val hash: String? = null,
    private val algorithm: String = "md5",
    private val timeout: Duration = HTTP_SOCKET_TIMEOUT,
    private val config: DirectoriesConfig = DirectoriesConfig(),
) {
    private val normalizedHash: String? = hash?.lowercase()

    /**
     * Fetch of the file into [outputPath], blocking until it completes. A file already at [outputPath] that matches
     * the hash, or the size the server reports when there is no hash, is kept and reported to [listener] as complete.
     *
     * @throws HashMismatchException if the downloaded file hash does not match the expected value.
     */
    suspend fun download(
        outputPath: Path,
        listener: DownloadListener? = null,
    ) = withContext(Dispatchers.IO) { executeDownload(outputPath, listener) }

    private suspend fun executeDownload(
        outputPath: Path,
        listener: DownloadListener? = null,
    ) {
        if (isUpToDate(outputPath)) {
            reportDownloaded(outputPath, listener)
            return
        }

        workspace("download", config = config) {
            val temporary = tempFile()
            log.info { "Downloading $url to $outputPath" }
            try {
                fetch(temporary, listener)
                verifyHash(temporary)
                temporary.move(outputPath)
            } finally {
                temporary.deleteCatching()
            }
        }
    }

    /**
     * Reports the file already held by [outputPath] to [listener] as a download that is complete.
     */
    private fun reportDownloaded(
        outputPath: Path,
        listener: DownloadListener?,
    ) {
        log.debug { "File $outputPath was already downloaded" }
        val size = outputPath.fileSize()
        listener?.invoke(size, size)
    }

    /**
     * Fetches the file into [target], reporting progress to [listener].
     */
    private suspend fun fetch(
        target: Path,
        listener: DownloadListener?,
    ) {
        target.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            val channel = output.asByteWriteChannel()
            useHttpClient(timeout) { copyTo(channel, listener) }
        }
    }

    /**
     * Copies the response body into [channel], reporting progress to [listener].
     */
    private suspend fun HttpClient.copyTo(
        channel: ByteWriteChannel,
        listener: DownloadListener?,
    ) = prepareGet(url) {
        listener?.apply { onDownload { received, total -> this(received, total ?: -1) } }
    }.execute { response: HttpResponse ->
        response.bodyAsChannel().copyAndClose(channel)
    }

    /**
     * Throws a [HashMismatchException] if the hash of [file] differs from the configured one. Does nothing when this
     * downloader carries no hash.
     */
    private suspend fun verifyHash(file: Path) {
        val expected = normalizedHash ?: return
        val actual = file.digest(algorithm)
        if (actual != expected) {
            throw HashMismatchException(url, expected, actual)
        }
    }

    /**
     * Returns `true` when [outputPath] already holds the file this downloader would fetch: its hash matches, or without
     * a hash, its size matches the size the server reports.
     */
    private suspend fun isUpToDate(outputPath: Path): Boolean {
        if (!outputPath.exists()) return false
        if (normalizedHash != null) return outputPath.digest(algorithm) == normalizedHash

        val remoteSize = remoteSize() ?: return false
        return outputPath.fileSize() == remoteSize
    }

    /**
     * The size the server reports for the file, or `null` when it reports none or cannot be asked.
     */
    private suspend fun remoteSize(): Long? = headCatching(url, timeout).getOrNull()?.takeIf { it >= 0 }

    companion object {
        private val log = logger<Downloader>()
    }
}

/**
 * Failure of [Downloader.download] when the file fetched from a URL does not hash to the expected value.
 *
 * @property expected The hash the downloader was given.
 * @property actual The hash of the file it fetched.
 */
class HashMismatchException(
    url: String,
    val expected: String,
    val actual: String,
) : IOException("Hash mismatch for download $url: expected $expected, actual $actual")
