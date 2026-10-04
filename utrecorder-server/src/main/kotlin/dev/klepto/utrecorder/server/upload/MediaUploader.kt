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
     * Sets the title and description of the existing item [id] to those of [metadata]. Returns normally if the item
     * already has them, or if the item is still being created by an [upload]. Throws if the item cannot be changed.
     */
    suspend fun describe(
        id: String,
        metadata: MediaMetadata,
    )

    /**
     * Deletes the file [name] of the item [id]. Returns normally if the item does not hold the file. Throws if the
     * file cannot be deleted.
     */
    suspend fun delete(
        id: String,
        name: String,
    )

    /**
     * Returns the URL the file [name] of the item [id] downloads from.
     */
    fun downloadUrl(
        id: String,
        name: String,
    ): String
}
