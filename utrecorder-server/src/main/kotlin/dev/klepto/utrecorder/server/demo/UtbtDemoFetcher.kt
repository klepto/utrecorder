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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DemoFetcher] over the verified cap list of UTBT.
 *
 * Each cap becomes one [Demo] whose [Demo.demoUrl] names its download under [UtbtConfig.demoUrl]. A cap whose id is
 * not a UUID is skipped with a warning.
 *
 * @property config The base URLs of the cap list and of the demo downloads.
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

    override suspend fun fetch(
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
