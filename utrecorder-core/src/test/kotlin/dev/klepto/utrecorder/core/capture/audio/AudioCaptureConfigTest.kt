package dev.klepto.utrecorder.core.capture.audio

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.core.demo.DemoPlaybackViewport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudioCaptureConfigTest :
    FunSpec({
        test("viewport renders at the sound viewport with the sound fps as the frame rate limit") {
            AudioCaptureConfig(ConfigReader.EMPTY, width = 64, height = 48, fps = 60).viewport() shouldBe
                DemoPlaybackViewport(
                    width = 64,
                    height = 48,
                    frameRateLimit = 60.0,
                )
        }
    })
