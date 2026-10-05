package dev.klepto.utrecorder.server.statistics

import dev.klepto.utrecorder.server.cache.CacheUsage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Statistics of the server at one point in time, as [StatisticsReport.read] computes them.
 *
 * Every day is a UTC calendar day, and every day series holds the 30 days that end with the day of [generatedAt], oldest
 * first. A "last day" figure covers the 24 hours before [generatedAt].
 *
 * @property generatedAt The time the statistics were computed at.
 * @property queue The recordings waiting for a recorder.
 * @property recorder What the recorder has recorded.
 * @property storage The mp4 files in the cache and in the Archive.
 * @property personalBests The personal bests among the demos the server holds.
 */
@Serializable
data class Statistics(
    val generatedAt: Instant,
    val queue: QueueStatistics,
    val recorder: RecorderStatistics,
    val storage: StorageStatistics,
    val personalBests: PersonalBestStatistics,
)

/**
 * Statistics of the queue.
 *
 * @property size The number of pending recordings in the queue.
 * @property recent The number of them whose demo is recent.
 * @property backlog The number of them whose demo is not recent, [size] minus [recent].
 * @property capTime The sum of the cap times of every queued demo.
 * @property recording The number of recordings a recorder is running.
 * @property failed The number of failed recordings.
 * @property unresolvable The number of unresolvable recordings.
 */
@Serializable
data class QueueStatistics(
    val size: Int,
    val recent: Int,
    val backlog: Int,
    val capTime: Duration,
    val recording: Int,
    val failed: Int,
    val unresolvable: Int,
)

/**
 * Statistics of the recorder.
 *
 * @property pace The processing time per second of cap time over the recordings recorded in the last 7 days with a
 * processing time, as a ratio of sums, or `null` if there is none.
 * @property lastDay The recordings recorded in the last 24 hours.
 * @property days The recordings recorded on each of the 30 days.
 * @property completed The number of recorded, uploaded or archived recordings.
 * @property views The sum of the stored views of every recording.
 */
@Serializable
data class RecorderStatistics(
    val pace: Double?,
    val lastDay: DayOutput,
    val days: List<RecorderDay>,
    val completed: Int,
    val views: Long,
)

/**
 * Recordings recorded in one window.
 *
 * @property recordings The number of recordings.
 * @property capTime The sum of the cap times of their demos.
 */
@Serializable
data class DayOutput(
    val recordings: Int,
    val capTime: Duration,
)

/**
 * Recordings recorded on one day.
 *
 * @property date The day.
 * @property recordings The number of recordings.
 * @property capTime The sum of the cap times of their demos.
 * @property processingTime The sum of their processing times.
 */
@Serializable
data class RecorderDay(
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val recordings: Int,
    val capTime: Duration,
    val processingTime: Duration,
)

/**
 * Statistics of the stored mp4 files.
 *
 * @property cache The usage of the cache, or `null` before its first pass.
 * @property cacheTargetBytes The size in bytes the cache is trimmed down to.
 * @property cacheMaxBytes The size in bytes the cache may take before it is trimmed.
 * @property archived The number of archived recordings.
 * @property archivedBytes The sum of the known sizes of the archived recordings.
 * @property archivedUnsized The number of archived recordings without a known size.
 * @property uploadsLastDay The number of uploaded or archived recordings whose status changed in the last 24 hours.
 * @property dailyUploads The most uploads the server starts in one day.
 */
@Serializable
data class StorageStatistics(
    val cache: CacheUsage?,
    val cacheTargetBytes: Long,
    val cacheMaxBytes: Long,
    val archived: Int,
    val archivedBytes: Long,
    val archivedUnsized: Int,
    val uploadsLastDay: Int,
    val dailyUploads: Int,
)

/**
 * Statistics of the personal bests.
 *
 * @property lastDay The number of personal bests set in the last 24 hours.
 * @property days The personal bests set on each of the 30 days.
 * @property players The number of distinct players with a personal best in the 30 days.
 * @property demos The number of demos the server holds.
 * @property maps The 5 maps with the most personal bests in the 30 days, most first, ties by name.
 */
@Serializable
data class PersonalBestStatistics(
    val lastDay: Int,
    val days: List<PersonalBestDay>,
    val players: Int,
    val demos: Int,
    val maps: List<MapCount>,
)

/**
 * Personal bests set on one day.
 *
 * @property date The day.
 * @property personalBests The number of personal bests.
 * @property players The number of distinct players who set them.
 */
@Serializable
data class PersonalBestDay(
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val personalBests: Int,
    val players: Int,
)

/**
 * Personal bests set on one map.
 *
 * @property map The name of the map.
 * @property personalBests The number of personal bests.
 */
@Serializable
data class MapCount(
    val map: String,
    val personalBests: Int,
)

/**
 * Serializer of a [LocalDate] as its ISO-8601 string, `YYYY-MM-DD`.
 */
private object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: LocalDate,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
