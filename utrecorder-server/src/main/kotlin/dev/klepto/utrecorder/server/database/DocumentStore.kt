package dev.klepto.utrecorder.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

/**
 * JSON documents on one SQLite connection, grouped in named collections.
 *
 * Open a store with [open].
 */
class DocumentStore private constructor(
    private val connection: Connection,
) {
    private val lock = Mutex()

    /**
     * Returns the collection [name] of this store. Each document is stored under the key that [key] returns for it.
     *
     * @throws IllegalArgumentException If [name] is not made of `a-z`, `0-9` and `_` only.
     */
    fun <T> collection(
        name: String,
        serializer: KSerializer<T>,
        key: (T) -> String,
    ): DocumentCollection<T> {
        require(name.matches(NAME)) { "Invalid collection name: $name" }
        return DocumentCollection(connection, lock, name, serializer, key)
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
                return block(DocumentStore(connection))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { connection.close() }
            }
        }

        private suspend fun connect(file: Path): Connection =
            withContext(Dispatchers.IO) {
                file.toAbsolutePath().parent.createDirectories()
                DriverManager.getConnection("jdbc:sqlite:$file").apply {
                    createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                }
            }
    }
}

/**
 * Returns the collection [name] of this store, serialized with the serializer of [T].
 */
inline fun <reified T> DocumentStore.collection(
    name: String,
    noinline key: (T) -> String,
): DocumentCollection<T> = collection(name, serializer<T>(), key)
