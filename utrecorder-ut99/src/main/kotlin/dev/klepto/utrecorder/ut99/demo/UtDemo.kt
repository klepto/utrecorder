package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.ut99.UtPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Parsed fields of an Unreal Tournament `.dem` file.
 *
 * Use [read] to parse a demo from a [Path] or from its bytes.
 *
 * @property map The map the demo opens, such as `CTF-BT-II-CoZyliciouS`.
 * @property packages The packages the demo loads, in the order the demo names them, with the level package first. A
 * package named more than once appears once, as its first entry, with names compared ignoring case.
 * @property duration The demo time of the last record. It can overshoot the played length by a fraction of a second.
 * @property cap The cap that ends the run the demo records, or `null` when the demo holds no cap.
 */
data class UtDemo(
    val map: String,
    val packages: List<UtPackage>,
    val duration: Duration,
    val cap: UtDemoCap?,
) {
    companion object {
        private const val NAME_SEPARATOR = " and "

        private val TOLERANCE = 50.milliseconds

        private val SPAWN_MARKER = Regex("""BTPOG_SPAWN_MARKER:(\S+)""")
        private val CAP_MARKER = Regex("""BTPOG_CAP_MARKER:(\S+)""")
        private val CAPPED = Regex("""(.+) capped! \S+ team scores!""")
        private val FINISHED = Regex("""\[UTBT] (.+) finished in (\S+) \(.*\)""")
        private val REPORTED_TIME = Regex("""(?:(\d{1,3}):)?(\d{1,3})\.(\d{3})""")

        /**
         * Reads the demo file at the given [path].
         */
        suspend fun read(path: Path): UtDemo = read(withContext(Dispatchers.IO) { path.readBytes() })

        /**
         * Reads the demo held in [bytes]. Reading stops at the first record that does not decode and keeps the records
         * before it. Throws [IllegalStateException] if no record names the map.
         */
        fun read(bytes: ByteArray): UtDemo {
            val records = UtDemoRecord.read(bytes).toList()
            val lines = records.flatMap { it.controlLines() }
            val map = checkNotNull(lines.firstNotNullOfOrNull(::levelOf)) { "Demo names no map" }
            val packages = lines.mapNotNull(::packageOf).distinctBy { it.name.lowercase() }
            val duration = records.lastOrNull()?.time ?: Duration.ZERO
            return UtDemo(map, packages, duration, readCap(records))
        }

        /**
         * Returns the map a `WELCOME` [line] names, or `null` if the line is not one or names no map.
         */
        private fun levelOf(line: String): String? = fieldsOf(line, "WELCOME")?.get("LEVEL")

        /**
         * Returns the package a `USES` [line] names, or `null` if the line is not one.
         */
        private fun packageOf(line: String): UtPackage? {
            val fields = fieldsOf(line, "USES") ?: return null
            return UtPackage.named(
                name = fields["PKG"].orEmpty(),
                guid = fields["GUID"].orEmpty(),
            )
        }

        /**
         * Returns the `KEY=VALUE` fields of a [line] opening with [command], or `null` if it opens with another one.
         */
        private fun fieldsOf(
            line: String,
            command: String,
        ): Map<String, String>? {
            val words = line.split(' ')
            if (words.first() != command) return null

            return words
                .drop(1)
                .filter { it.indexOf('=') > 0 }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }

        /**
         * Reads the first cap [records] hold, or returns `null` if they hold none.
         */
        private fun readCap(records: List<UtDemoRecord>): UtDemoCap? {
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
        private fun linesIn(record: UtDemoRecord): List<Line> = record.shiftedStrings().map { Line(record.time, it) }

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
                .mapNotNull { it.reportedTime() }
                .distinctBy { it.first }
                .toMap()

        /**
         * Returns the player whose run the demo records: the one of [reportedTimes] closest to [gap] within the
         * tolerance, or the one player [names] holds when nothing separates the reported times. `null` when neither
         * names exactly one player.
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

            /**
             * Returns the name and run time this line reports, or `null` when the line is not a `finished in` line or
             * reports a time in no known format.
             */
            fun reportedTime(): Pair<String, Duration>? {
                val match = FINISHED.matchEntire(text) ?: return null
                val time = timeOf(match.groupValues[2]) ?: return null
                return match.groupValues[1] to time
            }

            /**
             * Returns the run time a reported time of the form `M:SS.mmm` or `SS.mmm` holds, or `null` when [text] is
             * in neither form.
             */
            private fun timeOf(text: String): Duration? {
                val match = REPORTED_TIME.matchEntire(text) ?: return null
                val (minutes, seconds, millis) = match.destructured
                return (minutes.toIntOrNull() ?: 0).minutes + seconds.toInt().seconds + millis.toInt().milliseconds
            }
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
}
