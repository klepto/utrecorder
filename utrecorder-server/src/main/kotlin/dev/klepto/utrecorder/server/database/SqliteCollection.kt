package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * [DocumentCollection] backed by one SQLite table, read and written on every call.
 *
 * Get a collection through [SqliteDocumentStore.collection], which passes the connection and the lock of the store.
 *
 * @property connection The connection of the store. Its auto-commit is off.
 * @property lock The lock that serializes every call on [connection].
 * @property name The table name.
 * @property serializer The serializer of a document.
 * @property key Returns the key of a document.
 */
class SqliteCollection<T : Any>(
    private val connection: Connection,
    private val lock: Mutex,
    private val name: String,
    private val serializer: KSerializer<T>,
    private val key: (T) -> String,
) : DocumentCollection<T> {
    override suspend fun <R> transaction(block: suspend (DocumentTransaction<T>) -> R): R =
        lock.withLock {
            try {
                execute { createTable() }
                block(Transaction()).also { execute { connection.commit() } }
            } catch (e: Throwable) {
                withContext(NonCancellable) { execute { connection.rollback() } }
                throw e
            }
        }

    private fun createTable() {
        connection.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS $name (key TEXT PRIMARY KEY, document TEXT NOT NULL)")
        }
    }

    private suspend fun <R> execute(block: () -> R): R = withContext(Dispatchers.IO) { block() }

    private inner class Transaction : DocumentTransaction<T> {
        override suspend fun get(key: String): T? =
            execute {
                connection.prepareStatement("SELECT document FROM $name WHERE key = ?").use { statement ->
                    statement.setString(1, key)
                    statement.executeQuery().use { result ->
                        if (result.next()) JSON.decodeFromString(serializer, result.getString(1)) else null
                    }
                }
            }

        override suspend fun put(document: T) {
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

        override suspend fun remove(key: String) {
            execute {
                connection.prepareStatement("DELETE FROM $name WHERE key = ?").use { statement ->
                    statement.setString(1, key)
                    statement.executeUpdate()
                }
            }
        }

        override suspend fun all(): List<T> =
            execute {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT document FROM $name ORDER BY rowid").use { result ->
                        generateSequence { if (result.next()) result.getString(1) else null }
                            .map { JSON.decodeFromString(serializer, it) }
                            .toList()
                    }
                }
            }
    }

    private companion object {
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
