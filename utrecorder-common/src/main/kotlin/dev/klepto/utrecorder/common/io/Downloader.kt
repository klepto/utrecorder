package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.log.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.streams.asByteWriteChannel
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Downloader facade backed by Ktor client. Supports HTTP file downloads, hash checksum checking, and progress reporting
 * via [download] function.
 *
 * @param url The URL of the file to download.
 * @param hash The hash of the file to download.
 * @param algorithm The hash algorithm name to use for checking the downloaded file (e.g. "md5", "sha-256")
 * @param timeout The maximum time to wait between reads before the connection is considered stalled.
 * @author Augustinas R. <http://github.com/klepto>
 */
class Downloader(
    private val url: String,
    hash: String? = null,
    private val algorithm: String = "md5",
    private val timeout: Duration = socketTimeout,
) {
    /**
     * Normalized hash value for comparison.
     */
    private val normalizedHash: String? = hash?.lowercase()

    /**
     * Starts the download. This method blocks until the download is complete or failure is reached.
     * Supports progress reporting via [listener] callback.
     *
     * @param outputPath Path to the output file.
     * @param listener Callback invoked on download progress. The first argument is the number of bytes received so
     * far. The second argument is the total size of the file, `-1` if unknown.
     *
     * @throws HashMismatchException if the downloaded file hash does not match the expected value.
     */
    fun download(
        outputPath: Path,
        listener: ((Long, Long) -> Unit)? = null,
    ) {
        if (isUpToDate(outputPath)) {
            log.debug { "File $outputPath was already downloaded" }
            val size = outputPath.fileSize()
            listener?.invoke(size, size)
            return
        }

        val temporary = outputPath.createTemporaryFile()
        log.info { "Downloading $url to $outputPath" }
        try {
            temporary.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                val channel = output.asByteWriteChannel()
                createHttpClient().useBlocking {
                    prepareGet(url) {
                        listener?.apply { onDownload { received, total -> this(received, total ?: -1) } }
                    }.execute { response: HttpResponse ->
                        response.bodyAsChannel().copyAndClose(channel)
                    }
                }
            }

            if (normalizedHash != null) {
                val actualHash = temporary.digest(algorithm)
                if (actualHash != normalizedHash) {
                    throw HashMismatchException(url, normalizedHash, actualHash)
                }
            }

            temporary.move(outputPath)
        } finally {
            temporary.deleteIfExists()
        }
    }

    /**
     * Returns `true` when [outputPath] already holds the file this downloader would fetch.
     *
     * A configured hash is authoritative. Without one the local size is compared against the size the server reports,
     * which catches the truncated and half-written files.
     */
    private fun isUpToDate(outputPath: Path): Boolean {
        if (!outputPath.exists()) return false
        if (normalizedHash != null) return outputPath.digest(algorithm) == normalizedHash

        val remoteSize = remoteSize() ?: return false
        return outputPath.fileSize() == remoteSize
    }

    /**
     * Returns the file size reported in the HTTP headers, or `null` when it's not available.
     */
    private fun remoteSize(): Long? =
        runCatching {
            createHttpClient().useBlocking {
                head(url).contentLength()
            }
        }.getOrNull()?.takeIf { it >= 0 }

    /**
     * Creates a new HTTP client instance.
     */
    private fun createHttpClient() =
        HttpClient(CIO) {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = connectTimeout.inWholeMilliseconds
                socketTimeoutMillis = timeout.inWholeMilliseconds
            }
        }

    companion object {
        private val log = logger<Downloader>()
        private val socketTimeout = 1.minutes
        private val connectTimeout = 30.seconds
    }
}

/**
 * Thrown when the [url] download file [actual] hash does not match the [expected] hash value.
 */
class HashMismatchException(
    url: String,
    val expected: String,
    val actual: String,
) : IOException("Hash mismatch for download $url: expected $expected, actual $actual")
