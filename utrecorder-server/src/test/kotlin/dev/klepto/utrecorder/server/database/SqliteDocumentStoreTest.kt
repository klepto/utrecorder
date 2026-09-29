package dev.klepto.utrecorder.server.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

class SqliteDocumentStoreTest :
    FunSpec({
        @Serializable
        data class Entry(
            val id: String,
        )

        val file = createTempDirectory("utrecorder-sqlite-document-store-test").resolve("nested/store.db")

        test("creates the database file and returns the block result") {
            SqliteDocumentStore.open(file) { 42 } shouldBe 42
            file.exists() shouldBe true
        }

        test("keeps documents across a reopen") {
            SqliteDocumentStore.open(file) { store ->
                store.collection<Entry>("entries", key = Entry::id).transaction { it.put(Entry("a")) }
            }
            val entry =
                SqliteDocumentStore.open(file) { store ->
                    store.collection<Entry>("entries", key = Entry::id).transaction { it["a"] }
                }
            entry shouldBe Entry("a")
        }

        test("rejects a collection name outside a-z, 0-9 and _") {
            SqliteDocumentStore.open(file) { store ->
                shouldThrow<IllegalArgumentException> { store.collection<Entry>("entries; drop", key = Entry::id) }
            }
        }
    })
