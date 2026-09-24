package dev.klepto.utrecorder.core.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DemoPlaybackViewportTest :
    FunSpec({
        test("clientValues sets the viewport for windowed and fullscreen play and the frame rate limit") {
            val viewport =
                DemoPlaybackViewport(
                    width = 64,
                    height = 48,
                    frameRateLimit = 60.0,
                )

            viewport.clientValues() shouldBe
                mapOf(
                    "WindowedViewportX" to "64",
                    "WindowedViewportY" to "48",
                    "FullscreenViewportX" to "64",
                    "FullscreenViewportY" to "48",
                    "FrameRateLimit" to "60.000000",
                )
        }

        test("renderDeviceValues sets only the frame rate limit") {
            val viewport =
                DemoPlaybackViewport(
                    width = 64,
                    height = 48,
                    frameRateLimit = 0.0,
                )

            viewport.renderDeviceValues() shouldBe mapOf("FrameRateLimit" to "0.000000")
        }

        test("gameIniValues sets the client values in the client of every platform and the limit in the render device") {
            val viewport =
                DemoPlaybackViewport(
                    width = 64,
                    height = 48,
                    frameRateLimit = 60.0,
                )
            val clientValues =
                mapOf(
                    "WindowedViewportX" to "64",
                    "WindowedViewportY" to "48",
                    "FullscreenViewportX" to "64",
                    "FullscreenViewportY" to "48",
                    "FrameRateLimit" to "60.000000",
                )

            viewport.gameIniValues() shouldBe
                mapOf(
                    "WinDrv.WindowsClient" to clientValues,
                    "SDLDrv.SDLClient" to clientValues,
                    "OpenGLDrv.OpenGLRenderDevice" to mapOf("FrameRateLimit" to "60.000000"),
                )
        }
    })
