package dev.klepto.utrecorder.server.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.nio.file.Path
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

        suspend fun <R> entries(block: suspend (DocumentCollection<Entry>) -> R): R =
            SqliteDocumentStore.open(file()) { block(it.collection<Entry>("entries", key = Entry::id)) }

        test("returns a stored document") {
            val entry =
                entries {
                    it.put(Entry("a", 1))
                    it["a"]
                }
            entry shouldBe Entry("a", 1)
        }

        test("replaces the document stored under the same key") {
            val all =
                entries {
                    it.put(Entry("a", 1))
                    it.put(Entry("a", 2))
                    it.all()
                }
            all shouldBe listOf(Entry("a", 2))
        }

        test("removes a document") {
            val entry =
                entries {
                    it.put(Entry("a", 1))
                    it.remove("a")
                    it["a"]
                }
            entry shouldBe null
        }

        test("returns null for a missing key") {
            entries { it["missing"] } shouldBe null
        }

        test("returns every document in first-write order") {
            val all =
                entries {
                    it.put(Entry("b", 1))
                    it.put(Entry("a", 2))
                    it.put(Entry("b", 3))
                    it.all()
                }
            all shouldBe listOf(Entry("b", 3), Entry("a", 2))
        }

        test("keeps none of the changes of a transaction that throws") {
            val all =
                entries { entries ->
                    entries.put(Entry("a", 1))
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
            SqliteDocumentStore.open(file) { it.collection<Entry>("entries", key = Entry::id).put(Entry("a", 1)) }
            val entry = SqliteDocumentStore.open(file) { it.collection<ExtendedEntry>("entries", key = ExtendedEntry::id)["a"] }
            entry shouldBe ExtendedEntry("a", 1, "none")
        }
    })
