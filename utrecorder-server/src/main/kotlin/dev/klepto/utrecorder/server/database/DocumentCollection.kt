package dev.klepto.utrecorder.server.database

/**
 * JSON documents of one table of a [DocumentStore], each stored under a string key.
 *
 * Get a collection through [DocumentStore.collection]. Every call runs as one [transaction].
 */
interface DocumentCollection<T : Any> {
    /**
     * Returns the result of [block], run as one transaction on this collection. If [block] throws, none of its changes
     * are kept.
     *
     * No other transaction on the same store runs until [block] ends, so [block] must not open one.
     */
    suspend fun <R> transaction(block: suspend (DocumentTransaction<T>) -> R): R

    /**
     * Returns the document stored under [key]. If there is none, returns `null` instead.
     */
    suspend operator fun get(key: String): T? = transaction { it[key] }

    /**
     * Returns `true` if a document is stored under [key].
     */
    suspend operator fun contains(key: String): Boolean = transaction { key in it }

    /**
     * Stores [document] under its key, replacing the document stored there before.
     */
    suspend fun put(document: T) {
        transaction { it.put(document) }
    }

    /**
     * Removes the document stored under [key], if there is one.
     */
    suspend fun remove(key: String) {
        transaction { it.remove(key) }
    }

    /**
     * Returns every document, in the order their keys were first stored.
     */
    suspend fun all(): List<T> = transaction { it.all() }
}

/**
 * Documents of a [DocumentCollection] as seen inside one [DocumentCollection.transaction].
 */
interface DocumentTransaction<T : Any> {
    /**
     * Returns the document stored under [key]. If there is none, returns `null` instead.
     */
    suspend operator fun get(key: String): T?

    /**
     * Returns `true` if a document is stored under [key].
     */
    suspend operator fun contains(key: String): Boolean = get(key) != null

    /**
     * Stores [document] under its key, replacing the document stored there before.
     */
    suspend fun put(document: T)

    /**
     * Removes the document stored under [key], if there is one.
     */
    suspend fun remove(key: String)

    /**
     * Returns every document, in the order their keys were first stored.
     */
    suspend fun all(): List<T>
}
