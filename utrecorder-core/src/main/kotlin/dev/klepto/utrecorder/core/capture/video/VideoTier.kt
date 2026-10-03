package dev.klepto.utrecorder.core.capture.video

/**
 * One rung of the ladder of recording tiers: a resolution, the range of video bitrates it is recorded at, and the
 * encoder preset it is recorded with.
 *
 * @property width The width of the video, even and positive.
 * @property height The height of the video, even and positive.
 * @property minimumBitrate The lowest video bitrate the tier is recorded at, in bits per second.
 * @property maximumBitrate The highest video bitrate the tier is recorded at, in bits per second.
 * @property preset The encoder preset of the tier, or `null` for an encoder that takes none.
 * @throws IllegalArgumentException If [width] or [height] is odd or not positive, if [minimumBitrate] is not positive,
 * or if [minimumBitrate] is above [maximumBitrate].
 */
data class VideoTier(
    val width: Int,
    val height: Int,
    val minimumBitrate: Long,
    val maximumBitrate: Long,
    val preset: String?,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "The resolution ${width}x$height is not even and positive"
        }
        require(minimumBitrate > 0) { "The minimum bitrate $minimumBitrate is not positive" }
        require(minimumBitrate <= maximumBitrate) {
            "The minimum bitrate $minimumBitrate is above the maximum bitrate $maximumBitrate"
        }
    }
}
