package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DemoRepositoryTest :
    FunSpec({
        fun demo(
            capId: String,
            minute: Int,
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = Instant.parse("2026-09-26T12:00:00Z") + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        suspend fun <R> repository(block: suspend (DemoRepository) -> R): R =
            SqliteDocumentStore.open(createTempDirectory("utrecorder-demo-repository-test").resolve("server.db")) {
                block(DemoRepository(it))
            }

        test("stores the added demos in the order given and skips a demo already stored") {
            val demos =
                repository {
                    it += listOf(demo("b", 1), demo("a", 0))
                    it += listOf(demo("a", 5), demo("c", 2))
                    it.all()
                }
            demos shouldBe listOf(demo("b", 1), demo("a", 0), demo("c", 2))
        }

        test("signals a change once when collected and again after new demos are stored") {
            val changes =
                repository { repository ->
                    coroutineScope {
                        val changes = async { repository.changes.take(2).toList() }
                        delay(50.milliseconds)
                        repository += listOf(demo("a", 0))
                        changes.await()
                    }
                }
            changes shouldBe listOf(Unit, Unit)
        }

        test("reports no withdrawn demo when new") {
            val withdrawn =
                repository {
                    it += listOf(demo("a", 0))
                    it.withdrawn()
                }
            withdrawn shouldBe emptySet()
        }

        test("withdraws every stored demo not seen, restores every stored demo seen and ignores unknown cap ids") {
            val withdrawn =
                repository {
                    it += listOf(demo("a", 0), demo("b", 1), demo("c", 2))
                    it.withdrawAllExcept(setOf("a", "x"))
                    val first = it.withdrawn()
                    it.withdrawAllExcept(setOf("b", "c", "y"))
                    first to it.withdrawn()
                }
            withdrawn shouldBe (setOf("b", "c") to setOf("a"))
        }

        test("restores the given withdrawn demos and ignores cap ids that are not withdrawn") {
            val withdrawn =
                repository {
                    it += listOf(demo("a", 0), demo("b", 1), demo("c", 2))
                    it.withdrawAllExcept(setOf("a"))
                    it.restore(listOf("b", "a", "x"))
                    it.withdrawn()
                }
            withdrawn shouldBe setOf("c")
        }

        test("keeps a withdrawn demo withdrawn when it is added again") {
            val withdrawn =
                repository {
                    it += listOf(demo("a", 0), demo("b", 1))
                    it.withdrawAllExcept(setOf("b"))
                    it += listOf(demo("a", 0), demo("c", 2))
                    it.withdrawn()
                }
            withdrawn shouldBe setOf("a")
        }

        test("reports the same withdrawn demos when opened again over the same store") {
            val file = createTempDirectory("utrecorder-demo-repository-test").resolve("server.db")
            SqliteDocumentStore.open(file) {
                val repository = DemoRepository(it)
                repository += listOf(demo("a", 0), demo("b", 1), demo("c", 2))
                repository.withdrawAllExcept(setOf("b"))
            }
            val withdrawn = SqliteDocumentStore.open(file) { DemoRepository(it).withdrawn() }
            withdrawn shouldBe setOf("a", "c")
        }

        test("signals a change after a call that changes the withdrawn demos, and none after a call that does not") {
            val counts =
                repository { repository ->
                    repository += listOf(demo("a", 0), demo("b", 1))
                    coroutineScope {
                        val changes = mutableListOf<Unit>()
                        val collector = launch { repository.changes.collect { changes += it } }
                        delay(50.milliseconds)
                        repository.restore(listOf("a"))
                        repository.withdrawAllExcept(setOf("a", "b"))
                        delay(50.milliseconds)
                        val unchanged = changes.size
                        repository.withdrawAllExcept(setOf("a"))
                        delay(50.milliseconds)
                        val withdrawn = changes.size
                        repository.restore(listOf("b"))
                        delay(50.milliseconds)
                        collector.cancel()
                        listOf(unchanged, withdrawn, changes.size)
                    }
                }
            counts shouldBe listOf(1, 2, 3)
        }
    })
