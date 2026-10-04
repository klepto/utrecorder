package dev.klepto.utrecorder.common.http

import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.digest
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.logger
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.time.Duration

/**
 * A file fetched over HTTP, verified against a hash, and written out only once it is complete.
 *
 * Construct one per URL, then call [download] to write the file to a path.
 *
 * @param hash The expected hash of the file, compared case-insensitively. If not provided, the file is not verified.
 * @property url The URL of the file to download.
 * @property algorithm The name of the hash algorithm that verifies the file, such as `md5` or `sha-256`.
 * @property timeout The maximum time a read may stall before the connection is considered dead.
 * @property directories The directories holding the workspace a download is written in until it is complete.
 */
class Downloader(
    private val url: String,
    hash: String? = null,
    private val algorithm: String = "md5",
    private val timeout: Duration = HTTP_SOCKET_TIMEOUT,
    private val directories: DirectoriesConfig = DirectoriesConfig(),
) {
    private val expectedHash: String? = hash?.lowercase()

    /**
     * Downloads the file to [outputPath], reporting the bytes received out of the total size to [onProgress]. While
     * the total size is unknown, reports nothing.
     *
     * If [outputPath] already holds the file, keeps it and reports it to [onProgress] as complete instead. The file is
     * held if it matches the hash or the size the server reports when there is no hash.
     *
     * @throws HashMismatchException if the downloaded file does not match the expected hash.
     */
    suspend fun download(
        outputPath: Path,
        onProgress: ProgressListener<Unit> = {},
    ) = withContext(Dispatchers.IO) { downloadOrKeep(outputPath, onProgress) }

    private suspend fun downloadOrKeep(
        outputPath: Path,
        onProgress: ProgressListener<Unit>,
    ) {
        if (isUpToDate(outputPath)) {
            reportDownloaded(outputPath, onProgress)
            return
        }

        workspace("download", config = directories) {
            val temporary = tempFile()
            log.debug { "Downloading $url to $outputPath" }
            try {
                fetch(temporary, onProgress)
                verifyHash(temporary)
                temporary.move(outputPath)
            } finally {
                withContext(NonCancellable) { temporary.deleteCatching() }
            }
        }
    }

    /**
     * Returns `true` if [outputPath] already holds the file: its hash matches, or without a hash, its size matches the
     * size the server reports.
     */
    private suspend fun isUpToDate(outputPath: Path): Boolean {
        if (!outputPath.exists()) return false
        if (expectedHash != null) return outputPath.digest(algorithm) == expectedHash

        val remoteSize = remoteSize() ?: return false
        return outputPath.fileSize() == remoteSize
    }

    /**
     * Returns the size the server reports for the file. If the server reports no size or cannot be reached, returns
     * `null` instead.
     */
    private suspend fun remoteSize(): Long? = headCatching(url, timeout).getOrNull()?.takeIf { it >= 0 }

    /**
     * Reports the file already held by [outputPath] to [onProgress] as a complete download.
     */
    private fun reportDownloaded(
        outputPath: Path,
        onProgress: ProgressListener<Unit>,
    ) {
        log.debug { "File $outputPath was already downloaded" }
        onProgress(Progress(1.0))
    }

    /**
     * Fetches the file into [target], reporting progress to [onProgress].
     */
    private suspend fun fetch(
        target: Path,
        onProgress: ProgressListener<Unit>,
    ) {
        target.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            useHttpClient(timeout) { copyTo(output, onProgress) }
        }
    }

    /**
     * Copies the response body into [output], reporting progress to [onProgress].
     */
    private suspend fun HttpClient.copyTo(
        output: OutputStream,
        onProgress: ProgressListener<Unit>,
    ) = prepareGet(url).execute { response: HttpResponse ->
        val total = response.contentLength()?.takeIf { it > 0 }
        response.bodyAsChannel().copyTo(output) { received ->
            total?.let { onProgress(Progress(received.toDouble() / it)) }
        }
    }

    /**
     * Throws a [HashMismatchException] if the hash of [file] differs from the expected hash. If there is no expected
     * hash, does nothing.
     */
    private suspend fun verifyHash(file: Path) {
        val expected = expectedHash ?: return
        val actual = file.digest(algorithm)
        if (actual != expected) {
            throw HashMismatchException(url, expected, actual)
        }
    }

    companion object {
        private val log = logger<Downloader>()

        /**
         * Copies this channel into [output], passing the number of bytes copied so far to [onCopied] after each read.
         * Throws the cause the channel was closed with, if any.
         */
        private suspend fun ByteReadChannel.copyTo(
            output: OutputStream,
            onCopied: (Long) -> Unit,
        ) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val read = readAvailable(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                copied += read
                onCopied(copied)
            }
            closedCause?.let { throw it }
        }
    }
}

/**
 * Failure of a download whose file does not hash to the expected value.
 *
 * @param url The URL the file was downloaded from.
 * @property expected The hash the file was expected to have.
 * @property actual The hash of the downloaded file.
 */
class HashMismatchException(
    url: String,
    val expected: String,
    val actual: String,
) : IOException("Hash mismatch for download $url: expected $expected, actual $actual")
