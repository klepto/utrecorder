package dev.klepto.utrecorder.core.capture.video

/**
 * Resolution, video bitrate and encoder preset one run is recorded with.
 *
 * @property width The width of the video.
 * @property height The height of the video.
 * @property bitrate The average video bitrate of the encode, in bits per second, a whole number of kilobits.
 * @property preset The encoder preset of the encode, or `null` for an encoder that takes none.
 * @property fitsBudget Whether the size budget chose the format. It is `false` for the fallback, which a run takes
 * when no tier fits the budget.
 */
data class VideoFormat(
    val width: Int,
    val height: Int,
    val bitrate: Long,
    val preset: String?,
    val fitsBudget: Boolean,
)
