package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * [Service] that keeps a [Pending] recording in [recordings] for each demo of [demos] that waits for a recorder, and
 * orders those recordings behind the backlog gates.
 *
 * Only the fastest demo of each player with the same teammates on each map is queued. Players are told apart by
 * [Demo.userId], teams by [Demo.teammates]. The time compared is the team time: its own [Demo.capTime] for a solo demo,
 * and the slowest [Demo.capTime] among the stored demos of its [Demo.teamRunId] for a team demo. A team demo is complete
 * once, for each of its [Demo.teammates], a stored demo with that [Demo.userId] and the same [Demo.teamRunId] exists; a
 * team demo without a [Demo.teamRunId] is never complete. An incomplete team demo is neither queued nor compared: it
 * beats nothing and outdates nothing until the build after the missing demos are stored. Of two demos with the same team
 * time, the one added first is queued. A demo without a [Demo.userId] is never queued. A [Recorded], [Uploaded] or
 * [Archived] recording stays chosen until a faster recording of the same player with the same teammates on the same map
 * is [Recorded], [Uploaded] or [Archived] as well; it is set to [Outdated] then. Every recording that has not been
 * recorded and whose demo is no longer chosen is removed. A [RecordingStatus.Recording] one is left alone until its
 * capture ends.
 *
 * On [start], every [RecordingStatus.Recording] recording was interrupted by a server stop: its
 * [Recording.interruptions] goes up by one, and it is set to [Pending] again below [ServerConfig.recordingMaxAttempts],
 * or to [Failed] at it. Every [Failed] recording whose [Recording.interruptions] is below
 * [ServerConfig.recordingMaxAttempts], and every [Unresolvable] one, is set to [Pending] again. All of it is one
 * transaction, then the queue is built. After that, the queue is built again whenever [DemoRepository.changes] or
 * [RecordingRepository.statusChanges] signals a change, and every [Failed] or [Unresolvable] recording is set to
 * [Pending] again once every [ServerConfig.recordingRetryInterval]. A build that changes nothing changes no status.
 *
 * Every build sets the [Recording.runMedal] of each recording: the [Demo.medal] of the fastest stored demo of its
 * [Demo.teamRunId] that has a medal, or its own [Demo.medal] for a solo demo.
 *
 * The recordings are ordered key by key: recent first, then higher [Recording.runMedal], then shorter team time, then
 * longer [Demo.capTime], then newer [Demo.timestamp]. The team time of a recording is its own [Demo.capTime] for a solo
 * demo, and the slowest [Demo.capTime] among the recordings of its [Demo.teamRunId] for a team demo. Ties keep the order
 * [RecordingRepository.all] gives. A demo is recent while it is at most
 * [ServerConfig.recordingRecentAge] old, a demo whose timestamp lies in the future included; any other demo is a backlog
 * demo. A recording whose [Recording.runMedal] is not set yet ranks by its [Demo.medal]. A medal that is `null` or
 * outside `0` to `6` ranks as `0`, casual.
 *
 * Every [Pending] recording is sorted again on each call. Neither [get] nor [first] changes a status, and both wait until
 * [start] has built the queue once.
 *
 * @property demos The demos to choose from.
 * @property recordings The recordings of the chosen demos.
 * @property config The retry interval, the interruption limit, the recent age, the backlog buffer, the archive delay
 * and the daily uploads.
 * @property clock The source of now, which the age of a demo is measured against.
 */
class RecordingQueue(
    private val demos: DemoRepository,
    private val recordings: RecordingRepository,
    private val config: ServerConfig = ServerConfig(),
    private val clock: Clock = Clock.System,
) : Service {
    private val building = Mutex()
    private val built = CompletableDeferred<Unit>()

    override suspend fun start(): Unit =
        coroutineScope {
            requeue()
            build()
            built.complete(Unit)
            launch { merge(demos.changes, recordings.statusChanges).collect { build() } }
            retryLoop()
        }

    /**
     * Returns the head of the queue. Suspends until [start] has built the queue once. Suspends while the queue is empty,
     * or while the head is a backlog recording and either the backlog buffer is full or the pace has no free slot.
     *
     * The backlog buffer is full while at least [ServerConfig.recordingBacklogBuffer] recordings are
     * [RecordingStatus.Recording], or [Recorded] at least [ServerConfig.archiveDelay] ago. A [Recorded] recording
     * without [Recording.recordedAt] counts as recorded long enough ago.
     *
     * The pace has a free slot once the recordings started in the last day, laid out one day divided by
     * [ServerConfig.dailyUploads] apart and each no earlier than it started, end before now. A recording started at its
     * [Recording.recordedAt] minus its [Recording.processingTime], and a [RecordingStatus.Recording] one at its
     * [Recording.lastUpdate], the time it was claimed.
     *
     * The head is looked up again whenever [RecordingRepository.statusChanges] signals a change, and once the pace
     * has a free slot.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun first(): Recording {
        built.await()
        return recordings.statusChanges
            .mapLatest { head() }
            .filterNotNull()
            .first()
    }

    /**
     * Returns the head of the queue with the earliest time a recorder may take it. Suspends until [start] has built the
     * queue once. If the queue is empty, returns `null` instead.
     *
     * The time is now for a recent head, and for a backlog head while the pace has a free slot. For a backlog head while
     * the pace has no free slot, it is the next free slot. While the head is a backlog recording and the backlog buffer
     * is full, the time is `null`. The gates are those of [first].
     */
    suspend fun next(): QueueHead? {
        built.await()
        val all = recordings.all()
        val now = clock.now()
        val head = sorted(all, now).firstOrNull() ?: return null
        return QueueHead(head, startsAt(head, all, now))
    }

    /**
     * Returns the first [maxSize] recordings of the queue, in order. If the queue is shorter, returns all of them.
     * Suspends until [start] has built the queue once.
     *
     * @throws IllegalArgumentException If [maxSize] is negative.
     */
    suspend fun get(maxSize: Int): List<Recording> {
        require(maxSize >= 0) { "Max size must not be negative, was $maxSize" }
        built.await()
        return sorted(recordings.all(), clock.now()).take(maxSize)
    }

    /**
     * Returns `true` if [demo] is recent: at most [ServerConfig.recordingRecentAge] old at the time [clock] reports, a
     * demo whose timestamp lies in the future included. Any other demo is a backlog demo.
     */
    fun isRecent(demo: Demo): Boolean = isRecent(demo, clock.now())

    /**
     * Counts an interruption of every [RecordingStatus.Recording] recording of [recordings], and sets it to [Pending]
     * or, at [ServerConfig.recordingMaxAttempts], to [Failed]. Sets every [Failed] recording below that limit and every
     * [Unresolvable] one to [Pending]. Runs as one transaction.
     */
    private suspend fun requeue() {
        recordings.updateAll { requeued(it, config.recordingMaxAttempts) }
    }

    /**
     * Runs [retry] once every [ServerConfig.recordingRetryInterval].
     */
    private suspend fun retryLoop() {
        while (isRunning()) {
            delay(config.recordingRetryInterval)
            retry()
        }
    }

    /**
     * Sets every [Failed] or [Unresolvable] recording of [recordings] to [Pending].
     */
    private suspend fun retry() {
        setStatus(Pending) { it.status in GIVEN_UP }
    }

    /**
     * Stores a recording of the fastest demo of each player with the same teammates on each map in [recordings]. Chooses
     * those demos, and the fastest [completed][RecordingStatus.isCompleted] recording of each player with the same
     * teammates on each map, comparing the team time over every stored demo and skipping every incomplete team demo.
     * Removes every recording that has not been recorded and whose demo is not chosen, and sets every
     * [completed][RecordingStatus.isCompleted] recording whose demo is complete but not chosen to [Outdated]. Sets the
     * [Recording.runMedal] of every recording.
     */
    private suspend fun build(): Unit =
        building.withLock {
            val all = demos.all()
            val runs = runs(all)
            val fastest = fastest(all, runs)
            recordings += fastest
            val completed = recordings.all().filter { it.status.isCompleted }
            val chosen = (fastest + fastest(completed.map { it.demo }, runs)).mapTo(HashSet()) { it.capId }
            val beaten =
                completed
                    .map { it.demo }
                    .filter { it.capId !in chosen && teamTime(it, runs) != null }
                    .mapTo(HashSet()) { it.capId }
            recordings.retainUnrecorded { it in chosen }
            recordings.updateAll { rebuilt(it, beaten, runs) }
        }

    /**
     * Sets every recording of [recordings] that [filter] accepts to [status], as one transaction.
     */
    private suspend fun setStatus(
        status: RecordingStatus,
        filter: (Recording) -> Boolean,
    ) {
        recordings.updateAll {
            when {
                filter(it) -> it.copy(status = status)
                else -> it
            }
        }
    }

    /**
     * Returns the head of the queue once it may be taken. Waits while the head is a backlog recording and the pace has
     * no free slot. If the queue is empty, or its head is a backlog recording while the backlog buffer is full, returns
     * `null` instead.
     */
    private suspend fun head(): Recording? {
        val all = recordings.all()
        val now = clock.now()
        val head = sorted(all, now).firstOrNull() ?: return null
        val wait = (startsAt(head, all, now) ?: return null) - now
        if (!wait.isPositive()) return head
        delay(wait)
        return head()
    }

    /**
     * Returns the earliest time [head] may be taken at [now], with the recordings of [all]: [now] if it is recent or the
     * pace has a free slot, or the next free slot of the pace. If [head] is a backlog recording while the backlog buffer
     * is full, returns `null` instead.
     */
    private fun startsAt(
        head: Recording,
        all: List<Recording>,
        now: Instant,
    ): Instant? =
        when {
            isRecent(head.demo, now) -> now
            all.count { isBuffered(it, now) } >= config.recordingBacklogBuffer -> null
            else -> maxOf(nextSlot(all, now), now)
        }

    /**
     * Returns the time the pace has its next free slot at [now]. The recordings of [all] started in the day before
     * [now] are laid out one day divided by [ServerConfig.dailyUploads] apart, each no earlier than it started, and the
     * free slot follows the last of them. If none started in that day, returns a time before [now].
     */
    private fun nextSlot(
        all: List<Recording>,
        now: Instant,
    ): Instant {
        val interval = 1.days / config.dailyUploads
        return all
            .mapNotNull(::startedAt)
            .filter { now - it < 1.days }
            .sorted()
            .fold(now - 1.days) { slot, start -> maxOf(slot, start) + interval }
    }

    /**
     * Returns `true` if [recording] fills the backlog buffer at [now]: it is [RecordingStatus.Recording], or [Recorded]
     * at least [ServerConfig.archiveDelay] ago. A [Recorded] recording without [Recording.recordedAt] fills it as well.
     */
    private fun isBuffered(
        recording: Recording,
        now: Instant,
    ): Boolean =
        when (recording.status) {
            RecordingStatus.Recording -> true
            Recorded -> recording.recordedAt?.let { now - it >= config.archiveDelay } ?: true
            else -> false
        }

    /**
     * Returns the [Pending] recordings of [all] in queue order, with the age of each demo measured at [now].
     */
    private fun sorted(
        all: List<Recording>,
        now: Instant,
    ): List<Recording> =
        all
            .filter { it.status == Pending }
            .sortedWith(order(runTimes(all), now))

    /**
     * Returns the queue order, with the age of each demo measured at [now] and the team time of each team recording
     * taken from [runTimes].
     */
    private fun order(
        runTimes: Map<String, Duration>,
        now: Instant,
    ): Comparator<Recording> =
        compareByDescending<Recording> { isRecent(it.demo, now) }
            .thenByDescending { rank(it.runMedal ?: it.demo.medal) }
            .thenBy { runTimes[it.demo.teamRunId] ?: it.demo.capTime }
            .thenByDescending { it.demo.capTime }
            .thenByDescending { it.demo.timestamp }

    /**
     * Returns `true` if [demo] is at most [ServerConfig.recordingRecentAge] old at [now].
     */
    private fun isRecent(
        demo: Demo,
        now: Instant,
    ): Boolean = now - demo.timestamp <= config.recordingRecentAge

    private companion object {
        val GIVEN_UP = setOf(Failed, Unresolvable)

        val MEDALS = 0..6

        const val CASUAL = 0

        /**
         * Returns the [demos] of each [Demo.teamRunId] present among them. A demo without a [Demo.userId] counts.
         */
        fun runs(demos: List<Demo>): Map<String, List<Demo>> =
            demos
                .filter { it.teamRunId != null }
                .groupBy { it.teamRunId!! }

        /**
         * Returns the slowest [Demo.capTime] among the recordings of [all] for each [Demo.teamRunId] present among them.
         */
        fun runTimes(all: List<Recording>): Map<String, Duration> =
            runs(all.map { it.demo })
                .mapValues { (_, run) -> run.maxOf { it.capTime } }

        /**
         * Returns the team time of [demo]: its own [Demo.capTime] if it is solo, the slowest [Demo.capTime] of its run in
         * [runs] if it is a complete team demo, or `null` if it is an incomplete team demo. A team demo is complete if
         * its run in [runs] holds a demo of each of its [Demo.teammates].
         */
        fun teamTime(
            demo: Demo,
            runs: Map<String, List<Demo>>,
        ): Duration? {
            if (demo.teammates.isEmpty()) return demo.capTime
            val run = demo.teamRunId?.let { runs[it] } ?: return null
            val members = run.mapTo(HashSet()) { it.userId }
            return run.maxOf { it.capTime }.takeIf { members.containsAll(demo.teammates) }
        }

        /**
         * Returns the fastest of [demos] for each player with the same teammates on each map, oldest first. The demos
         * are compared by their team time over [runs], then by [Demo.timestamp]. A demo without a [Demo.userId] and an
         * incomplete team demo are never returned.
         */
        fun fastest(
            demos: List<Demo>,
            runs: Map<String, List<Demo>>,
        ): List<Demo> {
            val fastestFirst =
                compareBy<Demo> { teamTime(it, runs) }
                    .thenBy { it.timestamp }
            return demos
                .filter { it.userId != null && teamTime(it, runs) != null }
                .groupBy { Triple(it.userId, it.map, it.teammates) }
                .values
                .map { it.minWith(fastestFirst) }
                .sortedBy { it.timestamp }
        }

        /**
         * Returns [recording] as the server start leaves it, with [maxAttempts] as the limit of
         * [Recording.interruptions]. A [RecordingStatus.Recording] one was interrupted by a server stop: its
         * [Recording.interruptions] goes up by one, and it becomes [Pending] below [maxAttempts], or [Failed] with the
         * count in its [Recording.error] at it. A [Failed] one becomes [Pending] while its [Recording.interruptions] is
         * below [maxAttempts], and an [Unresolvable] one always does. Any other recording is returned unchanged.
         */
        fun requeued(
            recording: Recording,
            maxAttempts: Int,
        ): Recording =
            when (recording.status) {
                RecordingStatus.Recording -> interrupted(recording, maxAttempts)
                Failed if recording.interruptions >= maxAttempts -> recording
                Failed, Unresolvable -> recording.copy(status = Pending)
                else -> recording
            }

        /**
         * Returns [recording], interrupted by a server stop, with its [Recording.interruptions] one higher: [Pending]
         * below [maxAttempts], or [Failed] with the count in its [Recording.error] at it.
         */
        fun interrupted(
            recording: Recording,
            maxAttempts: Int,
        ): Recording {
            val interruptions = recording.interruptions + 1
            if (interruptions < maxAttempts) return recording.copy(status = Pending, interruptions = interruptions)
            return recording.copy(
                status = Failed,
                interruptions = interruptions,
                error = "Server stopped during the recording $interruptions times",
            )
        }

        /**
         * Returns [recording] with its [Recording.runMedal] taken from [runs], set to [Outdated] if its demo is in
         * [beaten] and it is [completed][RecordingStatus.isCompleted].
         */
        fun rebuilt(
            recording: Recording,
            beaten: Set<String>,
            runs: Map<String, List<Demo>>,
        ): Recording {
            val status =
                when {
                    recording.demo.capId in beaten && recording.status.isCompleted -> Outdated
                    else -> recording.status
                }
            return recording.copy(status = status, runMedal = runMedal(recording.demo, runs))
        }

        /**
         * Returns the medal of the run of [demo]: the [Demo.medal] of the fastest demo of its run in [runs] that has a
         * medal if it is a team demo, or its own [Demo.medal] if it is solo or its run is not in [runs]. Of demos with
         * the same [Demo.capTime], the highest ranked medal wins. If no demo of the run has a medal, returns `null`
         * instead.
         */
        fun runMedal(
            demo: Demo,
            runs: Map<String, List<Demo>>,
        ): Int? {
            val run = demo.teamRunId?.let { runs[it] } ?: return demo.medal
            val fastestFirst =
                compareBy<Demo> { it.capTime }
                    .thenByDescending { rank(it.medal) }
            return run
                .filter { it.medal != null }
                .minWithOrNull(fastestFirst)
                ?.medal
        }

        /**
         * Returns the rank of [medal]: [medal] itself if it is one of [MEDALS], [CASUAL] otherwise.
         */
        fun rank(medal: Int?): Int = medal?.takeIf { it in MEDALS } ?: CASUAL

        /**
         * Returns the time [recording] started: its [Recording.lastUpdate], the time it was claimed, if it is
         * [RecordingStatus.Recording], or its [Recording.recordedAt] minus its [Recording.processingTime]. If it was
         * not recorded by the server, returns `null` instead.
         */
        fun startedAt(recording: Recording): Instant? =
            when (recording.status) {
                RecordingStatus.Recording -> recording.lastUpdate
                else -> recording.recordedAt?.let { it - (recording.processingTime ?: Duration.ZERO) }
            }
    }
}

/**
 * Head of a [RecordingQueue], with the earliest time a recorder may take it.
 *
 * @property recording The recording at the head of the queue.
 * @property startsAt The earliest time a recorder may take [recording], or `null` while the backlog buffer holds it back.
 */
data class QueueHead(
    val recording: Recording,
    val startsAt: Instant?,
)
