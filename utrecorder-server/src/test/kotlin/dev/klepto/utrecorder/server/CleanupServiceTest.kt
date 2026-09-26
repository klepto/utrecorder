package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.database.DocumentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalPathApi::class)
class CleanupServiceTest :
    FunSpec({
        suspend fun withDirectories(block: suspend (database: Path, cache: Path) -> Unit) {
            val directory = createTempDirectory("utrecorder-cleanup-test")
            try {
                block(directory["server.db"], directory["files"].createDirectories())
            } finally {
                directory.deleteRecursively()
            }
        }

        suspend fun <T> withCleanups(
            database: Path,
            block: suspend (DocumentStore.Collection<Cleanup>) -> T,
        ): T {
            val store = DocumentStore.open(database)
            return try {
                block(store.collection("cleanups", Cleanup.serializer()) { it.path.toString() })
            } finally {
                store.close()
            }
        }

        test("deletes a file and removes its cleanup row") {
            withDirectories { database, cache ->
                withCleanups(database) { cleanups ->
                    val file = cache["stale.mp4"]
                    file.writeText("data")

                    CleanupService(cleanups).delete(file)

                    file.exists() shouldBe false
                    cleanups.all().shouldBeEmpty()
                }
            }
        }

        test("deletes a directory recursively") {
            withDirectories { database, cache ->
                withCleanups(database) { cleanups ->
                    val directory = cache["workspace"].createDirectories()
                    directory["file.txt"].writeText("data")

                    CleanupService(cleanups).delete(directory)

                    directory.exists() shouldBe false
                    cleanups.all().shouldBeEmpty()
                }
            }
        }

        test("deleting a path that no longer exists counts as success") {
            withDirectories { database, cache ->
                withCleanups(database) { cleanups ->
                    CleanupService(cleanups).delete(cache["missing.mp4"])

                    cleanups.all().shouldBeEmpty()
                }
            }
        }

        test("keeps a failed deletion's row with its attempts and error") {
            withDirectories { database, cache ->
                withCleanups(database) { cleanups ->
                    val file = cache["locked.mp4"]
                    file.writeText("data")

                    FileOutputStream(file.toFile()).use { CleanupService(cleanups).delete(file) }

                    val rows = cleanups.all()
                    rows shouldHaveSize 1
                    rows.single().path shouldBe file
                    rows.single().attempts shouldBe 1
                    rows.single().error shouldNotBe null
                }
            }
        }

        test("a retry pass removes a row once the deletion succeeds") {
            withDirectories { database, cache ->
                withCleanups(database) { cleanups ->
                    val file = cache["locked.mp4"]
                    file.writeText("data")
                    val cleanupService = CleanupService(cleanups)
                    FileOutputStream(file.toFile()).use { cleanupService.delete(file) }
                    cleanups.all() shouldHaveSize 1

                    cleanupService.retry()

                    file.exists() shouldBe false
                    cleanups.all().shouldBeEmpty()
                }
            }
        }

        test("the loop retries at startup and then hourly until cancelled") {
            withDirectories { database, cache ->
                withCleanups(database) { cleanups ->
                    val first = cache["first.mp4"]
                    first.writeText("data")
                    cleanups.put(Cleanup(first))

                    runTest {
                        // Waits for a condition an in-flight background deletion or store write is racing to
                        // satisfy, pumping any continuation the test dispatcher is holding queued on every attempt.
                        suspend fun waitUntil(
                            timeoutMs: Long = 5_000,
                            condition: suspend () -> Boolean,
                        ) {
                            val deadline = System.currentTimeMillis() + timeoutMs
                            while (!condition()) {
                                check(System.currentTimeMillis() < deadline) { "Timed out waiting for condition" }
                                runCurrent()
                                withContext(Dispatchers.IO) { Thread.sleep(5) }
                            }
                        }

                        val job = launch { CleanupService(cleanups).loop() }
                        waitUntil { !first.exists() }
                        waitUntil { cleanups.all().isEmpty() }

                        val second = cache["second.mp4"]
                        second.writeText("data")
                        cleanups.put(Cleanup(second))

                        advanceTimeBy(59.minutes)
                        runCurrent()
                        second.exists() shouldBe true

                        advanceTimeBy(2.minutes)
                        waitUntil { !second.exists() }
                        waitUntil { cleanups.all().isEmpty() }

                        job.cancelAndJoin()
                    }
                }
            }
        }
    })
