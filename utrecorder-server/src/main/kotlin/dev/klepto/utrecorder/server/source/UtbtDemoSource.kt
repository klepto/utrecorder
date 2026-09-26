package dev.klepto.utrecorder.server.source

import dev.klepto.utrecorder.common.http.HTTP_SOCKET_TIMEOUT
import dev.klepto.utrecorder.server.ServerConfig
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * [DemoSource] over the cap list of UTBT and its demo downloads.
 *
 * @param config The base URL of the cap list, [ServerConfig.sourceUrl], and of the demos, [ServerConfig.demoUrl].
 * @param engine The Ktor client engine the requests run over. Tests inject a `MockEngine`.
 */
class UtbtDemoSource(
    private val config: ServerConfig = ServerConfig(),
    engine: HttpClientEngine = CIO.create(),
) : DemoSource {
    private val client =
        HttpClient(engine) {
            install(HttpTimeout) {
                socketTimeoutMillis = HTTP_SOCKET_TIMEOUT.inWholeMilliseconds
            }
        }

    override suspend fun caps(offset: Int): List<Cap> {
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

    override suspend fun demo(id: String): ByteArray? {
        val response = client.get("${config.demoUrl}/$id")
        if (response.status == HttpStatusCode.NotFound) return null
        response.requireSuccess()
        return response.bodyAsBytes()
    }

    private fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw IOException("${request.url} answered $status")
    }

    private companion object {
        /** The number of caps a page holds. */
        const val PAGE_SIZE = 100

        fun readCap(cap: JsonObject): Cap =
            Cap(
                id = cap.string("id"),
                map = cap.string("map"),
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
