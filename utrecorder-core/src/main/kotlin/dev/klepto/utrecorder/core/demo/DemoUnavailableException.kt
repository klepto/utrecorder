package dev.klepto.utrecorder.core.demo

import java.io.IOException

/**
 * Failure of a demo download that the server answered with a client error, such as `404 Not Found`, so the demo is
 * not served at [url].
 *
 * @property url The URL the demo was downloaded from.
 * @property status The HTTP status code of the answer, from `400` to `499`.
 * @param cause The failure of the download, if any.
 */
class DemoUnavailableException(
    val url: String,
    val status: Int,
    cause: Throwable?,
) : IOException("Demo $url is unavailable: HTTP $status", cause)
