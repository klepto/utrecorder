package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DocumentCollection] that keeps every document of another collection in memory and writes each change through to it.
 *
 * The documents of [collection] are read on the first transaction, and every read after that is answered from memory.
 * If a transaction throws, the documents are read again on the next one.
 *
 * Get a collection through [DocumentStore.collection].
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

    override suspend fun <R> transaction(block: suspend (DocumentTransaction<T>) -> R): R =
        lock.withLock {
            try {
                collection.transaction { stored -> block(Transaction(stored, documents ?: load(stored))) }
            } catch (e: Throwable) {
                documents = null
                throw e
            }
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
        override suspend fun get(key: String): T? = documents[key]

        override suspend fun contains(key: String): Boolean = key in documents

        override suspend fun put(document: T) {
            stored.put(document)
            documents[key(document)] = document
        }

        override suspend fun remove(key: String) {
            stored.remove(key)
            documents.remove(key)
        }

        override suspend fun all(): List<T> = documents.values.toList()
    }
}
