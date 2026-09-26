package dev.klepto.utrecorder.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

/**
 * SQLite database of the demos, recordings and cleanup rows of the server.
 *
 * Use [open] to get one, and [close] it when done. Every change is written by one call. [load] reads the database once,
 * after it is opened.
 *
 * @property cache The directory the [Recording.file] of every recording is resolved against.
 */
class DemoStore private constructor(
    private val connection: Connection,
    private val cache: Path,
) {
    private val mutex = Mutex()

    /**
     * Returns every stored demo, recording and cleanup row.
     *
     * A recording in [RecordingState.RECORDING] is returned as [RecordingState.PENDING]. A recording whose mp4 is
     * missing from the cache directory is returned without its [Recording.file]. Both changes are also written.
     */
    suspend fun load(): DemoStoreContents =
        transaction {
            val recordings = selectAll(SELECT_RECORDINGS, ::readRecording)
            val restored = recordings.map(::restore)
            (recordings zip restored)
                .filter { (recording, restoredRecording) -> recording != restoredRecording }
                .forEach { (_, restoredRecording) -> updateRecording(restoredRecording) }
            DemoStoreContents(
                demos = selectAll(SELECT_DEMOS, ::readDemo),
                recordings = restored,
                cleanups = selectAll(SELECT_CLEANUPS, ::readCleanup),
            )
        }

    /**
     * Returns [demo] and [recording] as stored, each with its assigned key. The key [Demo.id], [Demo.recordingId] and
     * [Recording.id] of the arguments are ignored.
     */
    suspend fun insert(
        demo: Demo,
        recording: Recording,
    ): Pair<Demo, Recording> =
        transaction {
            val storedRecording = recording.copy(id = insertRecording(recording))
            val storedDemo = demo.copy(recordingId = storedRecording.id)
            storedDemo.copy(id = insertDemo(storedDemo)) to storedRecording
        }

    /**
     * Writes every field of [recording] to the stored recording of the same [Recording.id].
     */
    suspend fun update(recording: Recording) {
        transaction { updateRecording(recording) }
    }

    /**
     * Writes [cleanup], replacing the stored cleanup row of the same [Cleanup.path].
     */
    suspend fun put(cleanup: Cleanup) {
        transaction {
            execute(UPSERT_CLEANUP) {
                setString(1, cleanup.path.toString())
                setInt(2, cleanup.attempts)
                setString(3, cleanup.error)
            }
        }
    }

    /**
     * Removes the cleanup row of [path].
     */
    suspend fun remove(path: Path) {
        transaction { execute(DELETE_CLEANUP) { setString(1, path.toString()) } }
    }

    /**
     * Closes the database connection.
     */
    suspend fun close() {
        mutex.withLock { withContext(Dispatchers.IO) { connection.close() } }
    }

    private suspend fun <T> transaction(block: Connection.() -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { connection.inTransaction(block) } }

    private fun restore(recording: Recording): Recording =
        recording
            .let { if (it.state == RecordingState.RECORDING) it.copy(state = RecordingState.PENDING) else it }
            .let { if (it.file != null && !cache.resolve(it.file).exists()) it.copy(file = null) else it }

    companion object {
        /**
         * Schema migrations, applied in order. The migration at index `i` moves `PRAGMA user_version` from `i` to
         * `i + 1`.
         */
        private val MIGRATIONS: List<List<String>> =
            listOf(
                listOf(
                    """
                    CREATE TABLE recordings (
                        id INTEGER PRIMARY KEY,
                        lane TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        error TEXT,
                        file TEXT,
                        md5 TEXT,
                        archive_id TEXT,
                        collection TEXT,
                        recorded_at TEXT,
                        archived_at TEXT,
                        accessed_at TEXT
                    )
                    """,
                    """
                    CREATE TABLE demos (
                        id INTEGER PRIMARY KEY,
                        source_id TEXT NOT NULL UNIQUE,
                        map TEXT,
                        player TEXT NOT NULL,
                        cap_time_ms INTEGER NOT NULL,
                        duration_ms INTEGER,
                        added_at TEXT NOT NULL,
                        recording_id INTEGER NOT NULL REFERENCES recordings (id)
                    )
                    """,
                    """
                    CREATE TABLE cleanups (
                        path TEXT PRIMARY KEY,
                        attempts INTEGER NOT NULL,
                        error TEXT
                    )
                    """,
                ),
            )

        private const val SELECT_DEMOS =
            "SELECT id, source_id, map, player, cap_time_ms, duration_ms, added_at, recording_id FROM demos ORDER BY id"

        private const val SELECT_RECORDINGS =
            "SELECT id, lane, state, attempts, error, file, md5, archive_id, collection, recorded_at, archived_at, " +
                "accessed_at FROM recordings ORDER BY id"

        private const val SELECT_CLEANUPS = "SELECT path, attempts, error FROM cleanups ORDER BY path"

        private const val INSERT_DEMO =
            "INSERT INTO demos (source_id, map, player, cap_time_ms, duration_ms, added_at, recording_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"

        private const val INSERT_RECORDING =
            "INSERT INTO recordings (lane, state, attempts, error, file, md5, archive_id, collection, recorded_at, " +
                "archived_at, accessed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private const val UPDATE_RECORDING =
            "UPDATE recordings SET lane = ?, state = ?, attempts = ?, error = ?, file = ?, md5 = ?, archive_id = ?, " +
                "collection = ?, recorded_at = ?, archived_at = ?, accessed_at = ? WHERE id = ?"

        private const val UPSERT_CLEANUP =
            "INSERT INTO cleanups (path, attempts, error) VALUES (?, ?, ?) " +
                "ON CONFLICT (path) DO UPDATE SET attempts = excluded.attempts, error = excluded.error"

        private const val DELETE_CLEANUP = "DELETE FROM cleanups WHERE path = ?"

        /**
         * Returns the store of the SQLite file [database], created and migrated to the latest schema if needed.
         *
         * @param cache The directory the [Recording.file] of every recording is resolved against.
         */
        suspend fun open(
            database: Path,
            cache: Path,
        ): DemoStore =
            withContext(Dispatchers.IO) {
                database.toAbsolutePath().parent.createDirectories()
                val connection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
                runCatching { connection.initialize() }
                    .onFailure { failure -> runCatching { connection.close() }.onFailure(failure::addSuppressed) }
                    .getOrThrow()
                DemoStore(connection, cache)
            }

        private fun Connection.initialize() {
            createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            val version = selectAll("PRAGMA user_version") { it.getInt(1) }.single()
            MIGRATIONS.withIndex().drop(version).forEach { (index, statements) -> migrate(index + 1, statements) }
        }

        private fun Connection.migrate(
            version: Int,
            statements: List<String>,
        ) {
            inTransaction {
                createStatement().use { statement ->
                    statements.forEach { statement.execute(it.trimIndent()) }
                    statement.execute("PRAGMA user_version = $version")
                }
            }
        }

        private fun <T> Connection.inTransaction(block: Connection.() -> T): T {
            autoCommit = false
            return try {
                block().also { commit() }
            } catch (failure: Throwable) {
                runCatching { rollback() }.onFailure(failure::addSuppressed)
                throw failure
            } finally {
                autoCommit = true
            }
        }

        private fun Connection.execute(
            sql: String,
            parameters: PreparedStatement.() -> Unit,
        ) {
            prepareStatement(sql).use { it.apply(parameters).executeUpdate() }
        }

        private fun Connection.insert(
            sql: String,
            parameters: PreparedStatement.() -> Unit,
        ): Long {
            execute(sql, parameters)
            return selectAll("SELECT last_insert_rowid()") { it.getLong(1) }.single()
        }

        private fun <T> Connection.selectAll(
            sql: String,
            reader: (ResultSet) -> T,
        ): List<T> =
            createStatement().use { statement ->
                val result = statement.executeQuery(sql)
                generateSequence { result.takeIf { it.next() }?.let(reader) }.toList()
            }

        private fun Connection.insertDemo(demo: Demo): Long =
            insert(INSERT_DEMO) {
                setString(1, demo.sourceId)
                setString(2, demo.map)
                setString(3, demo.player)
                setLong(4, demo.capTime.inWholeMilliseconds)
                setNullableLong(5, demo.duration?.inWholeMilliseconds)
                setString(6, demo.addedAt.toString())
                setLong(7, demo.recordingId)
            }

        private fun Connection.insertRecording(recording: Recording): Long = insert(INSERT_RECORDING) { setRecording(recording) }

        private fun Connection.updateRecording(recording: Recording) {
            execute(UPDATE_RECORDING) {
                setRecording(recording)
                setLong(12, recording.id)
            }
        }

        private fun PreparedStatement.setRecording(recording: Recording) {
            setString(1, recording.lane.value)
            setString(2, recording.state.value)
            setInt(3, recording.attempts)
            setString(4, recording.error)
            setString(5, recording.file)
            setString(6, recording.md5)
            setString(7, recording.archiveId)
            setString(8, recording.collection)
            setString(9, recording.recordedAt?.toString())
            setString(10, recording.archivedAt?.toString())
            setString(11, recording.accessedAt?.toString())
        }

        private fun PreparedStatement.setNullableLong(
            index: Int,
            value: Long?,
        ) {
            if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)
        }

        private fun readDemo(result: ResultSet): Demo =
            Demo(
                id = result.getLong("id"),
                sourceId = result.getString("source_id"),
                map = result.getString("map"),
                player = result.getString("player"),
                capTime = result.getLong("cap_time_ms").milliseconds,
                duration = result.getNullableLong("duration_ms")?.milliseconds,
                addedAt = Instant.parse(result.getString("added_at")),
                recordingId = result.getLong("recording_id"),
            )

        private fun readRecording(result: ResultSet): Recording =
            Recording(
                id = result.getLong("id"),
                lane = RecordingLane.of(result.getString("lane")),
                state = RecordingState.of(result.getString("state")),
                attempts = result.getInt("attempts"),
                error = result.getString("error"),
                file = result.getString("file"),
                md5 = result.getString("md5"),
                archiveId = result.getString("archive_id"),
                collection = result.getString("collection"),
                recordedAt = result.getString("recorded_at")?.let(Instant::parse),
                archivedAt = result.getString("archived_at")?.let(Instant::parse),
                accessedAt = result.getString("accessed_at")?.let(Instant::parse),
            )

        private fun readCleanup(result: ResultSet): Cleanup =
            Cleanup(
                path = Path.of(result.getString("path")),
                attempts = result.getInt("attempts"),
                error = result.getString("error"),
            )

        private fun ResultSet.getNullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }
    }
}

/**
 * Every row of a [DemoStore], as [DemoStore.load] returns it.
 *
 * @property demos The demos, in the order they were stored.
 * @property recordings The recordings, in the order they were stored.
 * @property cleanups The cleanup rows.
 */
data class DemoStoreContents(
    val demos: List<Demo>,
    val recordings: List<Recording>,
    val cleanups: List<Cleanup>,
)
