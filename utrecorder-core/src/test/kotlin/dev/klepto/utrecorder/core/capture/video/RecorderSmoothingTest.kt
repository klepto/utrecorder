package dev.klepto.utrecorder.core.capture.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class RecorderSmoothingTest :
    FunSpec({
        test("of keeps the delays of a high frame rate demo at their floor") {
            RecorderSmoothing.of(2.998.milliseconds) shouldBe
                RecorderSmoothing(2.998.milliseconds, 30.milliseconds, 15.milliseconds)
        }

        test("of caps the movement delay of a 60 frames a second demo at 60 ms") {
            RecorderSmoothing.of(16.667.milliseconds) shouldBe
                RecorderSmoothing(16.667.milliseconds, 60.milliseconds, 40.milliseconds)
        }

        test("of falls back to an 8 ms frame interval when the demo shows none") {
            RecorderSmoothing.of(null) shouldBe RecorderSmoothing(8.milliseconds, 50.milliseconds, 25.milliseconds)
        }

        test("command passes the frame interval and both delays in seconds") {
            RecorderSmoothing(16.667.milliseconds, 60.milliseconds, 40.milliseconds).command() shouldBe
                "SmoothRecorder 0.016667 0.060 0.040"
        }
    })
