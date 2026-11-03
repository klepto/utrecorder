package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage.Capturing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecorderStageTest :
    FunSpec({
        test("entries are the stages a recording runs, in the order it runs them, which the web client mirrors") {
            RecorderStage.entries.map { it.name } shouldBe
                listOf("FetchingDemo", "PreparingInstallation", "ResolvingPackages", "Capturing", "Muxing")
        }

        test("start reports the stage at zero, then each progress value clamped to the range from zero to one") {
            val reports = mutableListOf<Progress<RecorderStage>>()

            val listener = Capturing.start { reports += it }
            listener(Progress(0.25))
            listener(Progress(1.5))
            listener(Progress(-1.0))
            listener(Progress(Double.NaN))

            reports.map { it.progress } shouldBe listOf(0.0, 0.25, 1.0, 0.0, 0.0)
            reports.map { it.state }.toSet() shouldBe setOf(Capturing)
        }
    })
