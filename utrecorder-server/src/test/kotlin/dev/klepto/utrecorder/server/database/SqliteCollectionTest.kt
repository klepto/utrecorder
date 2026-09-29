package dev.klepto.utrecorder.server.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

class SqliteCollectionTest :
    FunSpec({
        @Serializable
        data class Entry(
            val id: String,
            val value: Int,
        )

        @Serializable
        data class ExtendedEntry(
            val id: String,
            val value: Int,
            val note: String = "none",
        )

        fun file(): Path = createTempDirectory("utrecorder-sqlite-collection-test").resolve("store.db")

        suspend fun <T : Any, R> collection(
            file: Path,
            serializer: KSerializer<T>,
            key: (T) -> String,
            block: suspend (DocumentCollection<T>) -> R,
        ): R =
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.autoCommit = false
                block(SqliteCollection(connection, Mutex(), "entries", serializer, key))
            }

        suspend fun <R> entries(
            file: Path = file(),
            block: suspend (DocumentCollection<Entry>) -> R,
        ): R = collection(file, serializer<Entry>(), Entry::id, block)

        test("returns a stored document") {
            val entry =
                entries { entries ->
                    entries.transaction {
                        it.put(Entry("a", 1))
                        it["a"]
                    }
                }
            entry shouldBe Entry("a", 1)
        }

        test("replaces the document stored under the same key") {
            val all =
                entries { entries ->
                    entries.transaction {
                        it.put(Entry("a", 1))
                        it.put(Entry("a", 2))
                    }
                    entries.all()
                }
            all shouldBe listOf(Entry("a", 2))
        }

        test("removes a document") {
            val entry =
                entries { entries ->
                    entries.transaction {
                        it.put(Entry("a", 1))
                        it.remove("a")
                        it["a"]
                    }
                }
            entry shouldBe null
        }

        test("returns null for a missing key") {
            entries { entries -> entries.transaction { it["missing"] } } shouldBe null
        }

        test("returns every document in first-write order") {
            val all =
                entries { entries ->
                    entries.transaction {
                        it.put(Entry("b", 1))
                        it.put(Entry("a", 2))
                        it.put(Entry("b", 3))
                    }
                    entries.all()
                }
            all shouldBe listOf(Entry("b", 3), Entry("a", 2))
        }

        test("keeps none of the changes of a transaction that throws") {
            val all =
                entries { entries ->
                    entries.transaction { it.put(Entry("a", 1)) }
                    shouldThrow<IllegalStateException> {
                        entries.transaction {
                            it.put(Entry("a", 2))
                            it.put(Entry("b", 3))
                            error("Failed")
                        }
                    }
                    entries.all()
                }
            all shouldBe listOf(Entry("a", 1))
        }

        test("reads an older document with the default of an added property") {
            val file = file()
            entries(file) { entries -> entries.transaction { it.put(Entry("a", 1)) } }
            val entry =
                collection(file, serializer<ExtendedEntry>(), ExtendedEntry::id) { entries ->
                    entries.transaction { it["a"] }
                }
            entry shouldBe ExtendedEntry("a", 1, "none")
        }
    })
