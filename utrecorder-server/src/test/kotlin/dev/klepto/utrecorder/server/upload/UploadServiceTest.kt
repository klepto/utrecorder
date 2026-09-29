package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Pending
import dev.klepto.utrecorder.server.recording.RecordingStatus.Recorded
import dev.klepto.utrecorder.server.recording.RecordingStatus.Uploaded
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class UploadServiceTest :
    FunSpec({
        val root = tempdir().toPath()
        val now = AtomicReference(Instant.parse("2026-09-27T08:00:00Z"))
        val clock =
            object : Clock {
                override fun now(): Instant = now.get()
            }

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

        fun uploader(
            isUploaded: suspend (String, String) -> Boolean = { _, _ -> false },
            upload: suspend (String, Path, MediaMetadata) -> Unit = { _, _, _ -> },
        ): MediaUploader =
            object : MediaUploader {
                override suspend fun upload(
                    id: String,
                    file: Path,
                    metadata: MediaMetadata,
                ) = upload(id, file, metadata)

                override suspend fun isUploaded(
                    id: String,
                    md5: String,
                ): Boolean = isUploaded(id, md5)

                override fun downloadUrl(
                    id: String,
                    name: String,
                ): String = ""
            }

        suspend fun running(
            statuses: Map<String, RecordingStatus>,
            mp4s: List<String>,
            uploader: MediaUploader,
            md5s: Map<String, String> = emptyMap(),
            dailyUploads: Int = 4_320_000,
            block: suspend (RecordingRepository) -> Unit,
        ) {
            val recordings = createTempDirectory(root, "recordings")
            mp4s.forEach { recordings.resolve("$it.mp4").writeText(it) }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    recordings = recordings,
                    uploadInterval = 20.milliseconds,
                    dailyUploads = dailyUploads,
                )
            DocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val repository = RecordingRepository(store, config.recording, clock)
                repository += statuses.keys.mapIndexed { minute, capId -> demo(capId, minute) }
                statuses.forEach { (capId, status) -> repository.update(capId) { it.copy(status = status, md5 = md5s[capId]) } }
                coroutineScope {
                    val job = launch { UploadService(repository, uploader, config, clock).start() }
                    block(repository)
                    job.cancelAndJoin()
                }
            }
        }

        test("uploads every recorded mp4 one at a time, newest demo first") {
            val uploads = CopyOnWriteArrayList<String>()
            val active = AtomicInteger()
            val overlapped = AtomicInteger()
            val uploader =
                uploader { id, file, _ ->
                    if (active.incrementAndGet() > 1) overlapped.incrementAndGet()
                    delay(10.milliseconds)
                    uploads += "$id:${file.name}"
                    active.decrementAndGet()
                }
            running(mapOf("b" to Recorded, "a" to Recorded), listOf("a", "b"), uploader) { repository ->
                eventually(5.seconds) { repository["b"]?.status shouldBe Uploaded }
                repository["a"]?.status shouldBe Uploaded
            }

            uploads shouldContainExactly listOf("a:a.mp4", "b:b.mp4")
            overlapped.get() shouldBe 0
        }

        test("uploads a recording of a newer demo before the older ones still waiting") {
            val uploads = CopyOnWriteArrayList<String>()
            running(
                mapOf("oldest" to Recorded, "older" to Recorded, "old" to Recorded),
                listOf("oldest", "older", "old", "fresh"),
                uploader { id, _, _ -> uploads += id },
                dailyUploads = 432_000,
            ) { repository ->
                eventually(5.seconds) { uploads shouldContainExactly listOf("old") }
                repository += listOf(demo("fresh", 10))
                repository.update("fresh") { it.copy(status = Recorded) }
                eventually(5.seconds) { repository["oldest"]?.status shouldBe Uploaded }
            }

            uploads shouldContainExactly listOf("old", "fresh", "older", "oldest")
        }

        test("starts two uploads one day divided by the daily uploads apart") {
            val starts = CopyOnWriteArrayList<TimeSource.Monotonic.ValueTimeMark>()
            val uploader = uploader { _, _, _ -> starts += TimeSource.Monotonic.markNow() }
            running(
                mapOf("a" to Recorded, "b" to Recorded, "c" to Recorded),
                listOf("a", "b", "c"),
                uploader,
                dailyUploads = 432_000,
            ) { repository ->
                eventually(5.seconds) { repository["a"]?.status shouldBe Uploaded }
            }

            starts
                .zipWithNext { first, second -> second - first }
                .forEach { it shouldBeGreaterThanOrEqualTo 200.milliseconds }
        }

        test("checks uploaded recordings while waiting for the next upload") {
            val uploader = uploader(isUploaded = { id, _ -> id == "old" })
            running(
                mapOf("new" to Recorded, "old" to Uploaded),
                listOf("new"),
                uploader,
                mapOf("old" to "md5"),
                dailyUploads = 1,
            ) { repository ->
                eventually(5.seconds) { repository["new"]?.status shouldBe Uploaded }
                eventually(5.seconds) { repository["old"]?.status shouldBe Archived }
            }
        }

        test("skips a recording that is not recorded or whose mp4 is missing") {
            val uploads = CopyOnWriteArrayList<String>()
            val statuses = mapOf("pending" to Pending, "failed" to Failed, "missing" to Recorded, "cap" to Recorded)
            running(statuses, listOf("pending", "failed", "cap"), uploader { id, _, _ -> uploads += id }) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
                repository["missing"]?.status shouldBe Recorded
            }

            uploads shouldContainExactly listOf("cap")
        }

        test("leaves a failed upload recorded and uploads it again on a later pass") {
            val attempts = AtomicInteger()
            val uploader = uploader { _, _, _ -> if (attempts.incrementAndGet() == 1) throw IOException("Unavailable") }
            running(mapOf("cap" to Recorded), listOf("cap"), uploader) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
            }

            attempts.get() shouldBe 2
        }

        test("does not upload an uploaded recording again") {
            val uploads = CopyOnWriteArrayList<String>()
            running(mapOf("cap" to Recorded), listOf("cap"), uploader { id, _, _ -> uploads += id }) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
            }

            uploads shouldContainExactly listOf("cap")
        }

        test("stores the MD5 checksum of the uploaded mp4") {
            running(mapOf("cap" to Recorded), listOf("cap"), uploader()) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                repository["cap"]?.md5 shouldBe "3d791e43c8d5561e36bd09bacc097517"
            }
        }

        test("archives an uploaded recording once its checksum is confirmed") {
            val checks = CopyOnWriteArrayList<String>()
            val confirmed = AtomicInteger()
            val uploader =
                uploader(isUploaded = { id, md5 ->
                    checks += "$id:$md5"
                    confirmed.incrementAndGet() > 1
                })
            running(mapOf("cap" to Uploaded), emptyList(), uploader, mapOf("cap" to "3d791e43c8d5561e36bd09bacc097517")) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Archived }
                delay(100.milliseconds)
            }

            checks shouldContainExactly List(2) { "cap:3d791e43c8d5561e36bd09bacc097517" }
        }

        test("leaves an uploaded recording uploaded when its check fails") {
            val checks = AtomicInteger()
            val uploader =
                uploader(isUploaded = { _, _ ->
                    checks.incrementAndGet()
                    throw IOException("Unavailable")
                })
            running(mapOf("cap" to Uploaded), emptyList(), uploader, mapOf("cap" to "md5")) { repository ->
                eventually(5.seconds) { checks.get() shouldBeGreaterThanOrEqual 2 }
                repository["cap"]?.status shouldBe Uploaded
            }
        }

        test("uploads an unconfirmed recording again once 24 hours have passed since it was uploaded") {
            now.set(Instant.parse("2026-09-27T08:00:00Z"))
            val uploads = CopyOnWriteArrayList<String>()
            val checks = AtomicInteger()
            val uploader =
                uploader(isUploaded = { _, _ ->
                    checks.incrementAndGet()
                    false
                }) { id, _, _ -> uploads += id }
            running(mapOf("cap" to Uploaded), listOf("cap"), uploader, mapOf("cap" to "md5")) { repository ->
                now.set(Instant.parse("2026-09-27T08:00:00Z") + 24.hours - 1.minutes)
                val seen = checks.get()
                eventually(5.seconds) { checks.get() shouldBeGreaterThanOrEqual seen + 2 }
                uploads.shouldBeEmpty()

                now.set(Instant.parse("2026-09-27T08:00:00Z") + 24.hours)
                eventually(5.seconds) { uploads shouldContainExactly listOf("cap") }
                eventually(5.seconds) { repository["cap"]?.lastUpdate shouldBe Instant.parse("2026-09-28T08:00:00Z") }
                repository["cap"]?.status shouldBe Uploaded
            }
        }

        test("archives a recording without uploading it when its mp4 is already uploaded") {
            val uploads = CopyOnWriteArrayList<String>()
            val uploader =
                uploader(isUploaded = { id, md5 -> id == "uploaded" && md5 == "bf5f3461956c8f4ab9d7c0877c1505ad" }) { id, _, _ ->
                    uploads += id
                }
            running(mapOf("cap" to Recorded, "uploaded" to Recorded), listOf("cap", "uploaded"), uploader, dailyUploads = 1) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                repository["uploaded"]?.status shouldBe Archived
                repository["uploaded"]?.md5 shouldBe "bf5f3461956c8f4ab9d7c0877c1505ad"
            }

            uploads shouldContainExactly listOf("cap")
        }

        test("leaves a recording recorded when the check before its upload fails") {
            val uploads = CopyOnWriteArrayList<String>()
            val uploader = uploader(isUploaded = { _, _ -> throw IOException("Unavailable") }) { id, _, _ -> uploads += id }
            running(mapOf("cap" to Recorded), listOf("cap"), uploader) { repository ->
                delay(100.milliseconds)
                repository["cap"]?.status shouldBe Recorded
            }

            uploads.shouldBeEmpty()
        }

        test("describes the upload with the player, map and cap time of its demo") {
            val metadata = CopyOnWriteArrayList<MediaMetadata>()
            running(mapOf("cap" to Recorded), listOf("cap"), uploader { _, _, it -> metadata += it }) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
            }

            metadata shouldContainExactly
                listOf(
                    MediaMetadata(
                        "CTF-BT-Maverick - 10.000 by klepto",
                        "Unreal Tournament 99 Speedrunning\nPlayer: klepto\nMap: CTF-BT-Maverick\nTime: 10.000",
                        listOf("Video Games", "Unreal Tournament 99", "Speedrunning", "Bunny Track"),
                    ),
                )
        }
    })
