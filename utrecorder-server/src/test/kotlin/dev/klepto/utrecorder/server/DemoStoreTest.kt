package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.RecordingLane.LONG
import dev.klepto.utrecorder.server.RecordingLane.SHORT
import dev.klepto.utrecorder.server.RecordingState.ARCHIVED
import dev.klepto.utrecorder.server.RecordingState.EXPIRED
import dev.klepto.utrecorder.server.RecordingState.FAILED
import dev.klepto.utrecorder.server.RecordingState.PENDING
import dev.klepto.utrecorder.server.RecordingState.RECORDED
import dev.klepto.utrecorder.server.RecordingState.RECORDING
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalPathApi::class)
class DemoStoreTest :
    FunSpec({
        suspend fun withDirectories(block: suspend (database: Path, cache: Path) -> Unit) {
            val directory = createTempDirectory("utrecorder-store-test")
            try {
                block(directory["server.db"], directory["recordings"].createDirectories())
            } finally {
                directory.deleteRecursively()
            }
        }

        suspend fun <T> withStore(
            database: Path,
            cache: Path,
            block: suspend (DemoStore) -> T,
        ): T {
            val store = DemoStore.open(database, cache)
            return try {
                block(store)
            } finally {
                store.close()
            }
        }

        fun query(
            database: Path,
            sql: String,
        ): String =
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { statement ->
                    val result = statement.executeQuery(sql)
                    result.next()
                    (1..result.metaData.columnCount).joinToString(" ") { "${result.getString(it)}" }
                }
            }

        val demo =
            Demo(
                id = 0,
                sourceId = "7f0e2a52-8d5c-4c1f-9a53-5b6f0f1d2c11",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83_120.milliseconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
                recordingId = 0,
            )

        test("migrates an empty file and re-opens a migrated one") {
            withDirectories { database, cache ->
                val contents = withStore(database, cache) { it.load() }
                contents.demos.shouldBeEmpty()
                contents.recordings.shouldBeEmpty()
                contents.cleanups.shouldBeEmpty()
                query(database, "PRAGMA user_version") shouldBe "1"
                query(database, "PRAGMA journal_mode") shouldBe "wal"

                withStore(database, cache) { it.load() }.demos.shouldBeEmpty()
                query(database, "PRAGMA user_version") shouldBe "1"
            }
        }

        test("inserts a demo with its recording") {
            withDirectories { database, cache ->
                val unparsed = demo.copy(map = null, duration = null)
                val (stored, recording) = withStore(database, cache) { it.insert(unparsed, Recording(lane = LONG)) }

                val contents = withStore(database, cache) { it.load() }

                stored shouldBe unparsed.copy(id = 1, recordingId = 1)
                recording shouldBe Recording(id = 1, lane = LONG)
                contents.demos shouldContainExactly listOf(stored)
                contents.recordings shouldContainExactly listOf(recording)
            }
        }

        test("persists every recording transition") {
            withDirectories { database, cache ->
                cache["demo.mp4"].writeText("mp4")
                val recorded =
                    Recording(
                        id = 1,
                        lane = SHORT,
                        state = RECORDED,
                        attempts = 2,
                        error = "Capture timed out",
                        file = "demo.mp4",
                        md5 = "d41d8cd98f00b204e9800998ecf8427e",
                        recordedAt = Instant.parse("2026-09-02T10:00:00Z"),
                        accessedAt = Instant.parse("2026-09-03T10:00:00Z"),
                    )
                val archived =
                    recorded.copy(
                        state = ARCHIVED,
                        archiveId = "utrecorder-7f0e2a52",
                        collection = "test_collection",
                        archivedAt = Instant.parse("2026-09-02T11:00:00Z"),
                    )
                val transitions =
                    listOf(recorded, archived, archived.copy(state = EXPIRED, file = null), recorded.copy(state = FAILED))
                withStore(database, cache) { it.insert(demo, Recording(lane = SHORT)) }

                for (recording in transitions) {
                    withStore(database, cache) { it.update(recording) }

                    withStore(database, cache) { it.load() }.recordings shouldContainExactly listOf(recording)
                }
            }
        }

        test("adds, updates and removes a cleanup row") {
            withDirectories { database, cache ->
                val path = cache["stale"]
                val failed = Cleanup(path, attempts = 1, error = "Access denied")

                withStore(database, cache) { it.put(Cleanup(path)) }
                withStore(database, cache) { it.load() }.cleanups shouldContainExactly listOf(Cleanup(path))

                withStore(database, cache) { it.put(failed) }
                withStore(database, cache) { it.load() }.cleanups shouldContainExactly listOf(failed)

                withStore(database, cache) { it.remove(path) }
                withStore(database, cache) { it.load() }.cleanups.shouldBeEmpty()
            }
        }

        test("restores a recording in progress as pending") {
            withDirectories { database, cache ->
                withStore(database, cache) { store ->
                    val (_, recording) = store.insert(demo, Recording(lane = SHORT))
                    store.update(recording.copy(state = RECORDING, attempts = 1))
                }

                val recordings = withStore(database, cache) { it.load() }.recordings

                recordings shouldContainExactly listOf(Recording(id = 1, lane = SHORT, state = PENDING, attempts = 1))
                query(database, "SELECT state FROM recordings WHERE id = 1") shouldBe "pending"
            }
        }

        test("restores a recording whose mp4 is missing without its file") {
            withDirectories { database, cache ->
                cache["kept.mp4"].writeText("mp4")
                withStore(database, cache) { store ->
                    val (_, kept) = store.insert(demo, Recording(lane = SHORT))
                    val (_, missing) = store.insert(demo.copy(sourceId = "other"), Recording(lane = SHORT))
                    store.update(kept.copy(state = RECORDED, file = "kept.mp4"))
                    store.update(missing.copy(state = ARCHIVED, file = "missing.mp4"))
                }

                val recordings = withStore(database, cache) { it.load() }.recordings

                recordings shouldContainExactly
                    listOf(
                        Recording(id = 1, lane = SHORT, state = RECORDED, file = "kept.mp4"),
                        Recording(id = 2, lane = SHORT, state = ARCHIVED),
                    )
                query(database, "SELECT file FROM recordings WHERE id = 2") shouldBe "null"
            }
        }
    })
