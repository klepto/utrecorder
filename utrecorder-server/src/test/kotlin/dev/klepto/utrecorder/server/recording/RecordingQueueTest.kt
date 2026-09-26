package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecordingQueueTest :
    FunSpec({
        fun entry(
            id: Long,
            lane: RecordingLane,
        ): Pair<Demo, Recording> =
            Demo(
                id = id,
                sourceId = "source-$id",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83.seconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
                recordingId = id,
            ) to Recording(id = id, lane = lane)

        test("a lane takes the oldest recording of its own lane") {
            runTest {
                val queue = RecordingQueue()
                queue.enqueue(entry(1, RecordingLane.SHORT))
                queue.enqueue(entry(2, RecordingLane.LONG))
                queue.enqueue(entry(3, RecordingLane.SHORT))

                queue.take(RecordingLane.LONG).second.id shouldBe 2
                queue.take(RecordingLane.SHORT).second.id shouldBe 1
                queue.take(RecordingLane.SHORT).second.id shouldBe 3
            }
        }

        test("a lane suspends until a recording of its own lane is enqueued") {
            runTest {
                val queue = RecordingQueue()
                val taken = async { queue.take(RecordingLane.LONG) }
                runCurrent()
                taken.isCompleted shouldBe false

                queue.enqueue(entry(1, RecordingLane.SHORT))
                runCurrent()
                taken.isCompleted shouldBe false

                queue.enqueue(entry(2, RecordingLane.LONG))
                taken.await().second.id shouldBe 2
                queue.take(RecordingLane.SHORT).second.id shouldBe 1
            }
        }
    })
