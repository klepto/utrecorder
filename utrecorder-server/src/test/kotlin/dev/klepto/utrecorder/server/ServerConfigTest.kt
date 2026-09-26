package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ServerConfigTest :
    FunSpec({
        test("reads every server key") {
            val home = Path.of("/srv/utrecorder").toAbsolutePath().normalize()
            val properties =
                mapOf(
                    "utrecorder.home" to home.toString(),
                    "utrecorder.server.port" to "9090",
                    "utrecorder.server.database" to home.resolve("other.db").toString(),
                    "utrecorder.server.cache" to home.resolve("mp4").toString(),
                    "utrecorder.server.long_threshold" to "10m",
                    "utrecorder.server.max_attempts" to "5",
                    "utrecorder.server.cache_ttl" to "7d",
                    "utrecorder.server.poll_interval" to "1h",
                    "utrecorder.server.backfill_cooldown" to "30s",
                    "utrecorder.server.source_url" to "https://gateway.example.com",
                    "utrecorder.server.demo_url" to "http://demos.example.com",
                    "utrecorder.server.archive.access_key" to "access",
                    "utrecorder.server.archive.secret_key" to "secret",
                    "utrecorder.server.archive.collection" to "movies",
                    "utrecorder.server.archive.prefix" to "bunny",
                )
            val reader = SystemConfigReader(properties = properties::get, environment = { null })

            with(ServerConfig(reader)) {
                port shouldBe 9090
                database shouldBe home.resolve("other.db")
                cache shouldBe home.resolve("mp4")
                longThreshold shouldBe 10.minutes
                maxAttempts shouldBe 5
                cacheTtl shouldBe 7.days
                pollInterval shouldBe 1.hours
                backfillCooldown shouldBe 30.seconds
                sourceUrl shouldBe "https://gateway.example.com"
                demoUrl shouldBe "http://demos.example.com"
                archive.accessKey shouldBe "access"
                archive.secretKey shouldBe "secret"
                archive.collection shouldBe "movies"
                archive.prefix shouldBe "bunny"
            }
        }

        test("falls back to the literal defaults") {
            val home = Path.of("/srv/utrecorder").toAbsolutePath().normalize()
            val reader = ConfigReader { key -> if (key == "home") home.toString() else null }

            with(ServerConfig(reader)) {
                port shouldBe 8080
                database shouldBe home.resolve("database").resolve("server.db")
                cache shouldBe home.resolve("recordings")
                longThreshold shouldBe 5.minutes
                maxAttempts shouldBe 3
                cacheTtl shouldBe 30.days
                pollInterval shouldBe 10.minutes
                backfillCooldown shouldBe 5.minutes
                sourceUrl shouldBe "https://gateway.utbt.net"
                demoUrl shouldBe "http://api.utbt.net/demos"
                archive.accessKey shouldBe null
                archive.secretKey shouldBe null
                archive.collection shouldBe "test_collection"
                archive.prefix shouldBe "utrecorder"
            }
        }
    })
