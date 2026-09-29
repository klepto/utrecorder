package dev.klepto.utrecorder.server.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class CachedCollectionTest :
    FunSpec({
        @Serializable
        data class Entry(
            val id: String,
            val value: Int,
        )

        fun file(): Path = createTempDirectory("utrecorder-cached-collection-test").resolve("store.db")

        test("returns the documents stored before its first transaction") {
            val file = file()
            SqliteDocumentStore.open(file) { store ->
                store.cachedCollection<Entry>("entries", key = Entry::id).transaction { it.put(Entry("a", 1)) }
            }
            val all = SqliteDocumentStore.open(file) { it.cachedCollection<Entry>("entries", key = Entry::id).all() }
            all shouldBe listOf(Entry("a", 1))
        }

        test("writes each change through to the store") {
            val file = file()
            SqliteDocumentStore.open(file) { store ->
                store.cachedCollection<Entry>("entries", key = Entry::id).transaction {
                    it.put(Entry("a", 1))
                    it.put(Entry("b", 2))
                    it.put(Entry("a", 3))
                    it.remove("b")
                }
            }
            val all = SqliteDocumentStore.open(file) { it.cachedCollection<Entry>("entries", key = Entry::id).all() }
            all shouldBe listOf(Entry("a", 3))
        }

        test("answers reads from memory after its first transaction") {
            val entry =
                SqliteDocumentStore.open(file()) { store ->
                    val cached = store.cachedCollection<Entry>("entries", key = Entry::id)
                    cached.transaction { it["a"] }
                    store.collection<Entry>("entries", key = Entry::id).transaction { it.put(Entry("a", 1)) }
                    cached.transaction { it["a"] }
                }
            entry shouldBe null
        }

        test("keeps none of the changes of a transaction that throws") {
            val all =
                SqliteDocumentStore.open(file()) { store ->
                    val entries = store.cachedCollection<Entry>("entries", key = Entry::id)
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

        test("returns every document in first-write order") {
            val all =
                SqliteDocumentStore.open(file()) { store ->
                    val entries = store.cachedCollection<Entry>("entries", key = Entry::id)
                    entries.transaction {
                        it.put(Entry("b", 1))
                        it.put(Entry("a", 2))
                        it.put(Entry("b", 3))
                    }
                    entries.all()
                }
            all shouldBe listOf(Entry("b", 3), Entry("a", 2))
        }

        test("raises its revision after each transaction that puts or removes a document") {
            val revision =
                SqliteDocumentStore.open(file()) { store ->
                    val entries = store.cachedCollection<Entry>("entries", key = Entry::id)
                    entries.transaction { it.put(Entry("a", 1)) }
                    entries.transaction { it.remove("a") }
                    entries.revision.value
                }
            revision shouldBe 2
        }

        test("keeps its revision after a transaction that only reads") {
            val revision =
                SqliteDocumentStore.open(file()) { store ->
                    val entries = store.cachedCollection<Entry>("entries", key = Entry::id)
                    entries.transaction { it["a"] }
                    entries.all()
                    entries.revision.value
                }
            revision shouldBe 0
        }

        test("keeps its revision after a transaction that throws") {
            val revision =
                SqliteDocumentStore.open(file()) { store ->
                    val entries = store.cachedCollection<Entry>("entries", key = Entry::id)
                    shouldThrow<IllegalStateException> {
                        entries.transaction {
                            it.put(Entry("a", 1))
                            error("Failed")
                        }
                    }
                    entries.revision.value
                }
            revision shouldBe 0
        }
    })
