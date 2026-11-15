package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ServerConfigTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-server-config-test").toRealPath()

        afterSpec { root.toFile().deleteRecursively() }

        test("reads the recorder configuration from its own reader") {
            val keys = mapOf("record.target_size" to "640", "record.audio_bitrate" to "96k", "patch_version" to "469d")

            val recorder = ServerConfig(ConfigReader(keys::get), DirectoriesConfig(ConfigReader.EMPTY, home = root)).recorder

            recorder.video.targetSize shouldBe 640L
            recorder.audio.bitrate shouldBe "96k"
            recorder.ut.patchVersion shouldBe "469d"
        }

        test("defaults the cache free space limits to 15 and 20 gigabytes") {
            val config = ServerConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root))

            config.cacheMinFreeBytes shouldBe 16_106_127_360L
            config.cacheTargetFreeBytes shouldBe 21_474_836_480L
        }

        test("reads the cache free space limits in gigabytes") {
            val keys = mapOf("server.cache_min_free_gigabytes" to "2", "server.cache_target_free_gigabytes" to "3")

            val config = ServerConfig(ConfigReader(keys::get), DirectoriesConfig(ConfigReader.EMPTY, home = root))

            config.cacheMinFreeBytes shouldBe 2_147_483_648L
            config.cacheTargetFreeBytes shouldBe 3_221_225_472L
        }

        test("defaults the backlog cache TTL to zero") {
            val config = ServerConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root))

            config.cacheBacklogTtl shouldBe Duration.ZERO
        }

        test("reads the backlog cache TTL in the format of a duration") {
            listOf("12h", "PT12H").forEach { value ->
                val keys = mapOf("server.cache_backlog_ttl" to value)

                val config = ServerConfig(ConfigReader(keys::get), DirectoriesConfig(ConfigReader.EMPTY, home = root))

                config.cacheBacklogTtl shouldBe 12.hours
            }
        }

        test("defaults the recording free space floor to 5 gigabytes, read again every minute") {
            val config = ServerConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root))

            config.recordingMinFreeBytes shouldBe 5_368_709_120L
            config.recordingFreeSpaceInterval shouldBe 1.minutes
        }

        test("reads the recording free space floor in gigabytes and its interval") {
            val keys = mapOf("server.recording.min_free_gigabytes" to "2", "server.recording.free_space_interval" to "30s")

            val config = ServerConfig(ConfigReader(keys::get), DirectoriesConfig(ConfigReader.EMPTY, home = root))

            config.recordingMinFreeBytes shouldBe 2_147_483_648L
            config.recordingFreeSpaceInterval shouldBe 30.seconds
        }

        test("installs the game of the recorder under its own directories") {
            val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root)

            val config = ServerConfig(ConfigReader.EMPTY, directories = directories)

            config.recorder.ut.gameDirectory shouldBe root / "installs" / "ut99"
        }
    })
