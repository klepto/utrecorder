package dev.klepto.utrecorder.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One mark the game logged while it played a demo: the wall clock, the rendered frame count and the demo time of the
 * moment the mark names.
 *
 * @property label The mark the game logged, `start` or `end`.
 * @property wallTime The wall-clock time the mark was logged, read as UTC.
 * @property frame The number of frames the game had rendered when the mark was logged.
 * @property demoTime The demo time the mark was logged at.
 */
data class DemoMark(
    val label: String,
    val wallTime: Instant,
    val frame: Long,
    val demoTime: Duration,
)

/**
 * The demo-time range the game actually played, as reported by the start and end marks in its run log.
 *
 * @property start The first `start` mark of the run.
 * @property end The last `end` mark of the run.
 */
data class DemoMarks(
    val start: DemoMark,
    val end: DemoMark,
)

/**
 * Reads the marks the game logged from [lines] and returns the range they bound.
 *
 * Matches `UTRecorder: mark <label> <year> <month> <day> <hour> <minute> <second> <millisecond> <frame> <demoTime>`
 * wherever it appears on a line and ignores anything else there, including marks carrying any other label. The first
 * `start` mark and the last `end` mark win.
 *
 * Throws [IllegalStateException] naming the mark the log holds none of.
 */
fun parseMarks(lines: List<String>): DemoMarks {
    var start: DemoMark? = null
    var end: DemoMark? = null
    for (line in lines) {
        val mark = parseMark(line) ?: continue
        if (mark.label == START_LABEL && start == null) {
            start = mark
        } else if (mark.label == END_LABEL) {
            end = mark
        }
    }
    val first = start ?: throw IllegalStateException("Run log holds no $START_LABEL mark")
    val last = end ?: throw IllegalStateException("Run log holds no $END_LABEL mark")
    return DemoMarks(first, last)
}

private const val START_LABEL = "start"
private const val END_LABEL = "end"
private const val NANOS_PER_MILLI = 1_000_000

private val MARK_PATTERN =
    Regex(
        """UTRecorder: mark (?<label>\S+) (?<year>\d+) (?<month>\d+) (?<day>\d+) (?<hour>\d+)""" +
            """ (?<minute>\d+) (?<second>\d+) (?<millisecond>\d+) (?<frame>\S+) (?<demoTime>\S+)""",
    )

private fun parseMark(line: String): DemoMark? {
    val fields = MARK_PATTERN.find(line)?.groups ?: return null
    val label = fields["label"]?.value ?: return null
    if (label != START_LABEL && label != END_LABEL) {
        return null
    }
    val wallTime = fields.wallTime() ?: return null
    val frame = fields["frame"]?.value?.toLongOrNull() ?: return null
    val demoTime = fields["demoTime"]?.value?.toDoubleOrNull() ?: return null
    return DemoMark(label = label, wallTime = wallTime, frame = frame, demoTime = demoTime.seconds)
}

private fun MatchGroupCollection.wallTime(): Instant? {
    val year = this["year"]?.value?.toIntOrNull() ?: return null
    val month = this["month"]?.value?.toIntOrNull() ?: return null
    val day = this["day"]?.value?.toIntOrNull() ?: return null
    val hour = this["hour"]?.value?.toIntOrNull() ?: return null
    val minute = this["minute"]?.value?.toIntOrNull() ?: return null
    val second = this["second"]?.value?.toIntOrNull() ?: return null
    val millisecond = this["millisecond"]?.value?.toIntOrNull() ?: return null
    return runCatching {
        LocalDateTime.of(year, month, day, hour, minute, second, millisecond * NANOS_PER_MILLI).toInstant(ZoneOffset.UTC)
    }.getOrNull()
}
