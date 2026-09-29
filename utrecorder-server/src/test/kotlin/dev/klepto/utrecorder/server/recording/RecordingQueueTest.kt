package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Unresolvable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordingQueueTest :
    FunSpec({
        fun demo(
            capId: String,
            minute: Int,
            capTime: Duration = 10.seconds,
        ): Demo =
            Demo(
                capId = capId,
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = capTime,
                timestamp = Instant.parse("2026-09-26T12:00:00Z") + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        fun file(): Path = createTempDirectory("utrecorder-recording-queue-test").resolve("server.db")

        suspend fun <R> queue(
            file: Path = file(),
            block: suspend (RecordingQueue) -> R,
        ): R = DocumentStore.open(file) { block(RecordingQueue(it)) }

        test("stores a pending recording of an added demo and skips a demo already stored") {
            val entry =
                queue {
                    it += listOf(demo("cap", 0))
                    it.finish("cap", Recorded)
                    it += listOf(demo("cap", 0))
                    it["cap"]
                }
            entry shouldBe Recording(demo("cap", 0), Recorded)
        }

        test("returns the times of the newest and the oldest demo") {
            val times =
                queue {
                    it += listOf(demo("b", 1), demo("c", 2), demo("a", 0))
                    it.newest() to it.oldest()
                }
            times shouldBe (Instant.parse("2026-09-26T12:02:00Z") to Instant.parse("2026-09-26T12:00:00Z"))
        }

        test("takes the demo of the newest accepted pending recording and sets it to recording") {
            val taken =
                queue { queue ->
                    queue += listOf(demo("older", 0), demo("long", 2, 10.minutes), demo("newer", 1))
                    val first = queue.take { it.capTime < 1.minutes }
                    val second = queue.take { it.capTime < 1.minutes }
                    listOf(first.capId, second.capId, queue["newer"]?.status)
                }
            taken shouldBe listOf("newer", "older", RecordingStatus.Recording)
        }

        test("suspends a take until an accepted demo is added") {
            val taken =
                queue { queue ->
                    coroutineScope {
                        val take = async { queue.take { it.capId == "wanted" } }
                        delay(50.milliseconds)
                        queue += listOf(demo("other", 0))
                        delay(50.milliseconds)
                        take.isActive shouldBe true
                        queue += listOf(demo("wanted", 1))
                        take.await().capId
                    }
                }
            taken shouldBe "wanted"
        }

        test("keeps the error of a finished recording") {
            val entry =
                queue {
                    it += listOf(demo("cap", 0))
                    it.finish("cap", Unresolvable, "Missing BTPog")
                    it["cap"]
                }
            entry shouldBe Recording(demo("cap", 0), Unresolvable, "Missing BTPog")
        }

        test("sets recording and failed recordings to pending when opened again, and keeps the rest") {
            val file = file()
            queue(file) {
                it += listOf(demo("recording", 0), demo("failed", 1), demo("recorded", 2), demo("unresolvable", 3))
                it.take { demo -> demo.capId == "recording" }
                it.finish("failed", Failed, "Capture failed")
                it.finish("recorded", Recorded)
                it.finish("unresolvable", Unresolvable, "Missing BTPog")
            }

            val statuses = queue(file) { queue -> listOf("recording", "failed", "recorded", "unresolvable").map { queue[it] } }

            statuses shouldBe
                listOf(
                    Recording(demo("recording", 0), Pending),
                    Recording(demo("failed", 1), Pending, "Capture failed"),
                    Recording(demo("recorded", 2), Recorded),
                    Recording(demo("unresolvable", 3), Unresolvable, "Missing BTPog"),
                )
        }
    })
