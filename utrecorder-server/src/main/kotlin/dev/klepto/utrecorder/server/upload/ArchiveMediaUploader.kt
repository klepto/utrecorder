package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.http.HTTP_SOCKET_TIMEOUT
import dev.klepto.utrecorder.common.http.encodeUrlPathSegment
import dev.klepto.utrecorder.server.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.content.LocalFileContent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.name

/**
 * [MediaUploader] over the S3-like upload API and the metadata API of the Internet Archive.
 *
 * The Archive identifier of the item `<id>` is `<prefix>-<id>`, with the prefix [ServerConfig.archivePrefix], cut from
 * its end to at most 100 characters. Each item is created in [ServerConfig.archiveCollection] with the media type
 * `movies`, the title, description and subjects of its [MediaMetadata], and without a derive. An upload has no time
 * limit, only the socket timeout.
 *
 * @property config The keys, collection and identifier prefix of the Archive account.
 * @param engine The Ktor client engine the requests run over.
 * @throws IllegalArgumentException If [ServerConfig.archiveAccessKey] or [ServerConfig.archiveSecretKey] is not
 * configured.
 */
class ArchiveMediaUploader(
    private val config: ServerConfig = ServerConfig(),
    engine: HttpClientEngine = CIO.create(),
) : MediaUploader {
    private val authorization: String = authorization(config)
    private val client: HttpClient = createClient(engine)

    override suspend fun upload(
        id: String,
        file: Path,
        metadata: MediaMetadata,
    ) {
        client.put("https://s3.us.archive.org/${identifier(id)}/${file.name}") {
            header(HttpHeaders.Authorization, authorization)
            header("x-archive-auto-make-bucket", "1")
            header("x-archive-meta01-collection", config.archiveCollection)
            header("x-archive-meta-mediatype", "movies")
            header("x-archive-meta-title", encodeValue(metadata.title))
            header("x-archive-meta-description", encodeValue(toHtml(metadata.description)))
            metadata.tags.forEachIndexed { index, tag ->
                header("x-archive-meta%02d-subject".format(index + 1), encodeValue(tag))
            }
            header("x-archive-queue-derive", "0")
            setBody(LocalFileContent(file.toFile()))
        }
    }

    override suspend fun isUploaded(
        id: String,
        md5: String,
    ): Boolean =
        readItem(id)
            .files
            .any { it.md5 == md5 }

    override suspend fun describe(
        id: String,
        metadata: MediaMetadata,
    ) {
        if (readItem(id).metadata?.title == null) return
        val form =
            parameters {
                append("-target", "metadata")
                append("-patch", metadataPatch(metadata).toString())
            }
        val response =
            client.submitForm("https://archive.org/metadata/${identifier(id)}", form) {
                expectSuccess = false
                header(HttpHeaders.Authorization, authorization)
            }
        val body = response.bodyAsText()
        check(response.status.isSuccess() || isUnchanged(response.status, body)) {
            "The Archive refused to describe ${identifier(id)}: ${response.status} $body"
        }
    }

    override suspend fun delete(
        id: String,
        name: String,
    ) {
        val response =
            client.delete("https://s3.us.archive.org/${identifier(id)}/$name") {
                expectSuccess = false
                header(HttpHeaders.Authorization, authorization)
                header("x-archive-cascade-delete", "1")
            }
        check(response.status.isSuccess() || response.status == HttpStatusCode.NotFound) {
            "The Archive refused to delete $name of ${identifier(id)}: ${response.status}"
        }
    }

    override fun downloadUrl(
        id: String,
        name: String,
    ): String = "https://archive.org/download/${identifier(id)}/$name"

    /**
     * Returns the Archive identifier of the item [id].
     */
    private fun identifier(id: String): String = "${config.archivePrefix}-$id".take(IDENTIFIER_MAX_LENGTH)

    /**
     * Returns the metadata of the item [id]. Throws if the item cannot be read.
     */
    private suspend fun readItem(id: String): ItemMetadata {
        val body = client.get("https://archive.org/metadata/${identifier(id)}").bodyAsText()
        return JSON.decodeFromString(body)
    }

    /**
     * Metadata of one Archive item.
     *
     * @property files The files of the item. The Archive leaves it out for an item that does not exist.
     * @property metadata The fields of the item, or `null` if the Archive lists none.
     */
    @Serializable
    private data class ItemMetadata(
        val files: List<ItemFile> = emptyList(),
        val metadata: ItemFields? = null,
    )

    /**
     * Fields of one Archive item.
     *
     * @property title The title of the item, or `null` until the Archive has created the item.
     */
    @Serializable
    private data class ItemFields(
        val title: String? = null,
    )

    /**
     * One file of an Archive item.
     *
     * @property md5 The MD5 checksum of the file, or `null` if the Archive lists none.
     */
    @Serializable
    private data class ItemFile(
        val md5: String? = null,
    )

    private companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }
        const val IDENTIFIER_MAX_LENGTH: Int = 100
        const val UNCHANGED_METADATA_ERROR: String = "no changes to _meta.xml"

        /**
         * Returns `true` if the Metadata API answered [status] with [body] because the item already holds the metadata it
         * was sent.
         */
        fun isUnchanged(
            status: HttpStatusCode,
            body: String,
        ): Boolean = status == HttpStatusCode.BadRequest && UNCHANGED_METADATA_ERROR in body

        /**
         * Returns [value] as an Archive metadata header value: `uri(<value>)`, with [value] percent-encoded as UTF-8.
         */
        fun encodeValue(value: String): String = "uri(${value.encodeUrlPathSegment()})"

        /**
         * Returns the JSON Patch that replaces the title and description of an item with those of [metadata].
         */
        fun metadataPatch(metadata: MediaMetadata): JsonArray =
            buildJsonArray {
                addJsonObject {
                    put("op", "replace")
                    put("path", "/title")
                    put("value", metadata.title)
                }
                addJsonObject {
                    put("op", "replace")
                    put("path", "/description")
                    put("value", toHtml(metadata.description))
                }
            }

        /**
         * Returns [text] as HTML, with its markup characters escaped and each line break as `<br />`.
         */
        fun toHtml(text: String): String =
            text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br />")

        /**
         * Returns the `LOW <access>:<secret>` authorization of [config].
         */
        fun authorization(config: ServerConfig): String {
            val accessKey = requireNotNull(config.archiveAccessKey) { "The Archive access key is not configured" }
            val secretKey = requireNotNull(config.archiveSecretKey) { "The Archive secret key is not configured" }
            return "LOW $accessKey:$secretKey"
        }

        fun createClient(engine: HttpClientEngine): HttpClient =
            HttpClient(engine) {
                expectSuccess = true
                install(HttpTimeout) {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HTTP_SOCKET_TIMEOUT.inWholeMilliseconds
                }
            }
    }
}
