package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.server.database.CachedCollection
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.database.cachedCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

/**
 * Every [Demo] known to the server, kept in the `demos` collection of a [DocumentStore]. Each demo is keyed by its cap
 * id.
 *
 * A stored demo can be withdrawn: marked as no longer listed by the cap list, while it stays stored. The cap ids of the
 * withdrawn demos are kept in the `withdrawn_demos` collection of the same store.
 *
 * Both collections are kept in memory.
 *
 * @param store The store that holds the `demos` and `withdrawn_demos` collections.
 */
class DemoRepository(
    store: DocumentStore,
) {
    private val collection: CachedCollection<Demo> = store.cachedCollection("demos") { it.capId }
    private val withdrawnCollection: CachedCollection<WithdrawnDemo> =
        store.cachedCollection("withdrawn_demos") { it.capId }

    /**
     * Signal of changes. Emits once when collected, then again after demos are stored and after the withdrawn demos
     * change. Emissions that are not collected in time are merged into one.
     */
    val changes: Flow<Unit> = combine(collection.revision, withdrawnCollection.revision) { _, _ -> }

    /**
     * Returns `true` if a demo is stored under [capId].
     */
    suspend operator fun contains(capId: String): Boolean = capId in collection

    /**
     * Returns every demo, in the order they were first stored.
     */
    suspend fun all(): List<Demo> = collection.all()

    /**
     * Stores each of [demos], in the order given. A demo whose cap id is stored already is skipped. The withdrawn demos
     * do not change.
     */
    suspend operator fun plusAssign(demos: List<Demo>) {
        collection.transaction { stored ->
            demos
                .filter { it.capId !in stored }
                .forEach { stored.put(it) }
        }
    }

    /**
     * Returns the cap ids of the withdrawn demos. Each of them names a stored demo.
     */
    suspend fun withdrawn(): Set<String> =
        withdrawnCollection
            .all()
            .mapTo(LinkedHashSet()) { it.capId }

    /**
     * Withdraws every stored demo whose cap id is not in [seen], and restores every stored demo whose cap id is in
     * [seen], in one transaction. Cap ids of [seen] that name no stored demo are ignored.
     */
    suspend fun withdrawAllExcept(seen: Set<String>) {
        val stored = all()
        withdrawnCollection.transaction { withdrawn ->
            stored
                .map { it.capId }
                .forEach { capId ->
                    when {
                        capId in seen && capId in withdrawn -> withdrawn.remove(capId)
                        capId !in seen && capId !in withdrawn -> withdrawn.put(WithdrawnDemo(capId))
                    }
                }
        }
    }

    /**
     * Restores each of [capIds], so that it is no longer withdrawn. Cap ids that are not withdrawn are ignored.
     */
    suspend fun restore(capIds: Collection<String>) {
        withdrawnCollection.transaction { withdrawn ->
            capIds
                .filter { it in withdrawn }
                .forEach { withdrawn.remove(it) }
        }
    }

    /**
     * Mark of a withdrawn demo, as the `withdrawn_demos` collection stores it.
     */
    @Serializable
    private data class WithdrawnDemo(
        val capId: String,
    )
}
