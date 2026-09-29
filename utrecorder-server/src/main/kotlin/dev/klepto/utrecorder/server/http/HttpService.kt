package dev.klepto.utrecorder.server.http

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingJob
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Instant

/**
 * [Service] that serves the recordings of [repository] and the state of the server over HTTP on [ServerConfig.port].
 *
 * The routes are:
 * - `GET /`: an empty page.
 * - `GET /demo/{capId}`: the mp4 of a [Recorded], [Uploaded] or [Archived] recording, with range support. If the
 *   recording is [Archived] and its mp4 is not in [ServerConfig.recordings], redirects to the download URL of [cache]
 *   instead. Every other request answers `404`.
 * - `GET /api/jobs`: every running recording, with its attempt and progress.
 * - `GET /api/queue`: the next 10 demos to be recorded, in the order they are taken.
 * - `GET /api/recordings`: the 10 most recently recorded [Recorded], [Uploaded] or [Archived] recordings, newest
 *   first. A recording without [Recording.recordedAt] comes after every other one, ordered by the time its demo was
 *   added.
 *
 * Every request of `/demo/{capId}` for a stored recording counts one view of it through [cache].
 *
 * @property repository The recordings to serve.
 * @property jobs The running recordings.
 * @property cache The cache that counts the views and names the download URL of an archived mp4, or `null` if nothing
 * is archived.
 * @property config The recordings directory and the port.
 */
class HttpService(
    private val repository: RecordingRepository,
    private val jobs: RecordingJobs,
    private val cache: CacheService? = null,
    private val config: ServerConfig = ServerConfig(),
) : Service {
    override suspend fun start() {
        val server = embeddedServer(CIO, port = config.port) { module(this) }
        server.startSuspend()
        log.info { "Listening on port ${config.port}" }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { server.stopSuspend() }
        }
    }

    /**
     * Installs the plugins and the routes of this service on [application].
     */
    fun module(application: Application) {
        application.install(PartialContent)
        application.install(ContentNegotiation) { json() }
        application.routing {
            get("/") { call.respond(HttpStatusCode.OK) }
            get("/demo/{capId}") { serveDemo(call.parameters["capId"].orEmpty()) }
            get("/api/jobs") { call.respond(jobs.jobs.first().map { it.toResponse() }) }
            get("/api/queue") { call.respond(repository.pending(LIMIT)) }
            get("/api/recordings") { call.respond(latestRecordings()) }
        }
    }

    /**
     * Answers with the mp4 of the recording [capId], a redirect to its download URL, or `404`.
     */
    private suspend fun RoutingContext.serveDemo(capId: String) {
        val recording = view(capId)
        if (recording == null || recording.status !in COMPLETED) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val mp4 = mp4(recording)
        when {
            exists(mp4) -> call.respondFile(mp4.toFile())
            recording.status == Archived && cache != null -> call.respondRedirect(cache.downloadUrl(recording.demo.capId))
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    /**
     * Returns the recording stored under [capId], after counting one view of it through [cache]. If there is none,
     * returns `null` instead.
     */
    private suspend fun view(capId: String): Recording? =
        when (cache) {
            null -> repository[capId]
            else -> cache.view(capId)
        }

    /**
     * Returns the [LIMIT] most recently recorded [Recorded], [Uploaded] or [Archived] recordings, newest first.
     */
    private suspend fun latestRecordings(): List<Recording> =
        repository
            .all()
            .filter { it.status in COMPLETED }
            .sortedWith(NEWEST_RECORDED)
            .take(LIMIT)

    /**
     * Returns the file that the mp4 of [recording] is stored at.
     */
    private fun mp4(recording: Recording): Path = config.recordings["${recording.demo.capId}.mp4"]

    private suspend fun exists(file: Path): Boolean = withContext(Dispatchers.IO) { file.exists() }

    /**
     * One running recording, as `/api/jobs` lists it.
     *
     * @property demo The demo being recorded.
     * @property attempt The number of the current attempt, starting at `1`.
     * @property stage The stage of the current attempt, or `null` if it has not reported yet.
     * @property progress How much of [stage] is done, from `0.0` to `1.0`, or `null` if it has not reported yet.
     * @property startedAt The time the first attempt started.
     */
    @Serializable
    private data class JobResponse(
        val demo: Demo,
        val attempt: Int,
        val stage: RecorderStage?,
        val progress: Double?,
        val startedAt: Instant,
    )

    private companion object {
        const val LIMIT = 10

        val COMPLETED = setOf(Recorded, Uploaded, Archived)

        val NEWEST_RECORDED: Comparator<Recording> =
            compareByDescending<Recording> { it.recordedAt }
                .thenByDescending { it.demo.timestamp }

        val log = logger<HttpService>()

        fun RecordingJob.toResponse(): JobResponse = JobResponse(demo, attempt, progress?.state, progress?.progress, startedAt)
    }
}
