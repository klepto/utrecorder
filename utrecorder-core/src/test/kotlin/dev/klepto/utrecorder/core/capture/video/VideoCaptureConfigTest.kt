package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.core.demo.DemoPlaybackViewport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VideoCaptureConfigTest :
    FunSpec({
        test("viewport renders at the configured resolution with no frame rate limit") {
            VideoCaptureConfig(ConfigReader.EMPTY, width = 960, height = 540).viewport() shouldBe
                DemoPlaybackViewport(
                    width = 960,
                    height = 540,
                    frameRateLimit = 0.0,
                )
        }

        test("renderFps renders the motion blur samples of every encoded frame") {
            VideoCaptureConfig(ConfigReader.EMPTY, fps = 60, motionBlurSamples = 4).renderFps() shouldBe 240
        }

        test("renderFps renders one frame per encoded frame when motion blur is off") {
            VideoCaptureConfig(ConfigReader.EMPTY, fps = 60, motionBlur = false).renderFps() shouldBe 60
        }
    })
