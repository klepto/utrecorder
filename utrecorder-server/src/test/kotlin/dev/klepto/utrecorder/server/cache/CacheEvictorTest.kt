package dev.klepto.utrecorder.server.cache

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.server.ArchiveConfig
import dev.klepto.utrecorder.server.CleanupService
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.DemoStore
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import dev.klepto.utrecorder.server.RecordingState
import dev.klepto.utrecorder.server.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@OptIn(ExperimentalPathApi::class)
class CacheEvictorTest :
    FunSpec({
        val demo =
            Demo(
                id = 0,
                sourceId = "7f0e2a52",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 83.seconds,
                duration = 4.minutes,
                addedAt = Instant.parse("2026-09-01T12:00:00Z"),
                recordingId = 0,
            )
        val ttl = 30.days
        val enabledArchive = ArchiveConfig(ConfigReader.EMPTY, accessKey = "access", secretKey = "secret")
        val disabledArchive = ArchiveConfig(ConfigReader.EMPTY)

        suspend fun <T> withEvictor(
            archive: ArchiveConfig,
            block: suspend (CacheEvictor, DemoStore, Path) -> T,
        ): T {
            val directory = createTempDirectory("utrecorder-cache-evictor-test")
            try {
                val cache = directory["recordings"].createDirectories()
                val config = ServerConfig(ConfigReader.EMPTY, cache = cache, cacheTtl = ttl, archive = archive)
                val store = DemoStore.open(directory["server.db"], cache)
                val evictor = CacheEvictor(store, CleanupService(store), config)
                return try {
                    block(evictor, store, cache)
                } finally {
                    store.close()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

        fun cacheFile(
            cache: Path,
            recording: Recording,
        ): Path = cache["${recording.id}.mp4"]

        suspend fun insertCached(
            store: DemoStore,
            cache: Path,
            state: RecordingState,
            accessedAt: Instant,
        ): Recording {
            val (_, recording) =
                store.insert(demo, Recording(lane = RecordingLane.SHORT, state = state, accessedAt = accessedAt))
            val stored = recording.copy(file = "${recording.id}.mp4")
            store.update(stored)
            cacheFile(cache, stored).writeText("mp4")
            return stored
        }

        test("keeps a recording whose accessed_at is just under the ttl") {
            withEvictor(disabledArchive) { evictor, store, cache ->
                val recording =
                    insertCached(store, cache, RecordingState.ARCHIVED, Instant.now().minus((ttl - 1.minutes).toJavaDuration()))

                evictor.pass()

                store
                    .load()
                    .recordings
                    .single()
                    .file shouldBe recording.file
                cacheFile(cache, recording).shouldExist()
            }
        }

        test("evicts an archived recording just over the ttl, clearing its file") {
            withEvictor(disabledArchive) { evictor, store, cache ->
                val recording =
                    insertCached(store, cache, RecordingState.ARCHIVED, Instant.now().minus((ttl + 1.minutes).toJavaDuration()))

                evictor.pass()

                val stored = store.load().recordings.single()
                stored.file.shouldBeNull()
                stored.state shouldBe RecordingState.ARCHIVED
                cacheFile(cache, recording).shouldNotExist()
            }
        }

        test("evicts and expires a recorded, not-yet-archived recording when the uploader is disabled") {
            withEvictor(disabledArchive) { evictor, store, cache ->
                val recording =
                    insertCached(store, cache, RecordingState.RECORDED, Instant.now().minus((ttl + 1.minutes).toJavaDuration()))

                evictor.pass()

                val stored = store.load().recordings.single()
                stored.file.shouldBeNull()
                stored.state shouldBe RecordingState.EXPIRED
                cacheFile(cache, recording).shouldNotExist()
            }
        }

        test("keeps a recorded, not-yet-archived recording past the ttl when the uploader is enabled") {
            withEvictor(enabledArchive) { evictor, store, cache ->
                val recording =
                    insertCached(store, cache, RecordingState.RECORDED, Instant.now().minus((ttl + 1.minutes).toJavaDuration()))

                evictor.pass()

                val stored = store.load().recordings.single()
                stored.file shouldBe recording.file
                stored.state shouldBe RecordingState.RECORDED
                cacheFile(cache, recording).shouldExist()
            }
        }

        test("skips a recording without a local mp4") {
            withEvictor(disabledArchive) { evictor, store, _ ->
                store.insert(
                    demo,
                    Recording(
                        lane = RecordingLane.SHORT,
                        state = RecordingState.EXPIRED,
                        accessedAt = Instant.now().minus((ttl + 1.minutes).toJavaDuration()),
                    ),
                )

                evictor.pass()

                store
                    .load()
                    .recordings
                    .single()
                    .state shouldBe RecordingState.EXPIRED
            }
        }
    })
