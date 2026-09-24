package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.core.RecorderStage.Capturing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecorderStageTest :
    FunSpec({
        test("start reports the stage at zero, then each progress value clamped to the range from zero to one") {
            val reports = mutableListOf<RecorderProgress>()

            val listener = Capturing.start { reports += it }
            listener(0.25)
            listener(1.5)
            listener(-1.0)
            listener(Double.NaN)

            reports.map { it.progress } shouldBe listOf(0.0, 0.25, 1.0, 0.0, 0.0)
            reports.map { it.stage }.toSet() shouldBe setOf(Capturing)
        }
    })
