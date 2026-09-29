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
     *
     * @throws IllegalArgumentException If [name] is not a valid collection name of this store.
     */
    fun <T : Any> collection(
        name: String,
        serializer: KSerializer<T>,
        key: (T) -> String,
    ): DocumentCollection<T>
}

/**
 * Returns the collection [name] of this store, serialized with the serializer of [T].
 */
inline fun <reified T : Any> DocumentStore.collection(
    name: String,
    noinline key: (T) -> String,
): DocumentCollection<T> = collection(name, serializer<T>(), key)

/**
 * Returns the collection [name] of this store, kept in memory after its first transaction.
 *
 * @throws IllegalArgumentException If [name] is not a valid collection name of this store.
 */
fun <T : Any> DocumentStore.cachedCollection(
    name: String,
    serializer: KSerializer<T>,
    key: (T) -> String,
): CachedCollection<T> = CachedCollection(collection(name, serializer, key), key)

/**
 * Returns the collection [name] of this store, serialized with the serializer of [T] and kept in memory after its
 * first transaction.
 */
inline fun <reified T : Any> DocumentStore.cachedCollection(
    name: String,
    noinline key: (T) -> String,
): CachedCollection<T> = cachedCollection(name, serializer<T>(), key)
