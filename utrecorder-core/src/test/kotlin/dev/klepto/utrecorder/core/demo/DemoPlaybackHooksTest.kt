package dev.klepto.utrecorder.core.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DemoPlaybackHooksTest :
    FunSpec({
        test("message returns a line the hook library printed, trimmed") {
            DemoPlaybackHooks.message("  uthooks: demo ended after 120 frames, kept 118\r") shouldBe
                "uthooks: demo ended after 120 frames, kept 118"
        }

        test("message returns a line that reports the frame capture stopped") {
            DemoPlaybackHooks.message("uthooks: a frame send to the frame socket failed, capture stopped") shouldBe
                "uthooks: a frame send to the frame socket failed, capture stopped"
        }

        test("message returns the timing line") {
            val line =
                "uthooks: timing over 120 frames in ms, present mean 16.67 max 20.10, read mean 0.52 max 1.30, " +
                    "send mean 3.25 max 8.00, swap mean 0.40 max 0.90"

            DemoPlaybackHooks.message(line) shouldBe line
        }

        test("message returns the context restore line") {
            DemoPlaybackHooks.message("uthooks: made the GL context current again after a window was destroyed") shouldBe
                "uthooks: made the GL context current again after a window was destroyed"
        }

        test("message returns null for a line the game printed") {
            DemoPlaybackHooks.message("Log: Demo playback started, uthooks: is named here") shouldBe null
        }
    })
