package dev.klepto.utrecorder.server.statistics

import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unavailable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Computes the [Statistics] of the server from the data it holds in memory, anew on every [read].
 *
 * Days are UTC calendar days. Every day series holds [DAYS] entries, oldest first, ending with the day of now; a day
 * with nothing in it is an entry of zeros. A "last day" figure covers the 24 hours before now, now included.
 *
 * - Recorded cap time sums the [Demo.capTime] of every recording whose [Recording.recordedAt] falls in the window,
 *   whatever its status now.
 * - Pace is the sum of [Recording.processingTime] over the sum of [Demo.capTime] of the recordings with a processing
 *   time recorded in the last 7 days.
 * - A personal best is a demo with a [Demo.userId] whose [Demo.capTime] is strictly less than that of every earlier demo
 *   of the same player on the same map, by [Demo.timestamp], then [Demo.capId]. It counts on the day of its timestamp.
 * - The queue is [RecordingQueue.get] of every recording, and a queued demo is recent as [RecordingQueue.isRecent] says.
 * - The cache limits are the bytes of [CacheService.usage] plus the usable bytes it left, minus
 *   [ServerConfig.cacheMinFreeBytes] for the maximum and [ServerConfig.cacheTargetFreeBytes] for the target, each at
 *   least 0. Both are `null` before the first pass, or when the pass could not read the usable bytes.
 *
 * @property demos The demos personal bests are found among.
 * @property recordings The recordings the recorder and storage figures are computed from.
 * @property queue The queue whose recordings are counted.
 * @property jobs The running recordings.
 * @property cache The cache whose usage is reported.
 * @property config The free space limits the cache limits derive from, and the daily uploads reported.
 * @property clock The source of now.
 */
class StatisticsReport(
    private val demos: DemoRepository,
    private val recordings: RecordingRepository,
    private val queue: RecordingQueue,
    private val jobs: RecordingJobs,
    private val cache: CacheService,
    private val config: ServerConfig = ServerConfig(),
    private val clock: Clock = Clock.System,
) {
    /**
     * Returns the statistics of the server now. Suspends until the queue has been built once.
     */
    suspend fun read(): Statistics {
        val queued = queue.get(Int.MAX_VALUE)
        val all = recordings.all()
        val running = jobs.jobs.first().size
        val now = clock.now()
        val days = days(now)
        return Statistics(
            generatedAt = now,
            queue = queue(queued, all, running),
            recorder = recorder(all, now, days),
            storage = storage(all, now),
            personalBests = personalBests(demos.all(), now, days),
        )
    }

    /**
     * Returns the statistics of [queued], with [running] recordings running and the given-up recordings of [all].
     */
    private fun queue(
        queued: List<Recording>,
        all: List<Recording>,
        running: Int,
    ): QueueStatistics {
        val recent = queued.count { queue.isRecent(it.demo) }
        return QueueStatistics(
            size = queued.size,
            recent = recent,
            backlog = queued.size - recent,
            capTime = queued.total { it.demo.capTime },
            recording = running,
            failed = all.count { it.status == Failed },
            unresolvable = all.count { it.status == Unresolvable },
            unavailable = all.count { it.status == Unavailable },
        )
    }

    /**
     * Returns the recorder statistics of [all] at [now], with one entry for each of [days].
     */
    private fun recorder(
        all: List<Recording>,
        now: Instant,
        days: List<LocalDate>,
    ): RecorderStatistics {
        val recorded = all.mapNotNull { recording -> recording.recordedAt?.let { recording to it } }
        val lastDay = recorded.filter { (_, recordedAt) -> isWithin(recordedAt, now, 1.days) }.map { it.first }
        val byDay = recorded.groupBy({ (_, recordedAt) -> date(recordedAt) }, { it.first })
        return RecorderStatistics(
            pace = pace(recorded.filter { (_, recordedAt) -> isWithin(recordedAt, now, PACE_WINDOW) }.map { it.first }),
            lastDay = DayOutput(lastDay.size, lastDay.total { it.demo.capTime }),
            days =
                days.map { date ->
                    val day = byDay[date].orEmpty()
                    RecorderDay(
                        date = date,
                        recordings = day.size,
                        capTime = day.total { it.demo.capTime },
                        processingTime = day.total { it.processingTime ?: Duration.ZERO },
                    )
                },
            completed = all.count { it.status.isCompleted },
            views = all.sumOf { it.views.toLong() },
        )
    }

    /**
     * Returns the processing time per second of cap time of the recordings of [recordings] with a processing time. If
     * there is none, or their cap times sum to zero, returns `null` instead.
     */
    private fun pace(recordings: List<Recording>): Double? {
        val timed = recordings.filter { it.processingTime != null }
        val capTime = timed.total { it.demo.capTime }
        if (capTime <= Duration.ZERO) return null
        return timed.total { it.processingTime ?: Duration.ZERO } / capTime
    }

    /**
     * Returns the storage statistics of [all] at [now].
     */
    private fun storage(
        all: List<Recording>,
        now: Instant,
    ): StorageStatistics {
        val archived = all.filter { it.status == Archived }
        val usage = cache.usage
        val capacity = usage?.usableBytes?.let { usage.bytes + it }
        return StorageStatistics(
            cache = usage,
            cacheTargetBytes = capacity?.let { maxOf(0, it - config.cacheTargetFreeBytes) },
            cacheMaxBytes = capacity?.let { maxOf(0, it - config.cacheMinFreeBytes) },
            archived = archived.size,
            archivedBytes = archived.sumOf { it.size ?: 0L },
            archivedUnsized = archived.count { it.size == null },
            uploadsLastDay = all.count { it.status in UPLOADED && isWithin(it.lastUpdate, now, 1.days) },
            dailyUploads = config.dailyUploads,
        )
    }

    /**
     * Returns the personal best statistics of [all] at [now], with one entry for each of [days].
     */
    private fun personalBests(
        all: List<Demo>,
        now: Instant,
        days: List<LocalDate>,
    ): PersonalBestStatistics {
        val dates = days.toSet()
        val personalBests = personalBests(all)
        val inDays = personalBests.filter { date(it.timestamp) in dates }
        val byDay = inDays.groupBy { date(it.timestamp) }
        return PersonalBestStatistics(
            lastDay = personalBests.count { isWithin(it.timestamp, now, 1.days) },
            days =
                days.map { date ->
                    val day = byDay[date].orEmpty()
                    PersonalBestDay(date, day.size, day.distinctBy { it.userId }.size)
                },
            players = inDays.distinctBy { it.userId }.size,
            demos = all.size,
            maps =
                inDays
                    .groupingBy { it.map }
                    .eachCount()
                    .map { (map, count) -> MapCount(map, count) }
                    .sortedWith(compareByDescending<MapCount> { it.personalBests }.thenBy { it.map })
                    .take(TOP_MAPS),
        )
    }

    private companion object {
        const val DAYS = 30

        const val TOP_MAPS = 5

        const val SECONDS_PER_DAY = 86_400L

        val PACE_WINDOW = 7.days

        val UPLOADED = setOf(Uploaded, Archived)

        val CHRONOLOGICAL: Comparator<Demo> = compareBy<Demo> { it.timestamp }.thenBy { it.capId }

        /**
         * Returns the [DAYS] UTC days that end with the day of [now], oldest first.
         */
        fun days(now: Instant): List<LocalDate> {
            val today = date(now)
            return (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        }

        /**
         * Returns the UTC day of [instant].
         */
        fun date(instant: Instant): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(instant.epochSeconds, SECONDS_PER_DAY))

        /**
         * Returns `true` if [instant] lies in the [window] before [now], [now] included.
         */
        fun isWithin(
            instant: Instant,
            now: Instant,
            window: Duration,
        ): Boolean = instant > now - window && instant <= now

        /**
         * Returns the sum of the durations [selector] returns for each of these items.
         */
        fun <T> List<T>.total(selector: (T) -> Duration): Duration = fold(Duration.ZERO) { sum, item -> sum + selector(item) }

        /**
         * Returns the personal bests of [all], oldest first.
         */
        fun personalBests(all: List<Demo>): List<Demo> {
            val best = HashMap<Pair<String?, String>, Duration>()
            return all
                .filter { it.userId != null }
                .sortedWith(CHRONOLOGICAL)
                .filter { demo ->
                    val key = demo.userId to demo.map
                    val previous = best[key]
                    (previous == null || demo.capTime < previous).also { if (it) best[key] = demo.capTime }
                }
        }
    }
}
