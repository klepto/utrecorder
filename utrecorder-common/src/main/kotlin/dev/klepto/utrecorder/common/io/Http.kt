package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.log.catching
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.head
import io.ktor.http.contentLength
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val HTTP_CONNECT_TIMEOUT: Duration = 30.seconds

/**
 * The time a whole demo download may take before the fetch of it fails.
 */
val DEMO_FETCH_TIMEOUT: Duration = 10.minutes

/**
 * The time a read may stall before a connection is considered dead.
 */
val HTTP_SOCKET_TIMEOUT: Duration = 1.minutes

/**
 * Runs [block] against a new HTTP client on the calling thread, and closes the client once the block returns or throws.
 *
 * The client throws on a response whose status is not a success, and bounds only connecting and stalled reads, never
 * the length of a whole request.
 */
suspend fun <T> useHttpClient(
    socketTimeout: Duration = HTTP_SOCKET_TIMEOUT,
    block: suspend HttpClient.() -> T,
): T = createHttpClient(socketTimeout).use { it.block() }

private fun createHttpClient(socketTimeout: Duration): HttpClient =
    HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = HTTP_CONNECT_TIMEOUT.inWholeMilliseconds
            socketTimeoutMillis = socketTimeout.inWholeMilliseconds
        }
    }

/**
 * The outcome of a HEAD request to [url]: the content length the response reports, or `null` when it reports none.
 * A refused connection, a timeout and a status other than success are failures.
 */
suspend fun headCatching(
    url: String,
    socketTimeout: Duration = HTTP_SOCKET_TIMEOUT,
): Result<Long?> = catching { useHttpClient(socketTimeout) { head(url).contentLength() } }

/**
 * This string encoded as one path segment of a URL, with a space written as `%20`.
 */
fun String.encodeUrlPathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
