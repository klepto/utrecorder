package dev.klepto.utrecorder.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class MarksTest :
    FunSpec({
        test("both marks present in realistic log lines returns first start and last end") {
            val lines =
                listOf(
                    "Log: Log file open, 09/13/26 10:30:00",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: f 4600 tick 0.016 level 5.0 stream 13.0 dil 1.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                    "Exit: Preparing to exit.",
                )

            val marks = DemoMarks.parse(lines)

            marks.start shouldBe
                DemoMark(
                    label = DemoMark.Label.START,
                    wallTime = Instant.parse("2026-09-13T10:30:05.123Z"),
                    frame = 4567,
                    demoTime = 12.5.seconds,
                )
            marks.end shouldBe
                DemoMark(
                    label = DemoMark.Label.END,
                    wallTime = Instant.parse("2026-09-13T10:35:42.250Z"),
                    frame = 98765,
                    demoTime = 300.75.seconds,
                )
        }

        test("a log with no start throws IllegalStateException naming the missing start mark") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val failure = shouldThrow<IllegalStateException> { DemoMarks.parse(lines) }

            failure.message shouldContain "start"
        }

        test("a log with no end throws IllegalStateException naming the missing end mark") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                )

            val failure = shouldThrow<IllegalStateException> { DemoMarks.parse(lines) }

            failure.message shouldContain "end"
        }

        test("malformed lines are ignored") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 not-a-frame 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 not-a-time",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoMarks.parse(lines)

            marks.start.frame shouldBe 4567
            marks.end.frame shouldBe 98765
        }

        test("duplicate marks keep the first start and the last end") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 31 0 0 5000 60.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 34 0 0 90000 240.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoMarks.parse(lines)

            marks.start.frame shouldBe 4567
            marks.start.demoTime shouldBe 12.5.seconds
            marks.end.frame shouldBe 98765
            marks.end.demoTime shouldBe 300.75.seconds
        }

        test("marks with any other label are ignored") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 29 0 0 100 0.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 36 0 0 99000 301.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoMarks.parse(lines)

            marks.start.frame shouldBe 4567
            marks.end.frame shouldBe 98765
        }

        test("an empty log throws naming the missing start mark") {
            val failure = shouldThrow<IllegalStateException> { DemoMarks.parse(emptyList()) }

            failure.message shouldContain "start"
        }

        test("a mark labelled START in upper case is ignored") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark START 2026 9 13 10 29 0 0 100 0.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoMarks.parse(lines)

            marks.start.frame shouldBe 4567
        }

        test("a mark whose month is 13 is ignored") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 13 13 10 29 0 0 100 0.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoMarks.parse(lines)

            marks.start.frame shouldBe 4567
        }
    })
