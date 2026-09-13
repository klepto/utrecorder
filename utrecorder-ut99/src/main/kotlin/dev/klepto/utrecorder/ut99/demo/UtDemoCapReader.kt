package dev.klepto.utrecorder.ut99.demo

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the [UtDemoCap] of a demo out of the messages and markers its records carry.
 *
 * The patterns it matches are those the Bunny Track mods send: the broadcast lines of `UTBT_BTMod` and the spawn and
 * cap markers of `BTPog`.
 */
internal object UtDemoCapReader {
    private const val MILLIS_PER_SECOND = 1000
    private const val NAME_SEPARATOR = " and "

    private val TOLERANCE = 50.milliseconds

    private val SPAWN_MARKER = Regex("""BTPOG_SPAWN_MARKER:(\S+)""")
    private val CAP_MARKER = Regex("""BTPOG_CAP_MARKER:(\S+)""")
    private val CAPPED = Regex("""(.+) capped! \S+ team scores!""")
    private val FINISHED = Regex("""\[UTBT] (.+) finished in (\S+) \(.*\)""")
    private val REPORTED_TIME = Regex("""(?:(\d{1,3}):)?(\d{1,3})\.(\d{3})""")

    /**
     * Reads the first cap [records] hold, or returns `null` if they hold none.
     */
    fun read(records: List<UtDemoRecord>): UtDemoCap? {
        val lines = records.flatMap(::linesIn)
        val marker = lines.firstNotNullOfOrNull { it.marker(CAP_MARKER) }
        val capped = lines.firstNotNullOfOrNull { it.capped() }
        val demoTime = marker?.time ?: capped?.time ?: return null

        val gap = marker?.let { gapOf(it, lines) }
        val reportedTimes = reportedTimesOf(lines)
        val capper = capperOf(gap, reportedTimes, capped?.names.orEmpty())
        return UtDemoCap(
            capper = capper,
            id = marker?.id,
            runTime = capper?.let { reportedTimes[it] } ?: gap,
            demoTime = demoTime,
        )
    }

    /**
     * Returns every candidate line one [record] carries, at the demo time the record arrives at.
     */
    private fun linesIn(record: UtDemoRecord): List<Line> {
        val time = demoTimeOf(record.time)
        return record.shiftedStrings().map { Line(time, it) }
    }

    /**
     * Returns the demo time a record [time] in seconds holds, to the nearest millisecond.
     */
    private fun demoTimeOf(time: Float): Duration = (time.toDouble() * MILLIS_PER_SECOND).roundToInt().milliseconds

    /**
     * Returns the time between the spawn marker carrying [marker]'s id and [marker] itself, or `null` when [lines]
     * holds no spawn marker of that id.
     */
    private fun gapOf(
        marker: Marker,
        lines: List<Line>,
    ): Duration? =
        lines
            .firstNotNullOfOrNull { line -> line.marker(SPAWN_MARKER)?.takeIf { it.id == marker.id } }
            ?.let { marker.time - it.time }

    /**
     * Returns the run time each `finished in` line of [lines] reports, keeping the first time reported for a name.
     */
    private fun reportedTimesOf(lines: List<Line>): Map<String, Duration> =
        lines
            .mapNotNull(::reportedTimeOf)
            .distinctBy { it.first }
            .toMap()

    /**
     * Returns the name and run time a `finished in` [line] reports, or `null` when the line is not one or reports a
     * time in no known format.
     */
    private fun reportedTimeOf(line: Line): Pair<String, Duration>? {
        val match = FINISHED.matchEntire(line.text) ?: return null
        val time = timeOf(match.groupValues[2]) ?: return null
        return match.groupValues[1] to time
    }

    /**
     * Returns the run time a reported time of the form `M:SS.mmm` or `SS.mmm` holds, or `null` when [text] is in
     * neither form.
     */
    private fun timeOf(text: String): Duration? {
        val match = REPORTED_TIME.matchEntire(text) ?: return null
        val (minutes, seconds, millis) = match.destructured
        return (minutes.toIntOrNull() ?: 0).minutes + seconds.toInt().seconds + millis.toInt().milliseconds
    }

    /**
     * Returns the player whose run the demo records: the one of [reportedTimes] closest to [gap] within the tolerance,
     * or the one player [names] holds when nothing separates the reported times. `null` when neither names exactly one
     * player.
     */
    private fun capperOf(
        gap: Duration?,
        reportedTimes: Map<String, Duration>,
        names: List<String>,
    ): String? =
        when (val closest = gap?.let { closestNames(it, reportedTimes) }) {
            null -> names.singleOrNull()
            else -> closest.singleOrNull()
        }

    /**
     * Returns the names of [reportedTimes] whose time is nearest [gap], or `null` when none of them is within the
     * tolerance of it.
     */
    private fun closestNames(
        gap: Duration,
        reportedTimes: Map<String, Duration>,
    ): List<String>? =
        reportedTimes
            .filterValues { (it - gap).absoluteValue <= TOLERANCE }
            .entries
            .groupBy({ (it.value - gap).absoluteValue }, { it.key })
            .minByOrNull { it.key }
            ?.value

    /**
     * A candidate line of a demo.
     *
     * @property time The demo time the record carrying the line arrives at.
     * @property text The text of the line.
     */
    private class Line(
        val time: Duration,
        val text: String,
    ) {
        /**
         * Returns the marker this line holds under [pattern], or `null` when the line is not one.
         */
        fun marker(pattern: Regex): Marker? = pattern.matchEntire(text)?.let { Marker(it.groupValues[1], time) }

        /**
         * Returns the cap this line broadcasts, or `null` when the line is not a `capped!` line.
         */
        fun capped(): Capped? =
            CAPPED
                .matchEntire(text)
                ?.let { Capped(it.groupValues[1].split(NAME_SEPARATOR), time) }
    }

    /**
     * A `BTPog` marker of a demo.
     *
     * @property id The unique cap id the marker carries.
     * @property time The demo time the marker arrives at.
     */
    private class Marker(
        val id: String,
        val time: Duration,
    )

    /**
     * The `capped!` line a demo broadcasts.
     *
     * @property names The players the line names, in the order it names them.
     * @property time The demo time the line arrives at.
     */
    private class Capped(
        val names: List<String>,
        val time: Duration,
    )
}
