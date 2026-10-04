package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.database.CachedCollection
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.DocumentTransaction
import dev.klepto.utrecorder.server.database.cachedCollection
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Every [Recording] known to the server, kept in the `recordings` collection of a [DocumentStore]. Each recording is keyed
 * by the cap id of its demo.
 *
 * A recording is stored with the cap id of its demo, not the demo itself. Its [Recording.demo] is read from [demos],
 * which every added demo is stored in as well.
 *
 * The collection is kept in memory.
 *
 * Every change of the status of a recording sets its [Recording.lastUpdate] to the time [clock] reports. [recorded]
 * sets [Recording.recordedAt] to that time as well.
 *
 * @param store The store that holds the `recordings` collection.
 * @property demos The demos of the recordings.
 * @property clock The source of [Recording.lastUpdate] and of [Recording.recordedAt].
 */
class RecordingRepository(
    store: DocumentStore,
    private val demos: DemoRepository,
    private val clock: Clock = Clock.System,
) {
    private val collection: CachedCollection<StoredRecording> = store.cachedCollection("recordings") { it.capId }
    private val demosByCapId = ConcurrentHashMap<String, Demo>()
    private val statusRevision = MutableStateFlow(0L)

    /**
     * Signal of status changes. Emits once when collected, then again after every transaction that adds, removes or
     * changes the status of a recording. Emissions that are not collected in time are merged into one.
     */
    val statusChanges: Flow<Unit> = statusRevision.map { }

    /**
     * Returns the recording stored under [capId]. If there is none, returns `null` instead.
     */
    suspend operator fun get(capId: String): Recording? = transaction { it[capId] }

    /**
     * Returns every recording, in the order they were first stored.
     */
    suspend fun all(): List<Recording> = transaction { it.all() }

    /**
     * Stores a [Pending] recording of each of [demos], in the order given, and stores each demo in [DemoRepository]. A
     * demo whose cap id is stored already is skipped.
     */
    suspend operator fun plusAssign(demos: List<Demo>) {
        this.demos += demos
        demos.associateByTo(demoIndex()) { it.capId }
        transaction { recordings ->
            demos
                .filter { it.capId !in recordings }
                .forEach { put(recordings, Recording(it)) }
        }
    }

    /**
     * Removes every recording that has not been recorded and whose cap id [keep] does not accept. A recording has not
     * been recorded if it is [Pending], [Failed] or [Unresolvable], without a [Recording.recordedAt].
     */
    suspend fun retainUnrecorded(keep: (String) -> Boolean) {
        transaction { recordings ->
            recordings
                .all()
                .filter { it.isUnrecorded() && !keep(it.demo.capId) }
                .forEach { recordings.remove(it.demo.capId) }
        }
    }

    /**
     * If the recording stored under [capId] is [Pending], sets it to [RecordingStatus.Recording] as one transaction and
     * returns `true`. If there is none, or it is not [Pending], changes nothing and returns `false` instead.
     */
    suspend fun claim(capId: String): Boolean =
        transaction { recordings ->
            val recording = recordings[capId]?.takeIf { it.status == Pending } ?: return@transaction false
            put(recordings, recording.copy(status = RecordingStatus.Recording))
            true
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
     * Sets the recording stored under [capId] to [Recorded], recorded now in [processingTime]. If there is none, does
     * nothing.
     */
    suspend fun recorded(
        capId: String,
        processingTime: Duration,
    ) {
        update(capId) { it.copy(status = Recorded, recordedAt = clock.now(), processingTime = processingTime) }
    }

    /**
     * Replaces the recording stored under [capId] with the result of [transform]. If there is none, does nothing.
     */
    suspend fun update(
        capId: String,
        transform: (Recording) -> Recording,
    ) {
        transaction { recordings ->
            val recording = recordings[capId] ?: return@transaction
            put(recordings, transform(recording))
        }
    }

    /**
     * Replaces every recording with the result of [transform], as one transaction. A recording that [transform] returns
     * unchanged is not written.
     */
    suspend fun updateAll(transform: (Recording) -> Recording) {
        transaction { recordings ->
            recordings
                .all()
                .map { it to transform(it) }
                .filter { (recording, transformed) -> recording != transformed }
                .forEach { (_, transformed) -> put(recordings, transformed) }
        }
    }

    /**
     * Returns `true` if this recording is [Pending], [Failed] or [Unresolvable], without a [Recording.recordedAt].
     */
    private fun Recording.isUnrecorded(): Boolean = status in UNRECORDED && recordedAt == null

    /**
     * Returns [recording] after storing it in [recordings]. If its status differs from the one stored, or none is stored,
     * sets its [Recording.lastUpdate] to now first.
     */
    private suspend fun put(
        recordings: DocumentTransaction<Recording>,
        recording: Recording,
    ): Recording = stamp(recordings[recording.demo.capId], recording).also { recordings.put(it) }

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
     * Returns the result of [block], run as one transaction on the collection. Raises [statusRevision] once the
     * transaction is done, if it added, removed or changed the status of a recording.
     */
    private suspend fun <R> transaction(block: suspend (DocumentTransaction<Recording>) -> R): R {
        val index = demoIndex()
        val (result, changed) =
            collection.transaction { stored ->
                RecordingTransaction(stored, index).let { block(it) to it.statusChanged }
            }
        if (changed) statusRevision.update { it + 1 }
        return result
    }

    /**
     * Returns every demo of [demos], keyed by cap id. Reads [demos] only while no demo is known, since a demo never
     * changes and every demo added later is added to the index by [plusAssign].
     */
    private suspend fun demoIndex(): MutableMap<String, Demo> {
        if (demosByCapId.isEmpty()) demos.all().associateByTo(demosByCapId) { it.capId }
        return demosByCapId
    }

    /**
     * [Recording] as the `recordings` collection stores it, with the cap id of its demo in place of the demo.
     */
    @Serializable
    private data class StoredRecording(
        val capId: String,
        val status: RecordingStatus = Pending,
        val error: String? = null,
        val md5: String? = null,
        val lastUpdate: Instant = Instant.DISTANT_PAST,
        val views: Int = 0,
        val lastView: Instant? = null,
        val recordedAt: Instant? = null,
        val processingTime: Duration? = null,
        val itemId: String? = null,
    )

    /**
     * [DocumentTransaction] of recordings over a transaction of [StoredRecording] documents, with the demo of each
     * recording read from [demos].
     */
    private class RecordingTransaction(
        private val stored: DocumentTransaction<StoredRecording>,
        private val demos: Map<String, Demo>,
    ) : DocumentTransaction<Recording> {
        /**
         * `true` once a recording is added, removed or put with another status.
         */
        var statusChanged = false
            private set

        override suspend fun get(key: String): Recording? = stored[key]?.let { toRecording(it) }

        override suspend fun contains(key: String): Boolean = key in stored

        override suspend fun put(document: Recording) {
            if (stored[document.demo.capId]?.status != document.status) statusChanged = true
            stored.put(toStored(document))
        }

        override suspend fun remove(key: String) {
            stored.remove(key)
            statusChanged = true
        }

        override suspend fun all(): List<Recording> = stored.all().map { toRecording(it) }

        /**
         * Returns [recording] with its demo.
         *
         * @throws IllegalStateException If no demo is stored under the cap id of [recording].
         */
        private fun toRecording(recording: StoredRecording): Recording =
            Recording(
                demo = checkNotNull(demos[recording.capId]) { "No demo is stored for recording ${recording.capId}" },
                status = recording.status,
                error = recording.error,
                md5 = recording.md5,
                lastUpdate = recording.lastUpdate,
                views = recording.views,
                lastView = recording.lastView,
                recordedAt = recording.recordedAt,
                processingTime = recording.processingTime,
                itemId = recording.itemId,
            )

        /**
         * Returns [recording] with the cap id of its demo in place of the demo.
         */
        private fun toStored(recording: Recording): StoredRecording =
            StoredRecording(
                capId = recording.demo.capId,
                status = recording.status,
                error = recording.error,
                md5 = recording.md5,
                lastUpdate = recording.lastUpdate,
                views = recording.views,
                lastView = recording.lastView,
                recordedAt = recording.recordedAt,
                processingTime = recording.processingTime,
                itemId = recording.itemId,
            )
    }

    private companion object {
        val UNRECORDED = setOf(Pending, Failed, Unresolvable)
    }
}
