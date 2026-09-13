package dev.klepto.utrecorder.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [playbackDescription], the builder deriving what one marks run plays and is configured with.
 */
class PlaybackTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-playback-test")
        val installation = home.resolve("install")

        afterSpec { home.toFile().deleteRecursively() }

        fun config() = RecorderConfig(installationDirectory = installation)

        test("the exec line plays the staged demo") {
            val description = playbackDescription("ut-2026-09-13-1030-05", 10.minutes, config())

            description.stagedDemoName shouldBe "ut-2026-09-13-1030-05"
            description.execLine shouldBe "demoplay ut-2026-09-13-1030-05"
            description.execLine shouldNotContain ".dem"
        }

        test("the game-ini copy points [Engine.Engine] Console at UTRecorder.UTRecorderConsole") {
            val description = playbackDescription("ut-2026-09-13-1030-05", 10.minutes, config())

            description.console shouldBe "UTRecorder.UTRecorderConsole"
        }

        test("the UTRecorder.ini values for a known demo duration come from the config") {
            val custom =
                RecorderConfig(
                    installationDirectory = installation,
                    startupTimeout = 11.seconds,
                    maxRunMargin = 13.seconds,
                    exitDelay = 7.seconds,
                )
            val description = playbackDescription("ut-2026-09-13-1030-05", 10.minutes, custom)

            description.autoExit shouldBe true
            description.stopDemoFirst shouldBe true
            description.exitDelay shouldBe 7.seconds
            description.startupTimeout shouldBe 11.seconds
            description.maxRunSeconds shouldBe (10.minutes + 13.seconds)
        }

        test("normal speed configures no PlaybackFrameRate trim") {
            val description = playbackDescription("ut-2026-09-13-1030-05", 10.minutes, config())

            description.execLine shouldNotContain "PlaybackFrameRate"
            description.execLine shouldNotContain "DemoFrames"
        }
    })
