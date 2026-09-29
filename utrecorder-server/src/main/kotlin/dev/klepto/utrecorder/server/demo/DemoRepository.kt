package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.server.database.CachedCollection
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.cachedCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * Every [Demo] known to the server, kept in the `demos` collection of a [DocumentStore]. Each demo is keyed by its cap
 * id.
 *
 * The collection is kept in memory.
 *
 * @param store The store that holds the `demos` collection.
 */
class DemoRepository(
    store: DocumentStore,
) {
    private val collection: CachedCollection<Demo> = store.cachedCollection("demos") { it.capId }

    /**
     * Signal of new demos. Emits once when collected, then again after demos are stored. Emissions that are not
     * collected in time are merged into one.
     */
    val changes: Flow<Unit> = collection.revision.map { }

    /**
     * Returns `true` if a demo is stored under [capId].
     */
    suspend operator fun contains(capId: String): Boolean = capId in collection

    /**
     * Returns every demo, in the order they were first stored.
     */
    suspend fun all(): List<Demo> = collection.all()

    /**
     * Returns the time the newest demo was added. If there is no demo, returns `null` instead.
     */
    suspend fun newest(): Instant? = all().maxOfOrNull { it.timestamp }

    /**
     * Returns the time the oldest demo was added. If there is no demo, returns `null` instead.
     */
    suspend fun oldest(): Instant? = all().minOfOrNull { it.timestamp }

    /**
     * Stores each of [demos], in the order given. A demo whose cap id is stored already is skipped.
     */
    suspend operator fun plusAssign(demos: List<Demo>) {
        collection.transaction { stored ->
            demos
                .filter { it.capId !in stored }
                .forEach { stored.put(it) }
        }
    }
}
