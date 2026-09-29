package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * JSON documents of one table of a [DocumentStore], each stored under a string key.
 *
 * Get a collection through [DocumentStore.collection], which passes the connection and the lock of the store.
 *
 * @property connection The connection of the store.
 * @property lock The lock that serializes every call on [connection].
 * @property name The table name.
 * @property serializer The serializer of a document.
 * @property key Returns the key of a document.
 */
class DocumentCollection<T>(
    private val connection: Connection,
    private val lock: Mutex,
    private val name: String,
    private val serializer: KSerializer<T>,
    private val key: (T) -> String,
) {
    /**
     * Returns the document stored under [key]. If there is none, returns `null` instead.
     */
    suspend operator fun get(key: String): T? =
        execute {
            connection.prepareStatement("SELECT document FROM $name WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { result ->
                    if (result.next()) JSON.decodeFromString(serializer, result.getString(1)) else null
                }
            }
        }

    /**
     * Stores [document] under its key, replacing the document stored there before.
     */
    suspend fun put(document: T) {
        execute {
            connection
                .prepareStatement(
                    "INSERT INTO $name (key, document) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET document = excluded.document",
                ).use { statement ->
                    statement.setString(1, key(document))
                    statement.setString(2, JSON.encodeToString(serializer, document))
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Removes the document stored under [key], if there is one.
     */
    suspend fun remove(key: String) {
        execute {
            connection.prepareStatement("DELETE FROM $name WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Returns every document, in the order their keys were first stored.
     */
    suspend fun all(): List<T> =
        execute {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT document FROM $name ORDER BY rowid").use { result ->
                    generateSequence { if (result.next()) result.getString(1) else null }
                        .map { JSON.decodeFromString(serializer, it) }
                        .toList()
                }
            }
        }

    private suspend fun <R> execute(block: () -> R): R =
        lock.withLock {
            withContext(Dispatchers.IO) {
                connection.createStatement().use {
                    it.execute("CREATE TABLE IF NOT EXISTS $name (key TEXT PRIMARY KEY, document TEXT NOT NULL)")
                }
                block()
            }
        }

    companion object {
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
