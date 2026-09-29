package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.http.HTTP_SOCKET_TIMEOUT
import dev.klepto.utrecorder.common.http.encodeUrlPathSegment
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.name

/**
 * [MediaUploader] over the S3-like upload API and the metadata API of the Internet Archive.
 *
 * The Archive identifier of the item `<id>` is `<prefix>-<id>`, with the prefix [ArchiveConfig.prefix]. Each item is
 * created in [ArchiveConfig.collection] with the media type `movies`, the title, description and subjects of its
 * [MediaMetadata], and without a derive. An upload has no time limit, only the socket timeout.
 *
 * @property config The keys, collection and identifier prefix of the Archive account.
 * @param engine The Ktor client engine the requests run over.
 * @throws IllegalArgumentException If [ArchiveConfig.accessKey] or [ArchiveConfig.secretKey] is not configured.
 */
class ArchiveMediaUploader(
    private val config: ArchiveConfig = ArchiveConfig(),
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
            header("x-archive-meta01-collection", config.collection)
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
    ): Boolean {
        val body = client.get("https://archive.org/metadata/${identifier(id)}").bodyAsText()
        return JSON
            .decodeFromString<ItemMetadata>(body)
            .files
            .any { it.md5 == md5 }
    }

    override fun downloadUrl(
        id: String,
        name: String,
    ): String = "https://archive.org/download/${identifier(id)}/$name"

    /**
     * Returns the Archive identifier of the item [id].
     */
    private fun identifier(id: String): String = "${config.prefix}-$id"

    /**
     * Metadata of one Archive item.
     *
     * @property files The files of the item. The Archive leaves it out for an item that does not exist.
     */
    @Serializable
    private data class ItemMetadata(
        val files: List<ItemFile> = emptyList(),
    )

    /**
     * One file of an Archive item.
     *
     * @property name The name of the file inside the item.
     * @property md5 The MD5 checksum of the file, or `null` if the Archive lists none.
     */
    @Serializable
    private data class ItemFile(
        val name: String,
        val md5: String? = null,
    )

    private companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }

        /**
         * Returns [value] as an Archive metadata header value: `uri(<value>)`, with [value] percent-encoded as UTF-8.
         */
        fun encodeValue(value: String): String = "uri(${value.encodeUrlPathSegment()})"

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
        fun authorization(config: ArchiveConfig): String {
            val accessKey = requireNotNull(config.accessKey) { "The Archive access key is not configured" }
            val secretKey = requireNotNull(config.secretKey) { "The Archive secret key is not configured" }
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
