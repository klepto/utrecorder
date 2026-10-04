package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.io.fileExists
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.util.url

/**
 * [Endpoint] that serves the mp4s of the recordings of [repository], their thumbnails and the pages that play them.
 *
 * The routes are:
 * - `GET /mp4/{capId}`: the mp4 of a [Recorded], [Uploaded] or [Archived] recording, with range support. If the
 *   recording is [Archived] and its mp4 is not in [ServerConfig.recordings], redirects to the download URL of [cache]
 *   instead, if [cache] names one. The mp4 of an [Outdated] recording is served the same way, but never redirected;
 *   once its mp4 is gone, the recording answers `404`. Every other request answers `404`.
 * - `GET /thumbnail/{capId}`: the thumbnail of a recording `/mp4/{capId}` would serve, if it is in
 *   [ServerConfig.thumbnails]. Every other request answers `404`.
 * - `GET /thumb/{capId}`: for any cap id, a `301 Moved Permanently` to `/thumbnail/{capId}`, so a link preview that
 *   named the old path keeps its image.
 * - `GET /view/{capId}`: an HTML page that plays `/mp4/{capId}` and carries OpenGraph video tags for link previews: the
 *   title of the cap as its site name and no title of its own, the size of the first, largest tier of `record.tiers` as
 *   the size of the video and the image, `/thumbnail/{capId}` as its image, and absolute URLs built from the origin of the
 *   request. The origin honours the `X-Forwarded-*` headers only if [ServerConfig.trustForwardedHeaders] is enabled.
 *   Answers `404` wherever `/mp4/{capId}` answers `404`.
 *
 * A `GET` of `/mp4/{capId}` for a stored recording counts one view of it through [cache], unless it continues a
 * playback: a request with a `Range` header other than `bytes=0-` counts none. A `HEAD` request and a request of
 * `/thumbnail/{capId}`, `/thumb/{capId}` or `/view/{capId}` count none.
 *
 * @property repository The recordings to serve.
 * @property cache The cache that counts the views and names the download URL of an archived mp4.
 * @property config The recordings and thumbnails directories and the size of the first, largest tier of
 * `record.tiers`.
 */
class VideoEndpoint(
    private val repository: RecordingRepository,
    private val cache: CacheService,
    private val config: ServerConfig = ServerConfig(),
) : Endpoint {
    override fun install(route: Route) {
        route.get("/mp4/{capId}") { serveMp4(call.parameters["capId"].orEmpty()) }
        route.get("/thumbnail/{capId}") { serveThumbnail(call.parameters["capId"].orEmpty()) }
        route.get("/thumb/{capId}") {
            call.respondRedirect(call.url { path("thumbnail", call.parameters["capId"].orEmpty()) }, permanent = true)
        }
        route.get("/view/{capId}") { serveView(call.parameters["capId"].orEmpty()) }
    }

    /**
     * Answers with the mp4 of the recording [requestedCapId], a redirect to its download URL, or `404`. An [Outdated] recording
     * is never redirected: once its mp4 is gone, it answers `404`.
     */
    private suspend fun RoutingContext.serveMp4(requestedCapId: String) {
        val recording = cache.view(requestedCapId, counted = call.request.local.method == HttpMethod.Get && isFromStart())
        if (recording == null || !recording.status.isServable) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val capId = recording.demo.capId
        val mp4 = config.mp4(capId)
        val downloadUrl = cache.downloadUrl(recording)
        when {
            mp4.fileExists() -> call.respondFile(mp4.toFile())
            recording.status == Archived && downloadUrl != null -> call.respondRedirect(downloadUrl)
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    /**
     * Returns `true` if the request asks for the mp4 from its start: it has no `Range` header, or the range `bytes=0-`.
     */
    private fun RoutingContext.isFromStart(): Boolean =
        when (call.request.headers[HttpHeaders.Range]) {
            null, "bytes=0-" -> true
            else -> false
        }

    /**
     * Answers with the thumbnail of the recording [capId], or `404` if the recording is not served or has no
     * thumbnail.
     */
    private suspend fun RoutingContext.serveThumbnail(capId: String) {
        val thumbnail = repository[capId]?.takeIf { it.status.isServable }?.let { config.thumbnail(it.demo.capId) }
        when {
            thumbnail != null && thumbnail.fileExists() -> call.respondFile(thumbnail.toFile())
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    /**
     * Answers with the page that plays the recording [requestedCapId] and describes it to link previews, or `404` wherever
     * [serveMp4] answers `404`.
     */
    private suspend fun RoutingContext.serveView(requestedCapId: String) {
        val recording = repository[requestedCapId]
        if (recording == null || !isPlayable(recording)) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val capId = recording.demo.capId
        val page =
            viewPage(
                demo = recording.demo,
                pageUrl = call.url { path("view", capId) },
                mp4Url = call.url { path("mp4", capId) },
                thumbnailUrl = call.url { path("thumbnail", capId) },
            )
        call.respondText(page, ContentType.Text.Html)
    }

    /**
     * Returns whether [serveMp4] answers the recording with its mp4 or a redirect to it.
     */
    private suspend fun isPlayable(recording: Recording): Boolean {
        val capId = recording.demo.capId
        return when {
            !recording.status.isServable -> false
            config.mp4(capId).fileExists() -> true
            else -> recording.status == Archived && cache.downloadUrl(recording) != null
        }
    }

    /**
     * Returns the HTML page that plays the mp4 at [mp4Url] and carries the OpenGraph video tags of [demo], with
     * [pageUrl] as its canonical URL and the thumbnail at [thumbnailUrl] as its image.
     */
    private fun viewPage(
        demo: Demo,
        pageUrl: String,
        mp4Url: String,
        thumbnailUrl: String,
    ): String {
        val title = demo.title().escapeHtml()
        val mp4 = mp4Url.escapeHtml()
        val tier =
            config.recorder.video.tiers
                .first()
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <meta property="og:site_name" content="$title" />
                <meta property="og:type" content="video.other" />
                <meta property="og:url" content="${pageUrl.escapeHtml()}" />
                <meta property="og:video" content="$mp4" />
                <meta property="og:video:type" content="video/mp4" />
                <meta property="og:video:width" content="${tier.width}" />
                <meta property="og:video:height" content="${tier.height}" />
                <meta property="og:image" content="${thumbnailUrl.escapeHtml()}" />
                <meta property="og:image:type" content="image/jpeg" />
                <meta property="og:image:width" content="${tier.width}" />
                <meta property="og:image:height" content="${tier.height}" />
              </head>
              <body style="margin: 0; background: #000">
                <video src="$mp4" controls autoplay playsinline style="display: block; width: 100vw; height: 100vh"></video>
              </body>
            </html>
            """.trimIndent()
    }

    private companion object {
        /**
         * `true` if a recording with this status is served: it is [completed][RecordingStatus.isCompleted] or [Outdated].
         */
        val RecordingStatus.isServable: Boolean
            get() = isCompleted || this == Outdated

        /**
         * Returns this text with the characters that HTML gives a meaning escaped, so it reads as text in an element
         * or an attribute value.
         */
        fun String.escapeHtml(): String =
            replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
    }
}
