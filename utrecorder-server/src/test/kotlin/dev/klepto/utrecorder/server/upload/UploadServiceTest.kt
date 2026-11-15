package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingStatus
import dev.klepto.utrecorder.server.recording.RecordingStatus.Archived
import dev.klepto.utrecorder.server.recording.RecordingStatus.Failed
import dev.klepto.utrecorder.server.recording.RecordingStatus.Outdated
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class UploadServiceTest :
    FunSpec({
        val root = tempdir().toPath()
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root.resolve("home"))
        val now = AtomicReference(Instant.parse("2026-09-27T08:00:00Z"))
        val clock =
            object : Clock {
                override fun now(): Instant = now.get()
            }

        val item = "1234-4fa8c645-ctf-bt-maverick"

        fun demo(
            capId: String,
            minute: Int,
            map: String = "CTF-BT-Maverick",
            userId: String = "1234",
        ): Demo =
            Demo(
                capId = capId,
                map = map,
                player = "klepto",
                userId = userId,
                capTime = 10.seconds,
                timestamp = Instant.parse("2026-09-26T12:00:00Z") + minute.minutes,
                demoUrl = "http://api.utbt.net/demos/$capId",
            )

        fun uploader(
            isUploaded: suspend (String, String) -> Boolean = { _, _ -> false },
            describe: suspend (String, MediaMetadata) -> Unit = { _, _ -> },
            delete: suspend (String, String) -> Unit = { _, _ -> },
            size: suspend (String, String) -> Long? = { _, _ -> null },
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

                override suspend fun describe(
                    id: String,
                    metadata: MediaMetadata,
                ) = describe(id, metadata)

                override suspend fun delete(
                    id: String,
                    name: String,
                ) = delete(id, name)

                override suspend fun size(
                    id: String,
                    name: String,
                ): Long? = size(id, name)

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
            archiveDelay: Duration = Duration.ZERO,
            demos: List<Demo> = statuses.keys.mapIndexed { minute, capId -> demo(capId, minute) },
            seed: (Recording) -> Recording = { it },
            timeSource: TimeSource = TimeSource.Monotonic,
            block: suspend (RecordingRepository) -> Unit,
        ) {
            val recordings = createTempDirectory(root, "recordings")
            mp4s.forEach { recordings.resolve("$it.mp4").writeText(it) }
            val config =
                ServerConfig(
                    ConfigReader.EMPTY,
                    directories,
                    recordings = recordings,
                    uploadInterval = 20.milliseconds,
                    dailyUploads = dailyUploads,
                    archiveDelay = archiveDelay,
                )
            SqliteDocumentStore.open(createTempDirectory(root, "database").resolve("server.db")) { store ->
                val repository = RecordingRepository(store, DemoRepository(store), clock)
                repository += demos
                statuses.forEach { (capId, status) -> repository.update(capId) { seed(it.copy(status = status, md5 = md5s[capId])) } }
                coroutineScope {
                    val job = launch { UploadService(repository, uploader, config, clock, timeSource).start() }
                    block(repository)
                    job.cancelAndJoin()
                }
            }
        }

        test("uploads every recorded mp4 one at a time") {
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
                eventually(5.seconds) { repository["a"]?.status shouldBe Uploaded }
                repository["b"]?.status shouldBe Uploaded
            }

            uploads shouldContainExactly listOf("$item:b.mp4", "$item:a.mp4")
            overlapped.get() shouldBe 0
        }

        test("uploads the recording recorded first, whatever the age of its demo") {
            val uploads = CopyOnWriteArrayList<String>()
            val recordedAt =
                mapOf(
                    "first" to Instant.parse("2026-09-27T07:00:00Z"),
                    "second" to Instant.parse("2026-09-27T07:01:00Z"),
                    "third" to Instant.parse("2026-09-27T07:02:00Z"),
                )
            running(
                mapOf("second" to Recorded, "third" to Recorded, "first" to Recorded),
                listOf("first", "second", "third", "late"),
                uploader { _, file, _ -> uploads += file.name.removeSuffix(".mp4") },
                dailyUploads = 432_000,
                seed = { it.copy(recordedAt = recordedAt[it.demo.capId]) },
            ) { repository ->
                eventually(5.seconds) { uploads shouldContainExactly listOf("first") }
                repository += listOf(demo("late", -10))
                repository.update("late") { it.copy(status = Recorded, recordedAt = Instant.parse("2026-09-27T07:03:00Z")) }
                eventually(5.seconds) { repository["late"]?.status shouldBe Uploaded }
            }

            uploads shouldContainExactly listOf("first", "second", "third", "late")
        }

        test("starts two uploads one day divided by the daily uploads apart") {
            val marks = CopyOnWriteArrayList<TimeSource.Monotonic.ValueTimeMark>()
            val timeSource =
                object : TimeSource {
                    override fun markNow(): TimeMark = TimeSource.Monotonic.markNow().also { marks += it }
                }
            val uploads = CopyOnWriteArrayList<Pair<TimeSource.Monotonic.ValueTimeMark, TimeSource.Monotonic.ValueTimeMark>>()
            val uploader = uploader { _, _, _ -> uploads += marks.last() to TimeSource.Monotonic.markNow() }
            running(
                mapOf("a" to Recorded, "b" to Recorded, "c" to Recorded),
                listOf("a", "b", "c"),
                uploader,
                dailyUploads = 432_000,
                timeSource = timeSource,
            ) { repository ->
                eventually(5.seconds) { repository["c"]?.status shouldBe Uploaded }
            }

            // Measured from the mark the service takes before it picks and hashes an mp4, not from the upload itself.
            uploads
                .zipWithNext { (marked, _), (_, started) -> started - marked }
                .forEach { it shouldBeGreaterThanOrEqualTo 200.milliseconds }
        }

        test("checks uploaded recordings while waiting for the next upload") {
            val uploader = uploader(isUploaded = { id, md5 -> id == item && md5 == "md5" })
            running(
                mapOf("new" to Recorded, "old" to Uploaded),
                listOf("new"),
                uploader,
                mapOf("old" to "md5"),
                dailyUploads = 1,
                seed = { if (it.status == Uploaded) it.copy(itemId = item) else it },
            ) { repository ->
                eventually(5.seconds) { repository["new"]?.status shouldBe Uploaded }
                eventually(5.seconds) { repository["old"]?.status shouldBe Archived }
            }
        }

        test("skips a recording that is not recorded or whose mp4 is missing") {
            val uploads = CopyOnWriteArrayList<String>()
            val statuses = mapOf("pending" to Pending, "failed" to Failed, "missing" to Recorded, "cap" to Recorded)
            running(statuses, listOf("pending", "failed", "cap"), uploader { _, file, _ -> uploads += file.name }) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
                repository["missing"]?.status shouldBe Recorded
            }

            uploads shouldContainExactly listOf("cap.mp4")
        }

        test("leaves a failed upload recorded and uploads it again after the recordings whose upload did not fail") {
            val attempts = CopyOnWriteArrayList<String>()
            val uploader =
                uploader { _, file, _ ->
                    attempts += file.name
                    if (attempts.size == 1) throw IOException("Unavailable")
                }
            running(mapOf("cap" to Recorded, "next" to Recorded), listOf("cap", "next"), uploader) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                repository["next"]?.status shouldBe Uploaded
            }

            attempts shouldContainExactly listOf("cap.mp4", "next.mp4", "cap.mp4")
        }

        test("does not upload an uploaded recording again") {
            val uploads = CopyOnWriteArrayList<String>()
            running(mapOf("cap" to Recorded), listOf("cap"), uploader { _, file, _ -> uploads += file.name }) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
            }

            uploads shouldContainExactly listOf("cap.mp4")
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
            running(
                mapOf("cap" to Uploaded),
                emptyList(),
                uploader,
                mapOf("cap" to "3d791e43c8d5561e36bd09bacc097517"),
                seed = { it.copy(itemId = item) },
            ) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Archived }
                delay(100.milliseconds)
            }

            checks shouldContainExactly List(2) { "$item:3d791e43c8d5561e36bd09bacc097517" }
        }

        test("leaves an uploaded recording uploaded when its check fails") {
            val checks = AtomicInteger()
            val uploader =
                uploader(isUploaded = { _, _ ->
                    checks.incrementAndGet()
                    throw IOException("Unavailable")
                })
            running(
                mapOf("cap" to Uploaded),
                emptyList(),
                uploader,
                mapOf("cap" to "md5"),
                seed = { it.copy(itemId = item) },
            ) { repository ->
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
                }) { _, file, _ -> uploads += file.name }
            running(
                mapOf("cap" to Uploaded),
                listOf("cap"),
                uploader,
                mapOf("cap" to "md5"),
                seed = { it.copy(itemId = item) },
            ) { repository ->
                now.set(Instant.parse("2026-09-27T08:00:00Z") + 24.hours - 1.minutes)
                val seen = checks.get()
                eventually(5.seconds) { checks.get() shouldBeGreaterThanOrEqual seen + 2 }
                uploads.shouldBeEmpty()

                now.set(Instant.parse("2026-09-27T08:00:00Z") + 24.hours)
                eventually(5.seconds) { uploads shouldContainExactly listOf("cap.mp4") }
                eventually(5.seconds) { repository["cap"]?.lastUpdate shouldBe Instant.parse("2026-09-28T08:00:00Z") }
                repository["cap"]?.status shouldBe Uploaded
            }
        }

        test("archives a recording without uploading it when its mp4 is already uploaded") {
            val uploads = CopyOnWriteArrayList<String>()
            val uploader =
                uploader(isUploaded = { _, md5 -> md5 == "bf5f3461956c8f4ab9d7c0877c1505ad" }) { _, file, _ ->
                    uploads += file.name
                }
            running(mapOf("uploaded" to Recorded, "cap" to Recorded), listOf("cap", "uploaded"), uploader, dailyUploads = 1) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                repository["uploaded"]?.status shouldBe Archived
                repository["uploaded"]?.md5 shouldBe "bf5f3461956c8f4ab9d7c0877c1505ad"
            }

            uploads shouldContainExactly listOf("cap.mp4")
        }

        test("leaves a recording recorded when the check before its upload fails") {
            val uploads = CopyOnWriteArrayList<String>()
            val uploader = uploader(isUploaded = { _, _ -> throw IOException("Unavailable") }) { _, file, _ -> uploads += file.name }
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

        test("uploads a recording only once the archive delay has passed since it was recorded") {
            now.set(Instant.parse("2026-09-27T08:00:00Z"))
            val uploads = CopyOnWriteArrayList<String>()
            running(
                mapOf("delayed" to Recorded, "unstamped" to Recorded),
                listOf("delayed", "unstamped"),
                uploader { _, file, _ -> uploads += file.name },
                archiveDelay = 1.hours,
                seed = { if (it.demo.capId == "delayed") it.copy(recordedAt = Instant.parse("2026-09-27T07:01:00Z")) else it },
            ) { repository ->
                eventually(5.seconds) { repository["unstamped"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
                repository["delayed"]?.status shouldBe Recorded

                now.set(Instant.parse("2026-09-27T08:01:00Z"))
                eventually(5.seconds) { repository["delayed"]?.status shouldBe Uploaded }
            }

            uploads shouldContainExactly listOf("unstamped.mp4", "delayed.mp4")
        }

        test("uploads a backlog recording at once, while a recent one recorded at the same time waits for the archive delay") {
            now.set(Instant.parse("2026-09-27T08:00:00Z"))
            val uploads = CopyOnWriteArrayList<String>()
            running(
                mapOf("recent" to Recorded, "backlog" to Recorded),
                listOf("recent", "backlog"),
                uploader { _, file, _ -> uploads += file.name },
                archiveDelay = 1.hours,
                demos = listOf(demo("recent", 0), demo("backlog", -3 * 24 * 60)),
                seed = { it.copy(recordedAt = Instant.parse("2026-09-27T07:59:00Z")) },
            ) { repository ->
                eventually(5.seconds) { repository["backlog"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
                repository["recent"]?.status shouldBe Recorded

                now.set(Instant.parse("2026-09-27T08:59:00Z"))
                eventually(5.seconds) { repository["recent"]?.status shouldBe Uploaded }
            }

            uploads shouldContainExactly listOf("backlog.mp4", "recent.mp4")
        }

        test("keeps the archive delay for a recording whose demo was recent when it was recorded, however old the demo is now") {
            now.set(Instant.parse("2026-09-28T08:00:00Z"))
            val uploads = CopyOnWriteArrayList<String>()
            running(
                mapOf("aged" to Recorded),
                listOf("aged"),
                uploader { _, file, _ -> uploads += file.name },
                archiveDelay = 2.days,
                seed = { it.copy(recordedAt = Instant.parse("2026-09-26T13:00:00Z")) },
            ) { repository ->
                delay(100.milliseconds)
                repository["aged"]?.status shouldBe Recorded

                now.set(Instant.parse("2026-09-28T13:00:00Z"))
                eventually(5.seconds) { repository["aged"]?.status shouldBe Uploaded }
            }

            uploads shouldContainExactly listOf("aged.mp4")
        }

        test("on its first pass, uploads the oldest of the backlog recordings found on disk at boot") {
            now.set(Instant.parse("2026-09-27T08:00:00Z"))
            val uploads = CopyOnWriteArrayList<String>()
            val recordedAt =
                mapOf(
                    "second" to Instant.parse("2026-09-27T07:52:00Z"),
                    "oldest" to Instant.parse("2026-09-27T07:50:00Z"),
                    "third" to Instant.parse("2026-09-27T07:55:00Z"),
                )
            running(
                mapOf("second" to Recorded, "oldest" to Recorded, "third" to Recorded),
                listOf("second", "oldest", "third"),
                uploader { _, file, _ -> uploads += file.name },
                dailyUploads = 1,
                archiveDelay = 2.days,
                demos = recordedAt.keys.mapIndexed { index, capId -> demo(capId, -5 * 24 * 60 + index) },
                seed = { it.copy(recordedAt = recordedAt[it.demo.capId]) },
            ) { repository ->
                eventually(5.seconds) { repository["oldest"]?.status shouldBe Uploaded }
                delay(100.milliseconds)
                repository["second"]?.status shouldBe Recorded
                repository["third"]?.status shouldBe Recorded
            }

            uploads shouldContainExactly listOf("oldest.mp4")
        }

        test("names the item by the user id, a hash of the exact map name and the map slug") {
            val uploads = CopyOnWriteArrayList<String>()
            running(
                mapOf("plain" to Recorded, "mixed" to Recorded, "special" to Recorded),
                listOf("plain", "mixed", "special"),
                uploader { id, file, _ -> uploads += "$id:${file.name}" },
                demos =
                    listOf(
                        demo("plain", 0, "ctf-bt-maverick"),
                        demo("mixed", 1, "CTF-BT-Maverick"),
                        demo("special", 2, "CTF-BT-(Joe)Fun[2]", "a b/c"),
                    ),
            ) { repository ->
                eventually(5.seconds) { repository["special"]?.status shouldBe Uploaded }
                repository["plain"]?.itemId shouldBe "1234-3e92a055-ctf-bt-maverick"
                repository["mixed"]?.itemId shouldBe "1234-4fa8c645-ctf-bt-maverick"
                repository["special"]?.itemId shouldBe "a_b_c-f7505019-ctf-bt-_joe_fun_2_"
            }

            uploads shouldContainExactly
                listOf(
                    "1234-3e92a055-ctf-bt-maverick:plain.mp4",
                    "1234-4fa8c645-ctf-bt-maverick:mixed.mp4",
                    "a_b_c-f7505019-ctf-bt-_joe_fun_2_:special.mp4",
                )
        }

        test("describes the item after uploading the mp4 and when the mp4 is already uploaded") {
            val described = CopyOnWriteArrayList<String>()
            val uploader =
                uploader(
                    isUploaded = { _, md5 -> md5 == "bf5f3461956c8f4ab9d7c0877c1505ad" },
                    describe = { id, metadata -> described += "$id:${metadata.title}" },
                )
            running(mapOf("uploaded" to Recorded, "cap" to Recorded), listOf("cap", "uploaded"), uploader) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
                repository["uploaded"]?.status shouldBe Archived
                repository["uploaded"]?.itemId shouldBe item
            }

            described shouldContainExactly List(2) { "$item:CTF-BT-Maverick - 10.000 by klepto" }
        }

        test("leaves a recording recorded when describing its item fails, and describes it again on a later pass") {
            val describes = AtomicInteger()
            val uploader = uploader(describe = { _, _ -> if (describes.incrementAndGet() == 1) throw IOException("Unavailable") })
            running(mapOf("cap" to Recorded), listOf("cap"), uploader) { repository ->
                eventually(5.seconds) { repository["cap"]?.status shouldBe Uploaded }
            }

            describes.get() shouldBe 2
        }

        test("stores only the checksum and the item of an upload that finishes after its recording is outdated") {
            val gate = CompletableDeferred<Unit>()
            val uploads = AtomicInteger()
            val uploader =
                uploader { _, _, _ ->
                    uploads.incrementAndGet()
                    gate.await()
                }
            running(mapOf("cap" to Recorded), listOf("cap"), uploader) { repository ->
                eventually(5.seconds) { uploads.get() shouldBe 1 }
                repository.update("cap") { it.copy(status = Outdated) }
                gate.complete(Unit)
                eventually(5.seconds) { repository["cap"]?.itemId shouldBe item }
                repository["cap"]?.status shouldBe Outdated
                repository["cap"]?.md5 shouldBe "3d791e43c8d5561e36bd09bacc097517"
            }
        }

        test("deletes the files of the outdated recordings of an archived item, retrying a failed delete") {
            val deletes = CopyOnWriteArrayList<String>()
            val uploader =
                uploader(
                    isUploaded = { _, _ -> true },
                    delete = { id, name ->
                        deletes += "$id:$name"
                        if (deletes.size == 1) throw IOException("Unavailable")
                    },
                )
            running(
                mapOf("best" to Uploaded, "beaten" to Outdated, "elsewhere" to Outdated, "pending" to Outdated),
                emptyList(),
                uploader,
                mapOf("best" to "md5"),
                seed = {
                    when (it.demo.capId) {
                        "elsewhere" -> it.copy(itemId = "5678-4fa8c645-ctf-bt-maverick")
                        "pending" -> it
                        else -> it.copy(itemId = item)
                    }
                },
            ) { repository ->
                eventually(5.seconds) { repository["beaten"]?.itemId shouldBe null }
                repository["best"]?.status shouldBe Archived
                repository["elsewhere"]?.itemId shouldBe "5678-4fa8c645-ctf-bt-maverick"
                delay(100.milliseconds)
            }

            deletes shouldContainExactly List(2) { "$item:beaten.mp4" }
        }

        test("sizes each archived recording that has an item and no size, one at a time, then reads no more sizes") {
            val reads = CopyOnWriteArrayList<String>()
            val active = AtomicInteger()
            val overlapped = AtomicInteger()
            val sizes = mapOf("a.mp4" to 100L, "b.mp4" to 200L)
            val uploader =
                uploader(size = { id, name ->
                    if (active.incrementAndGet() > 1) overlapped.incrementAndGet()
                    delay(5.milliseconds)
                    reads += "$id:$name"
                    active.decrementAndGet()
                    sizes[name]
                })
            running(
                mapOf("a" to Archived, "b" to Archived, "sized" to Archived, "recorded" to Recorded, "unplaced" to Archived),
                emptyList(),
                uploader,
                seed = {
                    when (it.demo.capId) {
                        "sized" -> it.copy(itemId = item, size = 300)
                        "unplaced" -> it
                        else -> it.copy(itemId = item)
                    }
                },
            ) { repository ->
                eventually(5.seconds) { repository["b"]?.size shouldBe 200 }
                delay(200.milliseconds)

                repository.all().associate { it.demo.capId to it.size } shouldBe
                    mapOf("a" to 100L, "b" to 200L, "sized" to 300L, "recorded" to null, "unplaced" to null)
                repository["a"]?.status shouldBe Archived
            }

            reads shouldContainExactly listOf("$item:a.mp4", "$item:b.mp4")
            overlapped.get() shouldBe 0
        }

        test("reads a size again on the next pass after the read failed") {
            val reads = AtomicInteger()
            val uploader =
                uploader(size = { _, _ ->
                    if (reads.incrementAndGet() == 1) throw IOException("The Archive is unavailable")
                    100
                })
            running(mapOf("a" to Archived), emptyList(), uploader, seed = { it.copy(itemId = item) }) { repository ->
                eventually(5.seconds) { repository["a"]?.size shouldBe 100 }
            }

            reads.get() shouldBe 2
        }
    })
