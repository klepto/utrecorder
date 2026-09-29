package dev.klepto.utrecorder.server.database

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * JSON documents grouped in named collections.
 *
 * Open a store with [SqliteDocumentStore.open].
 */
interface DocumentStore {
    /**
     * Returns the collection [name] of this store. Each document is stored under the key that [key] returns for it.
     * If [cached] is `true`, the collection keeps every document in memory after its first transaction.
     *
     * @throws IllegalArgumentException If [name] is not a valid collection name of this store.
     */
    fun <T : Any> collection(
        name: String,
        serializer: KSerializer<T>,
        cached: Boolean = false,
        key: (T) -> String,
    ): DocumentCollection<T>
}

/**
 * Returns the collection [name] of this store, serialized with the serializer of [T].
 */
inline fun <reified T : Any> DocumentStore.collection(
    name: String,
    cached: Boolean = false,
    noinline key: (T) -> String,
): DocumentCollection<T> = collection(name, serializer<T>(), cached, key)
