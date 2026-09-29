package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.database.DocumentCollection
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.collection
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Every [Recording] known to the server, kept in the `recordings` collection of a [DocumentStore], and the queue of the
 * ones waiting for a recorder. Each recording is keyed by the cap id of its demo.
 *
 * The collection is read once, on the first call. At that read, every [RecordingStatus.Recording] or [Failed]
 * recording is set to [Pending] again.
 *
 * @param store The store that holds the `recordings` collection.
 */
class RecordingQueue(
    store: DocumentStore,
) {
    private val collection: DocumentCollection<Recording> = store.collection("recordings") { it.demo.capId }
    private val lock = Mutex()
    private val additions = MutableStateFlow(0L)
    private var recordings: MutableMap<String, Recording>? = null

    /**
     * Returns the recording stored under [capId]. If there is none, returns `null` instead.
     */
    suspend operator fun get(capId: String): Recording? = locked { it[capId] }

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
        additions.update { it + 1 }
    }

    /**
     * Returns the demo of the newest [Pending] recording that [accepts] returns `true` for, and sets the recording to
     * [RecordingStatus.Recording]. If there is none, suspends until demos are added and one of them is accepted.
     */
    suspend fun take(accepts: (Demo) -> Boolean): Demo {
        while (true) {
            val seen = additions.value
            claim(accepts)?.let { return it }
            additions.first { it != seen }
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
        locked { recordings ->
            val recording = recordings[capId] ?: return@locked
            put(recordings, recording.copy(status = status, error = error ?: recording.error))
        }
    }

    /**
     * Returns the demo of the newest [Pending] recording that [accepts] returns `true` for, and sets the recording to
     * [RecordingStatus.Recording]. If there is none, returns `null` instead.
     */
    private suspend fun claim(accepts: (Demo) -> Boolean): Demo? =
        locked { recordings ->
            recordings.values
                .filter { it.status == Pending && accepts(it.demo) }
                .maxByOrNull { it.demo.timestamp }
                ?.let { put(recordings, it.copy(status = RecordingStatus.Recording)).demo }
        }

    /**
     * Returns [recording] after storing it in the collection and in [recordings].
     */
    private suspend fun put(
        recordings: MutableMap<String, Recording>,
        recording: Recording,
    ): Recording =
        recording.also {
            collection.put(it)
            recordings[it.demo.capId] = it
        }

    /**
     * Returns the result of [block], run under the lock with every stored recording. Reads the collection on the first call.
     */
    private suspend fun <R> locked(block: suspend (MutableMap<String, Recording>) -> R): R =
        lock.withLock { block(recordings ?: load().also { recordings = it }) }

    /**
     * Returns every recording of the collection, keyed by cap id, after setting each [RecordingStatus.Recording] or
     * [Failed] one to [Pending].
     */
    private suspend fun load(): MutableMap<String, Recording> =
        collection
            .all()
            .map { requeue(it) }
            .associateByTo(mutableMapOf()) { it.demo.capId }

    /**
     * Returns [recording] set to [Pending] and stored, if it is [RecordingStatus.Recording] or [Failed]. Returns
     * [recording] unchanged otherwise.
     */
    private suspend fun requeue(recording: Recording): Recording =
        when (recording.status) {
            RecordingStatus.Recording, Failed -> recording.copy(status = Pending).also { collection.put(it) }
            else -> recording
        }
}
