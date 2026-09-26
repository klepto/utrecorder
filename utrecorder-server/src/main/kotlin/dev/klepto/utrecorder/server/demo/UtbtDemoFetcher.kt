package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.http.HTTP_SOCKET_TIMEOUT
import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingLane
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingState
import dev.klepto.utrecorder.ut99.demo.UtDemo
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [DemoFetcher] over the cap list of UTBT and its demo downloads.
 *
 * Two loops find caps: [poll] takes the caps added since the newest known one, and [backfill] walks the older ones
 * while the server is idle. Each cap is handled once: its demo is downloaded into memory, read with [UtDemo.read] for
 * its map and duration, stored, and enqueued in the lane its duration picks. A demo UTBT does not serve, or that does
 * not parse, is stored with a [RecordingState.FAILED] recording and not enqueued. A failed download stores nothing, so
 * a later pass retries it.
 *
 * @property demos The collection the demo of every handled cap is written to.
 * @property recordings The collection the recording of every handled cap is written to, before its demo.
 * @property queue The queue every stored recording is enqueued in.
 * @property uploaderIsEmpty Returns whether the uploader has no recording waiting to be uploaded.
 * @property config The base URL of the cap list, [ServerConfig.sourceUrl], and of the demos, [ServerConfig.demoUrl],
 * the poll interval, the backfill cooldown and the long lane threshold.
 * @param storedDemos The demos stored when the server started.
 * @param engine The Ktor client engine the requests run over.
 */
class UtbtDemoFetcher(
    private val demos: DocumentStore.Collection<Demo>,
    private val recordings: DocumentStore.Collection<Recording>,
    private val queue: RecordingQueue,
    private val uploaderIsEmpty: () -> Boolean,
    private val config: ServerConfig = ServerConfig(),
    storedDemos: List<Demo> = emptyList(),
    engine: HttpClientEngine = CIO.create(),
) : DemoFetcher {
    private val client =
        HttpClient(engine) {
            install(HttpTimeout) {
                socketTimeoutMillis = HTTP_SOCKET_TIMEOUT.inWholeMilliseconds
            }
        }
    private val mutex = Mutex()
    private val known = storedDemos.mapTo(mutableSetOf()) { it.id }
    private var backfillOffset = storedDemos.size
    private var firstPoll = storedDemos.isEmpty()

    /**
     * Runs [backfill] once, and [poll] every [ServerConfig.pollInterval], until cancelled. A failed poll is logged and
     * retried at the next one.
     */
    override suspend fun run(): Nothing =
        coroutineScope {
            launch { backfill() }
            pollForever()
        }

    private suspend fun pollForever(): Nothing {
        while (true) {
            try {
                poll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failure) { "Demo poll failed, retrying in ${config.pollInterval}" }
            }
            delay(config.pollInterval)
        }
    }

    /**
     * Handles, oldest first, every cap added since the newest known one.
     *
     * Reads pages from offset `0` until a page holds a known cap or is empty. When no demo was stored at startup, the
     * first pass takes only the newest cap and leaves the older ones to [backfill]. A pass stops at the first failed
     * download, so the next pass retries it before any newer cap.
     *
     * @throws IOException If UTBT cannot be reached or answers a page with an error.
     */
    suspend fun poll() {
        val unseen = mutableListOf<Cap>()
        var offset = 0
        while (true) {
            val page = fetchCaps(offset)
            val fresh = page.takeWhile { it.id !in known }
            if (firstPoll) {
                unseen += fresh.take(1)
                break
            }
            unseen += fresh
            if (page.isEmpty() || fresh.size < page.size) break
            offset += page.size
        }
        for (cap in unseen.asReversed()) {
            if (!handle(cap)) return
        }
        firstPoll = false
    }

    /**
     * Walks the caps older than those known at startup, newest first, until a page comes back empty.
     *
     * Starts at the number of demos stored at startup and skips every known cap. Before each cap it waits until both
     * the [queue] and the uploader are empty, and after each it waits [ServerConfig.backfillCooldown]. A failed page
     * read or download is logged and retried after the cooldown.
     */
    suspend fun backfill() {
        while (true) {
            val page =
                retrying("Backfill page read at offset $backfillOffset") { fetchCaps(backfillOffset) }
            if (page.isEmpty()) return
            for (cap in page) {
                while (cap.id !in known) {
                    awaitIdle()
                    val handled = handle(cap)
                    delay(config.backfillCooldown)
                    if (handled) break
                }
            }
            backfillOffset += page.size
        }
    }

    private suspend fun awaitIdle() {
        while (!queue.isEmpty || !uploaderIsEmpty()) delay(IDLE_CHECK_INTERVAL)
    }

    private suspend fun <T> retrying(
        action: String,
        block: suspend () -> T,
    ): T {
        while (true) {
            try {
                return block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failure) { "$action failed, retrying in ${config.backfillCooldown}" }
                delay(config.backfillCooldown)
            }
        }
    }

    /**
     * Stores and enqueues [cap] unless it is known. Returns `false` if its download failed and nothing was stored.
     */
    private suspend fun handle(cap: Cap): Boolean =
        mutex.withLock {
            if (cap.id in known) return true
            val bytes =
                try {
                    fetchDemo(cap.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    log.warn(failure) { "Download of demo ${cap.id} failed, retrying later" }
                    return false
                }
            val parsed =
                bytes?.let {
                    try {
                        Result.success(UtDemo.read(it))
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                } ?: Result.failure(IllegalStateException("UTBT does not serve demo ${cap.id}"))
            val demo = parsed.getOrNull()
            val stored =
                Demo(
                    id = cap.id,
                    map = demo?.map,
                    player = cap.alias,
                    capTime = cap.capTime,
                    duration = demo?.duration,
                    addedAt = cap.added,
                ) to
                    Recording(
                        demoId = cap.id,
                        lane = if (demo != null && demo.duration >= config.longThreshold) RecordingLane.LONG else RecordingLane.SHORT,
                        state = if (demo != null) RecordingState.PENDING else RecordingState.FAILED,
                        error = parsed.exceptionOrNull()?.let { it.message ?: it.toString() },
                    )
            recordings.put(stored.second)
            demos.put(stored.first)
            known += cap.id
            if (demo != null) {
                queue.enqueue(stored)
            } else {
                log.warn(parsed.exceptionOrNull()) { "Demo ${cap.id} stored as failed" }
            }
            true
        }

    /**
     * Returns the page of verified caps starting at [offset], newest first. An empty page means [offset] is past the
     * oldest cap.
     */
    private suspend fun fetchCaps(offset: Int): List<Cap> {
        val response =
            client.get("${config.sourceUrl}/caps") {
                parameter("limit", PAGE_SIZE)
                parameter("offset", offset)
                parameter("order", "desc")
                parameter("verified", true)
            }
        response.requireSuccess()
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("data")
            .jsonArray
            .map { readCap(it.jsonObject) }
    }

    /**
     * Returns the bytes of the demo of the cap [id], or `null` if UTBT does not serve it.
     */
    private suspend fun fetchDemo(id: String): ByteArray? {
        val response = client.get("${config.demoUrl}/$id")
        if (response.status == HttpStatusCode.NotFound) return null
        response.requireSuccess()
        return response.bodyAsBytes()
    }

    private fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw IOException("${request.url} answered $status")
    }

    /**
     * A capped run on the cap list.
     *
     * @property id The identifier of the cap at UTBT.
     * @property alias The alias of the player who capped.
     * @property capTime The time the cap took.
     * @property added The time UTBT added the cap.
     */
    private data class Cap(
        val id: String,
        val alias: String,
        val capTime: Duration,
        val added: Instant,
    )

    private companion object {
        /** The number of caps a page holds. */
        const val PAGE_SIZE = 100

        /** The time between two checks of whether the queue and the uploader are empty. */
        val IDLE_CHECK_INTERVAL = 10.seconds

        val log = logger<UtbtDemoFetcher>()

        fun readCap(cap: JsonObject): Cap =
            Cap(
                id = cap.string("id"),
                alias = cap.string("alias"),
                capTime = (cap.getValue("cap_time_seconds").jsonPrimitive.double * 1000).roundToLong().milliseconds,
                added = readAdded(cap.string("added")),
            )

        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        /**
         * Reads UTBT's `added` timestamp, a UTC date and time without a zone such as `2026-09-26 12:19:59.891708`.
         */
        fun readAdded(added: String): Instant = LocalDateTime.parse(added.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
    }
}
