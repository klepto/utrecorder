package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DocumentCollection] that keeps every document of another collection in memory and writes each change through to it.
 *
 * The documents of [collection] are read on the first transaction, and every read after that is answered from memory.
 * If a transaction throws, the documents are read again on the next one.
 *
 * Get a collection through [DocumentStore.cachedCollection].
 *
 * @property collection The collection that stores the documents.
 * @property key Returns the key of a document.
 */
class CachedCollection<T : Any>(
    private val collection: DocumentCollection<T>,
    private val key: (T) -> String,
) : DocumentCollection<T> {
    private val lock = Mutex()
    private var documents: MutableMap<String, T>? = null
    private val changes = MutableStateFlow(0L)

    /**
     * Number of transactions that put or removed a document and did not throw. Starts at `0`.
     */
    val revision: StateFlow<Long> = changes.asStateFlow()

    override suspend fun <R> transaction(block: suspend (DocumentTransaction<T>) -> R): R =
        lock.withLock {
            try {
                val (result, changed) = collection.transaction { stored -> run(stored, block) }
                if (changed) changes.update { it + 1 }
                result
            } catch (e: Throwable) {
                documents = null
                throw e
            }
        }

    /**
     * Returns the result of [block], run on the documents of [stored], and `true` if [block] put or removed a document.
     */
    private suspend fun <R> run(
        stored: DocumentTransaction<T>,
        block: suspend (DocumentTransaction<T>) -> R,
    ): Pair<R, Boolean> =
        Transaction(stored, documents ?: load(stored)).let { transaction ->
            block(transaction) to transaction.changed
        }

    /**
     * Returns every document of [stored], after keeping them in memory.
     */
    private suspend fun load(stored: DocumentTransaction<T>): MutableMap<String, T> =
        stored
            .all()
            .associateByTo(LinkedHashMap(), key)
            .also { documents = it }

    private inner class Transaction(
        private val stored: DocumentTransaction<T>,
        private val documents: MutableMap<String, T>,
    ) : DocumentTransaction<T> {
        /**
         * `true` once a document is put or removed.
         */
        var changed = false
            private set

        override suspend fun get(key: String): T? = documents[key]

        override suspend fun contains(key: String): Boolean = key in documents

        override suspend fun put(document: T) {
            stored.put(document)
            documents[key(document)] = document
            changed = true
        }

        override suspend fun remove(key: String) {
            stored.remove(key)
            documents.remove(key)
            changed = true
        }

        override suspend fun all(): List<T> = documents.values.toList()
    }
}
