package dev.klepto.utrecorder.server.upload

import java.nio.file.Path

/**
 * Remote storage of media files, one item per identifier.
 *
 * The default implementation is [ArchiveMediaUploader].
 */
interface MediaUploader {
    /**
     * Uploads [file] into the item [id], under the name of [file], and describes the item with [metadata]. Throws if
     * the upload fails.
     *
     * An upload that returns may not be stored yet. Use [isUploaded] to confirm it.
     */
    suspend fun upload(
        id: String,
        file: Path,
        metadata: MediaMetadata,
    )

    /**
     * Returns `true` if the item [id] holds a file whose MD5 checksum is [md5]. Throws if the item cannot be read.
     */
    suspend fun isUploaded(
        id: String,
        md5: String,
    ): Boolean

    /**
     * Returns the URL the file [name] of the item [id] downloads from.
     */
    fun downloadUrl(
        id: String,
        name: String,
    ): String
}
