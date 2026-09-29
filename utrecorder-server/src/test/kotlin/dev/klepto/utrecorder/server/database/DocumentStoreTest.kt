package dev.klepto.utrecorder.server.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

class DocumentStoreTest :
    FunSpec({
        @Serializable
        data class Entry(
            val id: String,
        )

        val file = createTempDirectory("utrecorder-document-store-test").resolve("nested/store.db")

        test("creates the database file and returns the block result") {
            DocumentStore.open(file) { 42 } shouldBe 42
            file.exists() shouldBe true
        }

        test("keeps documents across a reopen") {
            DocumentStore.open(file) { it.collection<Entry>("entries", Entry::id).put(Entry("a")) }
            DocumentStore.open(file) { it.collection<Entry>("entries", Entry::id)["a"] } shouldBe Entry("a")
        }

        test("rejects a collection name outside a-z, 0-9 and _") {
            DocumentStore.open(file) { store ->
                shouldThrow<IllegalArgumentException> { store.collection<Entry>("entries; drop", Entry::id) }
            }
        }
    })
