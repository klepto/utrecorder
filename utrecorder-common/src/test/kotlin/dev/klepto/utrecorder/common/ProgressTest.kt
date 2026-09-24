package dev.klepto.utrecorder.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProgressTest :
    FunSpec({
        test("part reports the progress of the part as the matching progress of the whole") {
            val reports = mutableListOf<Double>()
            val part = { progress: Double -> reports += progress }.part(0.5, 0.75)

            part(0.0)
            part(0.5)
            part(2.0)

            reports shouldBe listOf(0.5, 0.625, 0.75)
        }

        test("asDownloadListener reports the bytes received out of the total, and nothing while the total is unknown") {
            val reports = mutableListOf<Double>()
            val listener = { progress: Double -> reports += progress }.asDownloadListener()

            listener(25, 100)
            listener(50, -1)
            listener(100, 100)

            reports shouldBe listOf(0.25, 1.0)
        }

        test("slowest reports the least progress of all parts") {
            val reports = mutableListOf<Double>()
            val (first, second) = { progress: Double -> reports += progress }.slowest(2)

            first(0.5)
            second(0.25)
            second(1.0)
            first(1.0)

            reports shouldBe listOf(0.0, 0.25, 0.5, 1.0)
        }
    })
