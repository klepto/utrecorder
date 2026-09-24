package dev.klepto.utrecorder.common.http

import dev.klepto.utrecorder.common.suspendCatching
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

/**
 * The time a read may stall before a connection is considered dead.
 */
val HTTP_SOCKET_TIMEOUT: Duration = 1.minutes

/**
 * Runs [block] against a new HTTP client and closes the client once the block returns or throws.
 *
 * The client throws on a response whose status is not a success. It bounds only connecting and stalled reads, never
 * the length of a whole request. If [socketTimeout] is not provided, uses [HTTP_SOCKET_TIMEOUT] instead.
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
            connectTimeoutMillis = 30.seconds.inWholeMilliseconds
            socketTimeoutMillis = socketTimeout.inWholeMilliseconds
        }
    }

/**
 * Returns the content length that the response to a HEAD request to [url] reports.
 *
 * If the response reports no content length, the result holds `null`. A refused connection, a timeout and a status
 * other than success are returned as a failure. If [socketTimeout] is not provided, uses [HTTP_SOCKET_TIMEOUT] instead.
 */
suspend fun headCatching(
    url: String,
    socketTimeout: Duration = HTTP_SOCKET_TIMEOUT,
): Result<Long?> = suspendCatching { useHttpClient(socketTimeout) { head(url).contentLength() } }

/**
 * Returns this string encoded as one path segment of a URL, with a space written as `%20`.
 */
fun String.encodeUrlPathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
