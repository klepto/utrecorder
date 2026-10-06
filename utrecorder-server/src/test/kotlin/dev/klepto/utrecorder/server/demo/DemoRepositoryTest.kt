package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
    })
