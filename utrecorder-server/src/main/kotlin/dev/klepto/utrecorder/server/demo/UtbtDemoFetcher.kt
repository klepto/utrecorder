package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.http.HTTP_SOCKET_TIMEOUT
import dev.klepto.utrecorder.common.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * [DemoFetcher] over the verified cap list of UTBT, read newest first one page at a time.
 *
 * Each collection merges two streams, and the head always goes first:
 * - The head: once per cooldown, before the next demo is emitted, reads the caps added after the newest demo seen, and
 *   queues them. If the backlog ended before any demo was read, reads the newest caps instead.
 * - The backlog: on demand, reads the page of caps added before the oldest demo it has read, and emits every demo not
 *   seen yet. The first page is the newest page. A page without an unseen demo ends it.
 *
 * If neither stream holds a demo, the collection waits the cooldown. A failed read is logged. A failed head read
 * discards that poll, and a failed backlog read is retried after the cooldown.
 *
 * Each cap becomes one [Demo] whose [Demo.demoUrl] names its download under [UtbtConfig.demoUrl]. A cap whose id is
 * not a UUID is skipped with a warning.
 *
 * @property config The base URLs of the cap list and of the demo downloads, and the cooldown.
 * @param engine The Ktor client engine the requests run over.
 */
class UtbtDemoFetcher(
    private val config: UtbtConfig = UtbtConfig(),
    engine: HttpClientEngine = CIO.create(),
) : DemoFetcher {
    private val client: HttpClient =
        HttpClient(engine) {
            install(HttpTimeout) { socketTimeoutMillis = HTTP_SOCKET_TIMEOUT.inWholeMilliseconds }
        }

    override suspend fun fetch(): Flow<Demo> =
        flow {
            val search = Search()
            while (true) emit(search.next())
        }

    /**
     * Returns the page of up to [PAGE_LIMIT] demos added before [before] and after [after]. If the read fails, logs the
     * failure and returns `null` instead.
     */
    private suspend fun fetchPageCatching(
        before: Instant? = null,
        after: Instant? = null,
    ): List<Demo>? =
        try {
            fetchPage(before, after)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            log.warn(exception) { "Could not fetch the demos added before $before and after $after" }
            null
        }

    /**
     * Returns the page of up to [PAGE_LIMIT] demos added before [before] and after [after], newest first. A bound that
     * is `null` is left out of the request.
     */
    private suspend fun fetchPage(
        before: Instant?,
        after: Instant?,
    ): List<Demo> {
        val response =
            client.get("${config.apiUrl}/caps") {
                parameter("limit", PAGE_LIMIT)
                parameter("order", "desc")
                parameter("verified", true)
                parameter("before", before?.toEpochParameter())
                parameter("after", after?.toEpochParameter())
            }
        if (!response.status.isSuccess()) throw IOException("UTBT answered ${response.status} for ${response.request.url}")
        return JSON
            .decodeFromString<CapList>(response.bodyAsText())
            .data
            .mapNotNull { toDemo(it) }
    }

    private fun toDemo(cap: Cap): Demo? {
        if (Uuid.parseOrNull(cap.id) == null) {
            log.warn { "Skipping cap ${cap.id}, its id is not a UUID" }
            return null
        }
        return Demo(
            capId = cap.id,
            map = cap.map,
            player = cap.alias,
            capTime = cap.capTimeSeconds.seconds,
            timestamp = Instant.parse("${cap.added.replace(' ', 'T')}Z"),
            demoUrl = "${config.demoUrl}/${cap.id}",
        )
    }

    /**
     * State of one collection of [fetch]: the head queue, the backlog cursor and the cap ids it has seen.
     *
     * A cap id is seen once it is queued at the head or emitted from the backlog.
     */
    private inner class Search {
        private val seen: MutableSet<String> = mutableSetOf()
        private val head: ArrayDeque<Demo> = ArrayDeque()
        private val backlog: ArrayDeque<Demo> = ArrayDeque()
        private var newest: Instant? = null
        private var oldest: Instant? = null
        private var backlogEnded: Boolean = false
        private var headPolled: TimeMark? = null

        /**
         * Returns the next demo to emit.
         *
         * Polls the head first once the cooldown has passed since the last poll. Returns a queued head demo if there is
         * one, else the next backlog demo. If neither has a demo, waits the cooldown and tries again.
         */
        suspend fun next(): Demo {
            while (true) {
                if (isHeadDue()) pollHead()
                val demo = head.removeFirstOrNull() ?: nextBacklogDemo()
                if (demo != null) return demo
                delay(config.cooldown)
            }
        }

        /**
         * Returns `true` if the head was never polled, or the cooldown has passed since the last poll.
         */
        private fun isHeadDue(): Boolean = headPolled?.let { it.elapsedNow() >= config.cooldown } ?: true

        /**
         * Queues the unseen demos added after the newest demo seen, ahead of the demos already queued. Before any
         * demo is read, queues nothing while the backlog is open, and the unseen newest demos once it has ended.
         */
        private suspend fun pollHead() {
            headPolled = TimeSource.Monotonic.markNow()
            if (newest == null && !backlogEnded) return
            val fresh = readHead(newest) ?: return
            seen += fresh.map { it.capId }
            advanceNewest(fresh)
            head.addAll(0, fresh)
        }

        /**
         * Returns the unseen demos added after [after], newest first. Reads pages until one holds no unseen demo. If
         * [after] is `null`, reads from the newest demo on. If a read fails, returns `null` instead.
         */
        private suspend fun readHead(after: Instant?): List<Demo>? {
            val fresh = mutableListOf<Demo>()
            do {
                val page = fetchPageCatching(fresh.lastOrNull()?.timestamp, after) ?: return null
                val unseen = page.filter { it.capId !in seen && it !in fresh }
                fresh += unseen
            } while (unseen.isNotEmpty())
            return fresh
        }

        /**
         * Returns the next unseen demo of the backlog, reading the next page when the read ones run out. If the backlog
         * has ended, or a read fails, returns `null` instead.
         */
        private suspend fun nextBacklogDemo(): Demo? {
            while (backlog.isNotEmpty() || readBacklogPage()) {
                val demo = backlog.removeFirst()
                if (seen.add(demo.capId)) return demo
            }
            return null
        }

        /**
         * Reads the unseen demos of the page added before the oldest demo of the backlog. Returns `false` if the page
         * holds no unseen demo, the backlog has ended, or the read fails. A page without an unseen demo ends the
         * backlog.
         */
        private suspend fun readBacklogPage(): Boolean {
            if (backlogEnded) return false
            val page = fetchPageCatching(before = oldest) ?: return false
            val unseen = page.filter { it.capId !in seen }
            backlogEnded = unseen.isEmpty()
            oldest = page.lastOrNull()?.timestamp ?: oldest
            advanceNewest(page)
            backlog += unseen
            return unseen.isNotEmpty()
        }

        /**
         * Moves the newest time seen up to the newest of [demos].
         */
        private fun advanceNewest(demos: List<Demo>) {
            newest = (demos.map { it.timestamp } + listOfNotNull(newest)).maxOrNull()
        }
    }

    /**
     * One page of the UTBT cap list.
     *
     * @property data The caps of the page, newest first. UTBT leaves it out of a page without caps.
     */
    @Serializable
    private data class CapList(
        val data: List<Cap> = emptyList(),
    )

    /**
     * One cap of the UTBT cap list.
     *
     * @property id The UTBT identifier of the cap.
     * @property map The name of the map the cap was made on.
     * @property alias The alias of the player who capped.
     * @property capTimeSeconds The time the cap took, in seconds.
     * @property added The time UTBT added the cap, in UTC without a zone, such as `2026-09-26 12:19:59.891708`.
     */
    @Serializable
    private data class Cap(
        val id: String,
        val map: String,
        val alias: String,
        @SerialName("cap_time_seconds") val capTimeSeconds: Double,
        val added: String,
    )

    private companion object {
        const val PAGE_LIMIT = 100

        val JSON: Json = Json { ignoreUnknownKeys = true }

        val log = logger<UtbtDemoFetcher>()

        /**
         * Returns this time as the UTBT query parameter: Unix epoch seconds with a fraction of microseconds, such as
         * `1790533924.038369`.
         */
        fun Instant.toEpochParameter(): String = "$epochSeconds.${(nanosecondsOfSecond / 1_000).toString().padStart(6, '0')}"
    }
}
