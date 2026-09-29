package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

/**
 * [DocumentStore] on one SQLite connection, with one table per collection. A collection name is made of `a-z`, `0-9`
 * and `_` only.
 *
 * Open a store with [open].
 */
class SqliteDocumentStore private constructor(
    private val connection: Connection,
) : DocumentStore {
    private val lock = Mutex()

    override fun <T : Any> collection(
        name: String,
        serializer: KSerializer<T>,
        cached: Boolean,
        key: (T) -> String,
    ): DocumentCollection<T> {
        require(name.matches(NAME)) { "Invalid collection name: $name" }
        val collection = SqliteCollection(connection, lock, name, serializer, key)
        return when (cached) {
            true -> CachedCollection(collection, key)
            false -> collection
        }
    }

    companion object {
        private val NAME = Regex("[a-z0-9_]+")

        /**
         * Returns the result of [block], run with a store on the SQLite database [file]. The store is closed when
         * [block] ends.
         */
        suspend fun <R> open(
            file: Path,
            block: suspend (DocumentStore) -> R,
        ): R {
            val connection = connect(file)
            try {
                return block(SqliteDocumentStore(connection))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { connection.close() }
            }
        }

        private suspend fun connect(file: Path): Connection =
            withContext(Dispatchers.IO) {
                file.toAbsolutePath().parent.createDirectories()
                DriverManager.getConnection("jdbc:sqlite:$file").apply {
                    createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                    autoCommit = false
                }
            }
    }
}
