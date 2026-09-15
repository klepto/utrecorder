package dev.klepto.utrecorder.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mark the game logged while playing a demo, locating one moment on the wall clock, in rendered frames and in demo
 * time.
 *
 * @property label The mark the game logged.
 * @property wallTime The wall-clock time the mark was logged, read off the host's own clock, which is the clock the
 * game writes, and resolved to an instant in the zone of the process reading it.
 * @property frame The number of frames the game had rendered when the mark was logged.
 * @property demoTime The demo time the mark was logged at.
 */
data class DemoMark(
    val label: Label,
    val wallTime: Instant,
    val frame: Long,
    val demoTime: Duration,
) {
    /**
     * Kinds of mark the game logs, written in lower case in the run log.
     */
    enum class Label { START, END }
}

/**
 * Demo-time range a run played, bounded by the marks in its log.
 *
 * Use [parse] to read one from a run log.
 *
 * @property start The first start mark of the run.
 * @property end The last end mark of the run.
 */
data class DemoMarks(
    val start: DemoMark,
    val end: DemoMark,
) {
    companion object {
        private val MARK_PATTERN =
            Regex(
                """UTRecorder: mark (?<label>\S+) (?<year>\d+) (?<month>\d+) (?<day>\d+) (?<hour>\d+)""" +
                    """ (?<minute>\d+) (?<second>\d+) (?<millisecond>\d+) (?<frame>\S+) (?<demoTime>\S+)""",
            )

        /**
         * Returns the range bounded by the first start mark and the last end mark the game logged in [lines].
         *
         * A mark reads `UTRecorder: mark <label> <year> <month> <day> <hour> <minute> <second> <millisecond> <frame>
         * <demoTime>` anywhere on a line, with the label in lower case. A line holding no such mark is skipped.
         *
         * @throws IllegalStateException when [lines] hold no start mark or no end mark.
         */
        fun parse(lines: List<String>): DemoMarks {
            val marks = lines.mapNotNull(::parseMark)
            val start = marks.firstOrNull { it.label == DemoMark.Label.START } ?: error("Run log holds no start mark")
            val end = marks.lastOrNull { it.label == DemoMark.Label.END } ?: error("Run log holds no end mark")
            return DemoMarks(start, end)
        }

        private fun parseMark(line: String): DemoMark? {
            val match = MARK_PATTERN.find(line) ?: return null
            val label = DemoMark.Label.entries.find { it.name.lowercase() == match.field("label") } ?: return null
            val wallTime = wallTime(match) ?: return null
            val frame = match.field("frame").toLongOrNull() ?: return null
            val demoTime = match.field("demoTime").toDoubleOrNull() ?: return null
            return DemoMark(label = label, wallTime = wallTime, frame = frame, demoTime = demoTime.seconds)
        }

        private fun wallTime(match: MatchResult): Instant? {
            val year = match.integer("year") ?: return null
            val month = match.integer("month") ?: return null
            val day = match.integer("day") ?: return null
            val hour = match.integer("hour") ?: return null
            val minute = match.integer("minute") ?: return null
            val second = match.integer("second") ?: return null
            val millisecond = match.integer("millisecond") ?: return null
            val fields =
                listOf(
                    ChronoField.YEAR to year,
                    ChronoField.MONTH_OF_YEAR to month,
                    ChronoField.HOUR_OF_DAY to hour,
                    ChronoField.MINUTE_OF_HOUR to minute,
                    ChronoField.SECOND_OF_MINUTE to second,
                )
            if (fields.any { (field, value) -> !field.range().isValidIntValue(value.toLong()) }) return null
            if (!YearMonth.of(year, month).isValidDay(day)) return null
            return LocalDateTime
                .of(year, month, day, hour, minute, second)
                .plus(millisecond.toLong(), ChronoUnit.MILLIS)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        }

        private fun MatchResult.field(name: String): String = groups[name]?.value.orEmpty()

        private fun MatchResult.integer(name: String): Int? = field(name).toIntOrNull()
    }
}
