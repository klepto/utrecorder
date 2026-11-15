package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BacklogTest :
    FunSpec({
        val home = tempdir().toPath()
        val timestamp = Instant.parse("2026-09-20T12:00:00Z")
        val config =
            ServerConfig(
                ConfigReader.EMPTY,
                DirectoriesConfig(ConfigReader.EMPTY, home = home),
                recordingRecentAge = 1.days,
                archiveDelay = 2.days,
            )

        fun recording(
            recordedAfter: Duration?,
            status: RecordingStatus = RecordingStatus.Recorded,
        ): Recording =
            Recording(
                demo =
                    Demo(
                        capId = "cap",
                        map = "CTF-BT-Maverick",
                        player = "klepto",
                        userId = "1234",
                        capTime = 10.seconds,
                        timestamp = timestamp,
                        demoUrl = "http://api.utbt.net/demos/cap",
                    ),
                status = status,
                recordedAt = recordedAfter?.let { timestamp + it },
            )

        test("a recording made more than the recent age after its demo is a backlog recording") {
            recording(1.days + 1.nanoseconds).isBacklog(1.days) shouldBe true
            recording(3.days).isBacklog(1.days) shouldBe true
        }

        test("a recording made exactly the recent age after its demo is not a backlog recording") {
            recording(1.days).isBacklog(1.days) shouldBe false
        }

        test("a recording made within the recent age of its demo is not a backlog recording") {
            recording(1.hours).isBacklog(1.days) shouldBe false
        }

        test("a recording without a recording time is never a backlog recording") {
            recording(null).isBacklog(1.days) shouldBe false
            recording(null).isBacklog(Duration.ZERO) shouldBe false
        }

        test("the age of the demo is measured when it was recorded, not now") {
            recording(1.hours).isBacklog(1.days) shouldBe false
            recording(1.hours).isUploadDue(config, timestamp + 10.days) shouldBe true
            recording(1.hours).isUploadDue(config, timestamp + 1.days) shouldBe false
        }

        test("the upload of a backlog recording is due as soon as it is recorded") {
            val backlog = recording(3.days)

            backlog.isUploadDue(config, timestamp + 3.days) shouldBe true
        }

        test("the upload of a recent recording is due once the archive delay has passed since it was recorded") {
            val recent = recording(1.hours)
            val recordedAt = timestamp + 1.hours

            recent.isUploadDue(config, recordedAt) shouldBe false
            recent.isUploadDue(config, recordedAt + 2.days - 1.nanoseconds) shouldBe false
            recent.isUploadDue(config, recordedAt + 2.days) shouldBe true
        }

        test("a recording made exactly the recent age after its demo waits for the archive delay") {
            val boundary = recording(1.days)
            val recordedAt = timestamp + 1.days

            boundary.isUploadDue(config, recordedAt) shouldBe false
            boundary.isUploadDue(config, recordedAt + 2.days) shouldBe true
        }

        test("the upload of a recording without a recording time is due at once") {
            recording(null).isUploadDue(config, timestamp) shouldBe true
        }

        test("the upload rule does not look at the status") {
            recording(3.days, Archived).isUploadDue(config, timestamp + 3.days) shouldBe true
            recording(1.hours, Pending).isUploadDue(config, timestamp + 1.hours) shouldBe false
        }
    })
