package dev.klepto.utrecorder.server.database

import dev.klepto.utrecorder.common.io.get
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
class DocumentStoreTest :
    FunSpec({
        suspend fun withFile(block: suspend (Path) -> Unit) {
            val directory = createTempDirectory("utrecorder-document-store-test")
            try {
                block(directory["database"]["server.db"])
            } finally {
                directory.deleteRecursively()
            }
        }

        suspend fun <T> withStore(
            file: Path,
            block: suspend (DocumentStore) -> T,
        ): T {
            val store = DocumentStore.open(file)
            return try {
                block(store)
            } finally {
                store.close()
            }
        }

        suspend fun DocumentStore.notes(): DocumentStore.Collection<Note> = collection("notes", Note.serializer(), Note::id)

        test("gets a document by its key and tells whether a key is stored") {
            withFile { file ->
                withStore(file) { store ->
                    val notes = store.notes()
                    notes.put(Note("first", "hello"))

                    notes["first"] shouldBe Note("first", "hello")
                    ("first" in notes) shouldBe true
                    notes["missing"] shouldBe null
                    ("missing" in notes) shouldBe false
                }
            }
        }

        test("replaces a document of the same key and keeps its place") {
            withFile { file ->
                withStore(file) { store ->
                    val notes = store.notes()
                    notes.put(Note("first", "hello"))
                    notes.put(Note("second", "world"))

                    notes.put(Note("first", "changed"))

                    notes.all() shouldContainExactly listOf(Note("first", "changed"), Note("second", "world"))
                }
            }
        }

        test("removes a document") {
            withFile { file ->
                withStore(file) { store ->
                    val notes = store.notes()
                    notes.put(Note("first", "hello"))

                    notes.remove("first")
                    notes.remove("missing")

                    notes.all().shouldBeEmpty()
                }
            }
        }

        test("keeps the documents of two collections apart") {
            withFile { file ->
                withStore(file) { store ->
                    val notes = store.notes()
                    val drafts = store.collection("drafts", Note.serializer(), Note::id)
                    notes.put(Note("first", "note"))
                    drafts.put(Note("first", "draft"))

                    notes["first"] shouldBe Note("first", "note")
                    drafts["first"] shouldBe Note("first", "draft")
                }
            }
        }

        test("creates the file in write-ahead log mode and keeps its documents across a re-open") {
            withFile { file ->
                withStore(file) { it.notes().put(Note("first", "hello")) }

                withStore(file) { it.notes().all() } shouldContainExactly listOf(Note("first", "hello"))
                DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                    connection.createStatement().use { statement ->
                        val result = statement.executeQuery("PRAGMA journal_mode")
                        result.next()
                        result.getString(1) shouldBe "wal"
                    }
                }
            }
        }

        test("reads a document of an older shape with the defaults of added properties and without removed ones") {
            withFile { file ->
                withStore(file) { store ->
                    store.collection("notes", OldNote.serializer(), OldNote::id).put(OldNote("first", "hello", 3))
                }

                withStore(file) { it.notes()["first"] } shouldBe Note("first", "hello")
            }
        }

        test("rejects a collection name that is not a plain identifier") {
            withFile { file ->
                withStore(file) { store ->
                    shouldThrow<IllegalArgumentException> { store.collection("notes; DROP TABLE x", Note.serializer(), Note::id) }
                }
            }
        }
    }) {
    @Serializable
    data class Note(
        val id: String,
        val text: String,
        val tags: List<String> = emptyList(),
    )

    @Serializable
    data class OldNote(
        val id: String,
        val text: String,
        val priority: Int,
    )
}
