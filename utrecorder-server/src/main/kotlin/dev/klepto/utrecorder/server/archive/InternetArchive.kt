package dev.klepto.utrecorder.server.archive

import dev.klepto.utrecorder.common.http.HTTP_SOCKET_TIMEOUT
import dev.klepto.utrecorder.server.ArchiveConfig
import dev.klepto.utrecorder.server.Demo
import io.ktor.client.HttpClient
import io.ktor.client.content.LocalFileContent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Client for the Internet Archive's S3-like upload API and its metadata API.
 *
 * @param config The access key, secret key, collection and identifier prefix of the Archive account.
 * @param engine The Ktor client engine the requests run over. Tests inject a `MockEngine`.
 */
class InternetArchive(
    private val config: ArchiveConfig,
    engine: HttpClientEngine = CIO.create(),
) {
    private val client =
        HttpClient(engine) {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HTTP_SOCKET_TIMEOUT.inWholeMilliseconds
            }
        }

    /**
     * Returns the Archive item identifier of a demo whose source id is [sourceId]: [ArchiveConfig.prefix] followed by
     * a hyphen and the source id.
     */
    fun identifier(sourceId: String): String = "${config.prefix}-$sourceId"

    /**
     * Returns the URL [file] of item [identifier] is downloaded from.
     */
    fun downloadUrl(
        identifier: String,
        file: String,
    ): String = "https://archive.org/download/$identifier/$file"

    /**
     * Uploads [file] as the mp4 of item [identifier], staging it at the Archive without triggering a derive.
     *
     * Title and description metadata are built from [demo]'s map, player and cap time. The request carries no
     * timeout of its own beyond the client's socket idle timeout, because the file may be large.
     */
    suspend fun upload(
        identifier: String,
        file: Path,
        demo: Demo,
    ) {
        client.put("https://s3.us.archive.org/$identifier/${file.name}") {
            header(HttpHeaders.Authorization, "LOW ${config.accessKey}:${config.secretKey}")
            header("x-archive-auto-make-bucket", "1")
            header("x-archive-meta-mediatype", "movies")
            header("x-archive-meta01-collection", config.collection)
            header("x-archive-meta-title", title(demo))
            header("x-archive-meta-description", description(demo))
            header("x-archive-queue-derive", "0")
            setBody(LocalFileContent(file.toFile()))
        }
    }

    /**
     * Returns whether the Archive's metadata for item [identifier] lists [file] with the checksum [md5].
     *
     * A `PUT` success only means the file is staged; this is what confirms the Archive has ingested it.
     */
    suspend fun isArchived(
        identifier: String,
        file: String,
        md5: String,
    ): Boolean = files(identifier).any { it["name"] == file && it["md5"] == md5 }

    /**
     * Returns whether the Archive's metadata for item [identifier] lists [file], whatever its checksum.
     */
    suspend fun lists(
        identifier: String,
        file: String,
    ): Boolean = files(identifier).any { it["name"] == file }

    /**
     * Returns the string fields of every file the Archive's metadata for item [identifier] lists, or none if the item
     * does not exist.
     */
    private suspend fun files(identifier: String): List<Map<String, String>> {
        val body = client.get("https://archive.org/metadata/$identifier").bodyAsText()
        val files = Json.parseToJsonElement(body).jsonObject["files"]?.jsonArray ?: return emptyList()
        return files.map { entry ->
            entry.jsonObject.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it.content } }.toMap()
        }
    }

    private fun title(demo: Demo): String = "${demo.map ?: "Unknown map"} — ${demo.player}"

    private fun description(demo: Demo): String = "Map: ${demo.map ?: "unknown"}. Player: ${demo.player}. Cap time: ${demo.capTime}."
}
