package dev.klepto.utrecorder.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProgressTest :
    FunSpec({
        test("part reports the progress of the part as the matching progress of the whole") {
            val reports = mutableListOf<Double>()
            val part = { report: Progress<Unit> -> reports += report.progress }.part(0.5, 0.75)

            part(Progress(0.0))
            part(Progress(0.5))
            part(Progress(2.0))

            reports shouldBe listOf(0.5, 0.625, 0.75)
        }

        test("slowest reports the least progress of all parts") {
            val reports = mutableListOf<Double>()
            val (first, second) = { report: Progress<Unit> -> reports += report.progress }.slowest(2)

            first(Progress(0.5))
            second(Progress(0.25))
            second(Progress(1.0))
            first(Progress(1.0))

            reports shouldBe listOf(0.0, 0.25, 0.5, 1.0)
        }
    })
