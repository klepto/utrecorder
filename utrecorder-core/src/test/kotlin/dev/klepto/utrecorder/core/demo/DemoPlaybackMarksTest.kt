package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.ut99.UtProcessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

class DemoPlaybackMarksTest :
    FunSpec({
        fun wallClock(text: String) = LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()

        test("parse reads the wall time, frame and demo time of the start and end marks among other log lines") {
            val lines =
                listOf(
                    "Log: Log file open, 09/13/26 10:30:00",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: f 4600 tick 0.016 level 5.0 stream 13.0 dil 1.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                    "Exit: Preparing to exit.",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.start shouldBe
                DemoPlaybackMark(
                    label = DemoPlaybackMark.Label.START,
                    wallTime = wallClock("2026-09-13T10:30:05.123"),
                    frame = 4567,
                    demoTime = 12.5.seconds,
                )
            marks.end shouldBe
                DemoPlaybackMark(
                    label = DemoPlaybackMark.Label.END,
                    wallTime = wallClock("2026-09-13T10:35:42.250"),
                    frame = 98765,
                    demoTime = 300.75.seconds,
                )
        }

        test("isMark accepts every line parse reads as a mark, and rejects a line without the mark text") {
            val marks =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 30 6 500 4650 13.75",
                    "UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )
            val others =
                listOf(
                    "Log: Log file open, 09/13/26 10:30:00",
                    "ScriptLog: UTRecorder: f 4600 tick 0.016 level 5.0 stream 13.0 dil 1.0",
                    "ScriptLog: UTRecorder: mark",
                    "ScriptLog: UTRecorder:  mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "",
                )

            marks.filter(DemoPlaybackMarks::isMark) shouldBe marks
            others.filter(DemoPlaybackMarks::isMark) shouldBe emptyList()
        }

        test("parse returns the same marks for a log filtered by isMark as for the whole log") {
            val lines =
                listOf(
                    "Log: Log file open, 09/13/26 10:30:00",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: f 4600 tick 0.016 level 5.0 stream 13.0 dil 1.0",
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 30 6 500 4650 13.75",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 not-a-frame 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                    "Exit: Preparing to exit.",
                )

            DemoPlaybackMarks.parse(lines.filter(DemoPlaybackMarks::isMark)) shouldBe DemoPlaybackMarks.parse(lines)
        }

        test("parse throws naming the start mark when the log holds no start mark") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val failure = shouldThrow<IllegalStateException> { DemoPlaybackMarks.parse(lines) }

            failure.message shouldBe "Run log holds no start mark"
        }

        test("parse throws naming the end mark when the log holds no end mark") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                )

            val failure = shouldThrow<IllegalStateException> { DemoPlaybackMarks.parse(lines) }

            failure.message shouldBe "Run log holds no end mark"
        }

        test("parse skips a mark with a missing field or a field that does not parse") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 not-a-frame 12.5",
                    "ScriptLog: UTRecorder: mark start 2026 13 13 10 29 0 0 100 0.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 not-a-time",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.start.frame shouldBe 4567
            marks.end.frame shouldBe 98765
        }

        test("parse returns the first start mark and the last end mark when the log holds several") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 31 0 0 5000 60.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 34 0 0 90000 240.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.start.frame shouldBe 4567
            marks.start.demoTime shouldBe 12.5.seconds
            marks.end.frame shouldBe 98765
            marks.end.demoTime shouldBe 300.75.seconds
        }

        test("parse returns the first spawn mark when the log holds several") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 30 6 500 4650 13.75",
                    "ScriptLog: UTRecorder: mark spawn 2026 9 13 10 31 0 0 5000 60.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.spawn shouldBe
                DemoPlaybackMark(
                    label = DemoPlaybackMark.Label.SPAWN,
                    wallTime = wallClock("2026-09-13T10:30:06.500"),
                    frame = 4650,
                    demoTime = 13.75.seconds,
                )
        }

        test("parse returns no spawn mark when the log holds none") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.spawn shouldBe null
        }

        test("parse skips a mark with any other label") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark rewind 2026 9 13 10 29 0 0 100 0.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark rewind 2026 9 13 10 36 0 0 99000 301.0",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.start.frame shouldBe 4567
            marks.end.frame shouldBe 98765
        }

        test("parse skips a mark whose label is not in lower case") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark START 2026 9 13 10 29 0 0 100 0.5",
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )

            val marks = DemoPlaybackMarks.parse(lines)

            marks.start.frame shouldBe 4567
        }

        test("parse of a run result returns the marks of its log") {
            val lines =
                listOf(
                    "ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5",
                    "ScriptLog: UTRecorder: mark end 2026 9 13 10 35 42 250 98765 300.75",
                )
            val result = UtProcessResult(0, lines, emptyList(), gameIni = "", userIni = "", exec = emptyList())

            DemoPlaybackMarks.parse(result) shouldBe DemoPlaybackMarks.parse(lines)
        }

        test("parse of a run result throws naming the missing mark and the exit code of the run") {
            val lines = listOf("ScriptLog: UTRecorder: mark start 2026 9 13 10 30 5 123 4567 12.5")
            val result = UtProcessResult(139, lines, lines, gameIni = "", userIni = "", exec = emptyList())

            val failure = shouldThrow<IllegalStateException> { DemoPlaybackMarks.parse(result) }

            failure.message shouldBe "Run log holds no end mark (exit code 139)"
        }
    })
