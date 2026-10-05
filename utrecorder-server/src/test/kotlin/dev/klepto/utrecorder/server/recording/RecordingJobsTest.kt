package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage.Capturing
import dev.klepto.utrecorder.core.RecorderStage.FetchingDemo
import dev.klepto.utrecorder.server.demo.Demo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordingJobsTest :
    FunSpec({
        fun recording(capId: String): Recording =
            Recording(
                Demo(
                    capId = capId,
                    map = "CTF-BT-Maverick",
                    player = "klepto",
                    capTime = 10.seconds,
                    timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                    demoUrl = "http://api.utbt.net/demos/$capId",
                ),
            )

        test("lists a job at its first attempt without progress while its block runs") {
            val jobs = RecordingJobs()

            jobs.track(recording("cap")) {
                val job = jobs.jobs.first().single()
                job.recording.demo.capId shouldBe "cap"
                job.attempt shouldBe 1
                job.progress shouldBe null
            }
        }

        test("lists the jobs of nested blocks apart") {
            val jobs = RecordingJobs()

            jobs.track(recording("first")) {
                jobs.track(recording("second")) {
                    jobs.jobs.first().map { it.recording.demo.capId } shouldContainExactlyInAnyOrder listOf("first", "second")
                }
                jobs.jobs.first().map { it.recording.demo.capId } shouldBe listOf("first")
            }
        }

        test("sets reported progress, and clears it when a new attempt starts") {
            val jobs = RecordingJobs()

            jobs.track(recording("cap")) { tracker ->
                tracker.report(Progress(Capturing, 0.5))
                val reported = jobs.jobs.first().single()
                reported.progress shouldBe Progress(Capturing, 0.5)

                tracker.startAttempt(2)
                val retried = jobs.jobs.first().single()
                retried.attempt shouldBe 2
                retried.progress shouldBe null
            }
        }

        test("removes the job when its block throws, and ignores updates after the block ends") {
            val jobs = RecordingJobs()
            lateinit var escaped: RecordingJobs.Tracker

            shouldThrow<IllegalStateException> {
                jobs.track(recording("cap")) {
                    escaped = it
                    error("Recording failed")
                }
            }
            escaped.report(Progress(FetchingDemo, 0.5))

            jobs.jobs.first().shouldBeEmpty()
        }
    })
