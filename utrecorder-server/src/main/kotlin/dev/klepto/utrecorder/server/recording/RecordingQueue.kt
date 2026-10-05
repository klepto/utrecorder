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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * [Service] that keeps a [Pending] recording in [recordings] for each demo of [demos] that waits for a recorder, and
 * orders those recordings behind a backlog gate.
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
 * On [start], every [RecordingStatus.Recording], [Failed] or [Unresolvable] recording is set to [Pending] again, then the
 * queue is built. After that, the queue is built again whenever [DemoRepository.changes] or
 * [RecordingRepository.statusChanges] signals a change, and every [Failed] or [Unresolvable] recording is set to
 * [Pending] again once every [ServerConfig.recordingRetryInterval]. A build that changes nothing changes no status.
 *
 * Every build sets the [Recording.runMedal] of each recording: the highest ranked [Demo.medal] among the stored demos of
 * its [Demo.teamRunId], or its own [Demo.medal] for a solo demo.
 *
 * The recordings are ordered key by key: recent first, then higher [Recording.runMedal], then shorter [Demo.capTime],
 * then newer [Demo.timestamp]. Ties keep the order [RecordingRepository.all] gives. A demo is recent while it is at most
 * [ServerConfig.recordingRecentAge] old, a demo whose timestamp lies in the future included; any other demo is a backlog
 * demo. A recording whose [Recording.runMedal] is not set yet ranks by its [Demo.medal]. A medal that is `null` or
 * outside `0` to `6` ranks as `0`, casual.
 *
 * Every [Pending] recording is sorted again on each call. Neither [get] nor [first] changes a status, and both wait until
 * [start] has built the queue once.
 *
 * @property demos The demos to choose from.
 * @property recordings The recordings of the chosen demos.
 * @property config The retry interval, the recent age, the backlog buffer and the archive delay.
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
     * or while the head is a backlog recording and at least [ServerConfig.recordingBacklogBuffer] recordings are
     * [RecordingStatus.Recording], or [Recorded] at least [ServerConfig.archiveDelay] ago, until
     * [RecordingRepository.statusChanges] signals a change after which the head may be taken. A [Recorded] recording
     * without [Recording.recordedAt] counts as recorded long enough ago.
     */
    suspend fun first(): Recording {
        built.await()
        return recordings.statusChanges
            .mapNotNull { head() }
            .first()
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
     * Sets every [RecordingStatus.Recording], [Failed] or [Unresolvable] recording of [recordings] to [Pending].
     */
    private suspend fun requeue() {
        setStatus(Pending) { it.status in REQUEUED }
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
     * Returns the head of the queue, if it may be taken. If the queue is empty, or its head is a backlog recording while
     * the backlog buffer is full, returns `null` instead.
     */
    private suspend fun head(): Recording? {
        val all = recordings.all()
        val now = clock.now()
        val buffered = all.count { isBuffered(it, now) }
        return sorted(all, now)
            .firstOrNull()
            ?.takeIf { isRecent(it.demo, now) || buffered < config.recordingBacklogBuffer }
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
            .sortedWith(order(now))

    /**
     * Returns the queue order, with the age of each demo measured at [now].
     */
    private fun order(now: Instant): Comparator<Recording> =
        compareByDescending<Recording> { isRecent(it.demo, now) }
            .thenByDescending { rank(it.runMedal ?: it.demo.medal) }
            .thenBy { it.demo.capTime }
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

        val REQUEUED = GIVEN_UP + RecordingStatus.Recording

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
         * Returns the medal of the run of [demo]: the highest ranked [Demo.medal] of its run in [runs] if it is a team
         * demo, or its own [Demo.medal] if it is solo or its run is not in [runs]. If no demo of the run has a medal,
         * returns `null` instead.
         */
        fun runMedal(
            demo: Demo,
            runs: Map<String, List<Demo>>,
        ): Int? {
            val run = demo.teamRunId?.let { runs[it] } ?: return demo.medal
            return run
                .mapNotNull { it.medal }
                .maxByOrNull { rank(it) }
        }

        /**
         * Returns the rank of [medal]: [medal] itself if it is one of [MEDALS], [CASUAL] otherwise.
         */
        fun rank(medal: Int?): Int = medal?.takeIf { it in MEDALS } ?: CASUAL
    }
}
