package dev.klepto.utrecorder.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecordingDescriptionTest :
    FunSpec({
        fun config() =
            RecorderConfig(
                installationDirectory = Path.of("install"),
                fps = 60,
                width = 960,
                height = 540,
                soundWidth = 64,
                soundHeight = 64,
                soundFps = 60,
                maxRunMargin = 13.seconds,
                timeoutGrace = 17.seconds,
                frameTimeout = 20.minutes,
            )

        test("the frame pass's description carries the configured resolution") {
            val description = RecordingDescription.framePass(config())

            description shouldBe
                RecordingDescription(
                    width = 960,
                    height = 540,
                    frameRateLimit = 0.0,
                    frameTimeout = 20.minutes,
                )
        }

        test("the sound pass's description carries the configured viewport and cap") {
            val description = RecordingDescription.soundPass(config(), 10.minutes)

            description shouldBe
                RecordingDescription(
                    width = 64,
                    height = 64,
                    frameRateLimit = 60.0,
                    frameTimeout = 630.seconds,
                )
        }

        test("clientValues carries the viewport and limit into both client sections") {
            val description =
                RecordingDescription(
                    width = 64,
                    height = 64,
                    frameRateLimit = 60.0,
                    frameTimeout = 10.minutes,
                )

            val values =
                mapOf(
                    "WindowedViewportX" to "64",
                    "WindowedViewportY" to "64",
                    "FullscreenViewportX" to "64",
                    "FullscreenViewportY" to "64",
                    "FrameRateLimit" to "60.000000",
                )

            description.clientValues() shouldBe values
        }

        test("renderDeviceValues carries the limit into the render device section") {
            val description =
                RecordingDescription(
                    width = 64,
                    height = 64,
                    frameRateLimit = 0.0,
                    frameTimeout = 10.minutes,
                )

            description.renderDeviceValues() shouldBe mapOf("FrameRateLimit" to "0.000000")
        }
    })
