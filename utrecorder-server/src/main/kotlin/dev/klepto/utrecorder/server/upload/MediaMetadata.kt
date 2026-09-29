package dev.klepto.utrecorder.server.upload

/**
 * Description of an uploaded media item, shown where the item is viewed.
 *
 * @property title The title of the item.
 * @property description The description of the item, as plain text. Each line break starts a new line.
 * @property tags The subjects the item is found by.
 */
data class MediaMetadata(
    val title: String,
    val description: String,
    val tags: List<String> = emptyList(),
)
