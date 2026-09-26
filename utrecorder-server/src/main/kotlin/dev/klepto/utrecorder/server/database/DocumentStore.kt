package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.io.path.createDirectories

/**
 * SQLite file of JSON documents, each stored under a key in a named collection.
 *
 * Use [open] to get one, and [close] it when done. Get a collection through [collection].
 */
class DocumentStore private constructor(
    private val connection: Connection,
) {
    private val mutex = Mutex()

    /**
     * Returns the collection [name] of documents written with [serializer], created if the file does not hold it yet.
     *
     * @param keyOf Returns the key a document is stored under.
     * @throws IllegalArgumentException If [name] holds a character other than a lowercase letter, a digit or `_`.
     */
    suspend fun <T> collection(
        name: String,
        serializer: KSerializer<T>,
        keyOf: (T) -> String,
    ): Collection<T> {
        require(COLLECTION_NAME.matches(name)) { "Invalid collection name $name" }
        use { update("CREATE TABLE IF NOT EXISTS $name (key TEXT PRIMARY KEY, document TEXT NOT NULL)") }
        return Collection(name, serializer, keyOf)
    }

    /**
     * Closes the database connection.
     */
    suspend fun close() {
        use { close() }
    }

    private suspend fun <T> use(block: Connection.() -> T): T = mutex.withLock { withContext(Dispatchers.IO) { connection.block() } }

    /**
     * Named collection of documents of one type, each stored under the key [keyOf] returns for it.
     *
     * Get one through [DocumentStore.collection]. A document that lacks a property its type declares with a default
     * value is read with that default. A property the type no longer declares is skipped.
     */
    inner class Collection<T>(
        private val name: String,
        private val serializer: KSerializer<T>,
        private val keyOf: (T) -> String,
    ) {
        /**
         * Returns the document stored under [key], or `null` if none is.
         */
        suspend operator fun get(key: String): T? =
            use { select("SELECT document FROM $name WHERE key = ?", key) }
                .singleOrNull()
                ?.let { JSON.decodeFromString(serializer, it) }

        /**
         * Returns whether a document is stored under [key].
         */
        suspend operator fun contains(key: String): Boolean = use { select("SELECT key FROM $name WHERE key = ?", key) }.isNotEmpty()

        /**
         * Writes [document] under its key, replacing the document stored under the same key. A replaced document keeps
         * its place in the order [all] returns.
         */
        suspend fun put(document: T) {
            val json = JSON.encodeToString(serializer, document)
            use {
                update(
                    "INSERT INTO $name (key, document) VALUES (?, ?) " +
                        "ON CONFLICT (key) DO UPDATE SET document = excluded.document",
                    keyOf(document),
                    json,
                )
            }
        }

        /**
         * Removes the document stored under [key]. Does nothing if none is.
         */
        suspend fun remove(key: String) {
            use { update("DELETE FROM $name WHERE key = ?", key) }
        }

        /**
         * Returns every document, in the order they were first written.
         */
        suspend fun all(): List<T> =
            use { select("SELECT document FROM $name ORDER BY rowid") }
                .map { JSON.decodeFromString(serializer, it) }
    }

    companion object {
        private val COLLECTION_NAME = Regex("[a-z0-9_]+")

        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /**
         * Returns the store of the SQLite file [file], created if it does not exist.
         */
        suspend fun open(file: Path): DocumentStore =
            withContext(Dispatchers.IO) {
                file.toAbsolutePath().parent.createDirectories()
                val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
                runCatching { connection.update("PRAGMA journal_mode = WAL") }
                    .onFailure { failure -> runCatching { connection.close() }.onFailure(failure::addSuppressed) }
                    .getOrThrow()
                DocumentStore(connection)
            }

        private fun Connection.update(
            sql: String,
            vararg parameters: String,
        ) {
            prepareStatement(sql).use { it.bind(parameters).execute() }
        }

        private fun Connection.select(
            sql: String,
            vararg parameters: String,
        ): List<String> =
            prepareStatement(sql).use { statement ->
                val result = statement.bind(parameters).executeQuery()
                generateSequence { result.takeIf { it.next() }?.getString(1) }.toList()
            }

        private fun PreparedStatement.bind(parameters: Array<out String>): PreparedStatement =
            apply { parameters.forEachIndexed { index, value -> setString(index + 1, value) } }
    }
}
