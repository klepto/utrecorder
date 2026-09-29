package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.database.DocumentCollection
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.collection
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every [Recording] known to the server, kept in the `recordings` collection of a [DocumentStore], and the queue of the
 * ones waiting for a recorder. Each recording is keyed by the cap id of its demo.
 *
 * The collection is read once, on the first call. At that read, every [RecordingStatus.Recording], [Failed] or
 * [Unresolvable] recording is set to [Pending] again.
 *
 * A demo added within [RecordingConfig.recentAge] of now is recent, and is taken whenever it is the newest pending one.
 * An older demo is a backlog demo. It is taken only while fewer than [RecordingConfig.backlogBuffer] recordings are
 * [RecordingStatus.Recording] or [Recorded].
 *
 * Every change of the status of a recording sets its [Recording.lastUpdate] to the time [clock] reports.
 *
 * @param store The store that holds the `recordings` collection.
 * @property config The recent age and the backlog buffer.
 * @property clock The source of [Recording.lastUpdate] and of the age of a demo.
 */
class RecordingRepository(
    store: DocumentStore,
    private val config: RecordingConfig = RecordingConfig(),
    private val clock: Clock = Clock.System,
) {
    private val collection: DocumentCollection<Recording> = store.collection("recordings") { it.demo.capId }
    private val lock = Mutex()
    private val changes = MutableStateFlow(0L)
    private var recordings: MutableMap<String, Recording>? = null

    /**
     * Returns the recording stored under [capId]. If there is none, returns `null` instead.
     */
    suspend operator fun get(capId: String): Recording? = locked { it[capId] }

    /**
     * Returns every recording, in the order they were first stored.
     */
    suspend fun all(): List<Recording> = locked { it.values.toList() }

    /**
     * Returns `true` if a recording is stored under [capId].
     */
    suspend operator fun contains(capId: String): Boolean = locked { capId in it }

    /**
     * Returns the time the newest demo was added. If there is no demo, returns `null` instead.
     */
    suspend fun newest(): Instant? = locked { recordings -> recordings.values.maxOfOrNull { it.demo.timestamp } }

    /**
     * Returns the time the oldest demo was added. If there is no demo, returns `null` instead.
     */
    suspend fun oldest(): Instant? = locked { recordings -> recordings.values.minOfOrNull { it.demo.timestamp } }

    /**
     * Stores a [Pending] recording of each of [demos], in the order given. A demo whose cap id is stored already is
     * skipped.
     */
    suspend operator fun plusAssign(demos: List<Demo>) {
        locked { recordings ->
            demos
                .filter { it.capId !in recordings }
                .forEach { put(recordings, Recording(it)) }
        }
    }

    /**
     * Returns the demo of the newest [Pending] recording that may be taken, and sets the recording to
     * [RecordingStatus.Recording]. If there is none, suspends until a recording changes and one may be taken.
     */
    suspend fun take(): Demo {
        while (true) {
            val seen = changes.value
            claim()?.let { return it }
            changes.first { it != seen }
        }
    }

    /**
     * Sets the recording stored under [capId] to [status], with [error] as its failure. If there is none, does nothing.
     */
    suspend fun finish(
        capId: String,
        status: RecordingStatus,
        error: String? = null,
    ) {
        update(capId) { it.copy(status = status, error = error ?: it.error) }
    }

    /**
     * Replaces the recording stored under [capId] with the result of [transform]. If there is none, does nothing.
     */
    suspend fun update(
        capId: String,
        transform: (Recording) -> Recording,
    ) {
        locked { recordings ->
            val recording = recordings[capId] ?: return@locked
            put(recordings, transform(recording))
        }
    }

    /**
     * Returns the demo of the newest [Pending] recording that may be taken, and sets the recording to
     * [RecordingStatus.Recording]. If there is none, returns `null` instead.
     */
    private suspend fun claim(): Demo? =
        locked { recordings ->
            val backlogOpen = recordings.values.count { it.isBuffered() } < config.backlogBuffer
            recordings.values
                .filter { it.status == Pending && (backlogOpen || isRecent(it.demo)) }
                .maxByOrNull { it.demo.timestamp }
                ?.let { put(recordings, it.copy(status = RecordingStatus.Recording)).demo }
        }

    /**
     * Returns `true` if this recording is [RecordingStatus.Recording] or [Recorded].
     */
    private fun Recording.isBuffered(): Boolean = status == RecordingStatus.Recording || status == Recorded

    /**
     * Returns `true` if [demo] was added within [RecordingConfig.recentAge] of now.
     */
    private fun isRecent(demo: Demo): Boolean = clock.now() - demo.timestamp <= config.recentAge

    /**
     * Returns [recording] after storing it in the collection and in [recordings], and wakes every waiting [take]. If
     * its status differs from the one stored, or none is stored, sets its [Recording.lastUpdate] to now first.
     */
    private suspend fun put(
        recordings: MutableMap<String, Recording>,
        recording: Recording,
    ): Recording =
        stamp(recordings[recording.demo.capId], recording).also {
            collection.put(it)
            recordings[it.demo.capId] = it
            changes.update { count -> count + 1 }
        }

    /**
     * Returns [recording] with its [Recording.lastUpdate] set to now, if its status differs from the status of
     * [previous] or [previous] is `null`. Returns [recording] unchanged otherwise.
     */
    private fun stamp(
        previous: Recording?,
        recording: Recording,
    ): Recording =
        when (previous?.status) {
            recording.status -> recording
            else -> recording.copy(lastUpdate = clock.now())
        }

    /**
     * Returns the result of [block], run under the lock with every stored recording. Reads the collection on the first call.
     */
    private suspend fun <R> locked(block: suspend (MutableMap<String, Recording>) -> R): R =
        lock.withLock { block(recordings ?: load().also { recordings = it }) }

    /**
     * Returns every recording of the collection, keyed by cap id, after setting each [RecordingStatus.Recording],
     * [Failed] or [Unresolvable] one to [Pending].
     */
    private suspend fun load(): MutableMap<String, Recording> =
        collection
            .all()
            .map { requeue(it) }
            .associateByTo(mutableMapOf()) { it.demo.capId }

    /**
     * Returns [recording] set to [Pending] and stored, if it is [RecordingStatus.Recording], [Failed] or
     * [Unresolvable]. Returns [recording] unchanged otherwise.
     */
    private suspend fun requeue(recording: Recording): Recording =
        when (recording.status) {
            RecordingStatus.Recording, Failed, Unresolvable -> {
                stamp(
                    recording,
                    recording.copy(status = Pending),
                ).also { collection.put(it) }
            }

            else -> {
                recording
            }
        }
}
