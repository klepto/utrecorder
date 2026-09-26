package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.server.cache.RecordingCache
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Lane
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The time a `202` response asks the client to wait before it asks again.
 */
private val RETRY_AFTER = 5.seconds

/**
 * Installs `GET /demos/{demo_id}.mp4`, the one HTTP endpoint of the recording backend.
 *
 * The route reads each recording from [recordings] on every request, so it answers with the state last written:
 * - A demo id that is not a UUID answers `404` before any lookup; an unknown one also answers `404`.
 * - A recording with a local mp4 is served with range support, and its `accessed_at` is touched.
 * - An [RecordingState.ARCHIVED] recording with no local mp4 is redirected to its Archive download URL, its
 *   `accessed_at` is touched, and a re-cache is started in the background through [cache].
 * - A [RecordingState.PENDING] or [RecordingState.RECORDING] recording answers `202` with a `Retry-After` header and
 *   a JSON body of its state, and, while recording, the progress of the [lanes] entry recording it.
 * - A [RecordingState.FAILED] recording answers `410` with its error; a [RecordingState.EXPIRED] one answers `410`.
 *
 * @param recordings The collection a requested recording is read from.
 * @param cache The cache a local mp4 is served from, touched through, and re-cached through.
 * @param lanes Every recording lane, read for the [Lane.progress] of the one recording a request names.
 */
fun Application.serveRoute(
    recordings: DocumentStore.Collection<Recording>,
    cache: RecordingCache,
    lanes: List<Lane>,
) {
    install(PartialContent)
    routing {
        get("/demos/{name}") {
            val name = call.parameters["name"].orEmpty()
            val demoId = name.removeSuffix(".mp4")
            if (!name.endsWith(".mp4") || !demoId.isUuid()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val recording = recordings[demoId]
            if (recording == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            serve(recording, cache, lanes)
        }
    }
}

private suspend fun RoutingContext.serve(
    recording: Recording,
    cache: RecordingCache,
    lanes: List<Lane>,
) {
    val file = cache.file(recording)
    when {
        file != null -> {
            serveLocal(recording, cache, file)
        }

        recording.state == RecordingState.ARCHIVED -> {
            serveArchived(recording, cache)
        }

        recording.state == RecordingState.PENDING || recording.state == RecordingState.RECORDING -> {
            servePending(recording, lanes)
        }

        recording.state == RecordingState.FAILED -> {
            call.respondJson(HttpStatusCode.Gone) {
                put("state", recording.state.value)
                put("error", recording.error)
            }
        }

        recording.state == RecordingState.EXPIRED -> {
            call.respondJson(HttpStatusCode.Gone) { put("state", recording.state.value) }
        }

        else -> {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private suspend fun RoutingContext.serveLocal(
    recording: Recording,
    cache: RecordingCache,
    file: Path,
) {
    cache.touch(recording)
    call.respondFile(file.toFile())
}

private suspend fun RoutingContext.serveArchived(
    recording: Recording,
    cache: RecordingCache,
) {
    val touched = cache.touch(recording)
    cache.startRecache(touched)
    call.respondRedirect(cache.downloadUrl(touched), permanent = false)
}

private suspend fun RoutingContext.servePending(
    recording: Recording,
    lanes: List<Lane>,
) {
    call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER.inWholeSeconds.toString())
    call.respondJson(HttpStatusCode.Accepted) {
        put("state", recording.state.value)
        if (recording.state == RecordingState.RECORDING) {
            lanes.firstOrNull { it.lane == recording.lane }?.progress?.let { progress ->
                put(
                    "progress",
                    buildJsonObject {
                        put("stage", progress.stage.name)
                        put("progress", progress.progress)
                    },
                )
            }
        }
    }
}

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    build: JsonObjectBuilder.() -> Unit,
) {
    respondText(buildJsonObject(build).toString(), ContentType.Application.Json, status)
}

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess
