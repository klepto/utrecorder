package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.common.logger
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
 * package named more than once appears once, as its first entry, with names compared ignoring case. An entry that names
 * no package, or a name that is not a plain file name, is left out.
 * @property duration The demo time of the last record. It can overshoot the played length by a fraction of a second.
 * @property frameInterval The median demo time between two demo frames. If the demo holds no two frames less than
 * 0.1 seconds apart, it is `null` instead.
 * @property cap The cap that ends the run the demo records. If the demo holds no cap, it is `null` instead.
 */
data class UtDemo(
    val map: String,
    val packages: List<UtPackage>,
    val duration: Duration,
    val frameInterval: Duration?,
    val cap: UtDemoCap?,
) {
    companion object {
        private val TOLERANCE = 50.milliseconds
        private val MAX_FRAME_INTERVAL = 100.milliseconds
        private const val UNSAFE_NAME_CHARACTERS = "/\\:\u0000"

        private val SPAWN_MARKER = Regex("""BTPOG_SPAWN_MARKER:(\S+)""")
        private val CAP_MARKER = Regex("""BTPOG_CAP_MARKER:(\S+)""")
        private val CAPPED = Regex("""(.+) capped! \S+ team scores!""")
        private val FINISHED = Regex("""\[UTBT] (.+) finished in (\S+) \(.*\)""")
        private val REPORTED_TIME = Regex("""(?:(\d{1,3}):)?(\d{1,3})\.(\d{3})""")
        private val log = logger<UtDemo>()

        /**
         * Returns the demo read from the file at [path].
         */
        suspend fun read(path: Path): UtDemo = read(withContext(Dispatchers.IO) { path.readBytes() })

        /**
         * Returns the demo read from [bytes]. A record whose packet does not decode is logged and skipped. Reading stops
         * at a record cut off at the end of [bytes] and keeps the records before it.
         *
         * @throws IllegalStateException If no record names the map.
         */
        fun read(bytes: ByteArray): UtDemo {
            val records = UtDemoRecord.read(bytes).toList()
            val lines = records.flatMap { it.controlLines() }
            val map = checkNotNull(lines.firstNotNullOfOrNull(::levelOf)) { "Demo names no map" }
            val packages =
                lines
                    .mapNotNull(::packageOf)
                    .distinctBy { it.name.lowercase() }
            val duration = records.lastOrNull()?.time ?: Duration.ZERO
            return UtDemo(map, packages, duration, frameIntervalOf(records), capOf(records))
        }

        /**
         * Returns the median demo time between two demo frames of [records], leaving out any interval longer than
         * [MAX_FRAME_INTERVAL]. If no interval is left, returns `null` instead.
         */
        private fun frameIntervalOf(records: List<UtDemoRecord>): Duration? =
            records
                .filter { it.time > Duration.ZERO }
                .distinctBy { it.frame }
                .zipWithNext(::intervalOf)
                .filterNotNull()
                .sorted()
                .takeIf { it.isNotEmpty() }
                ?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

        /**
         * Returns the demo time per demo frame between [first] and [second]. If [second] is not a later frame at a later
         * time, or the interval is longer than [MAX_FRAME_INTERVAL], returns `null` instead.
         */
        private fun intervalOf(
            first: UtDemoRecord,
            second: UtDemoRecord,
        ): Duration? {
            if (second.frame <= first.frame || second.time <= first.time) return null
            return ((second.time - first.time) / (second.frame - first.frame)).takeIf { it <= MAX_FRAME_INTERVAL }
        }

        /**
         * Returns the map a `WELCOME` [line] names. If the line is not a `WELCOME` line or names no map, returns `null`
         * instead.
         */
        private fun levelOf(line: String): String? = fieldsOf(line, "WELCOME")?.get("LEVEL")

        /**
         * Returns the package a `USES` [line] names. If the line is not a `USES` line, returns `null` instead. If it
         * names no package or a name that is not a plain file name, logs it and returns `null`.
         */
        private fun packageOf(line: String): UtPackage? {
            val fields = fieldsOf(line, "USES") ?: return null
            val name = fields["PKG"].orEmpty()
            if (!isPlainFileName(name)) {
                log.warn { "Skipping package \"$name\", which is not a plain file name" }
                return null
            }
            return UtPackage.named(name, fields["GUID"].orEmpty())
        }

        /**
         * Returns whether [name] names a file in the directory it is resolved against, and no other location.
         */
        private fun isPlainFileName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." && name.none { it in UNSAFE_NAME_CHARACTERS }

        /**
         * Returns the `KEY=VALUE` fields of a [line] opening with [command]. If the line opens with another command,
         * returns `null` instead.
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
         * Returns the first cap [records] hold. If they hold none, returns `null` instead.
         */
        private fun capOf(records: List<UtDemoRecord>): UtDemoCap? {
            val lines = records.flatMap(::linesOf)
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
        private fun linesOf(record: UtDemoRecord): List<Line> = record.shiftedStrings().map { Line(record.time, it) }

        /**
         * Returns the time between the spawn marker carrying [marker]'s id and [marker] itself. If [lines] holds no
         * spawn marker of that id, returns `null` instead.
         */
        private fun gapOf(
            marker: Marker,
            lines: List<Line>,
        ): Duration? =
            lines
                .asSequence()
                .mapNotNull { it.marker(SPAWN_MARKER) }
                .firstOrNull { it.id == marker.id }
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
         * Returns the player whose run the demo records: the one name of [reportedTimes] whose time is nearest [gap].
         * If [gap] is `null` or no reported time is within [TOLERANCE] of it, returns the one player [names] holds
         * instead. If the names chosen hold more than one player or none, returns `null`.
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
         * Returns the names of [reportedTimes] whose time is nearest [gap]. If no time is within [TOLERANCE] of [gap],
         * returns `null` instead.
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
         * Returns the run time a reported time of the form `M:SS.mmm` or `SS.mmm` holds. If [text] is in neither form,
         * returns `null` instead.
         */
        private fun timeOf(text: String): Duration? {
            val match = REPORTED_TIME.matchEntire(text) ?: return null
            val (minutes, seconds, millis) = match.destructured
            return (minutes.toIntOrNull() ?: 0).minutes + seconds.toInt().seconds + millis.toInt().milliseconds
        }

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
             * Returns the marker this line holds under [pattern]. If the line does not match [pattern], returns `null`
             * instead.
             */
            fun marker(pattern: Regex): Marker? = pattern.matchEntire(text)?.let { Marker(it.groupValues[1], time) }

            /**
             * Returns the cap this line broadcasts. If the line is not a `capped!` line, returns `null` instead.
             */
            fun capped(): Capped? =
                CAPPED
                    .matchEntire(text)
                    ?.let { Capped(it.groupValues[1].split(" and "), time) }

            /**
             * Returns the name and run time this line reports. If the line is not a `finished in` line or reports a
             * time in no known format, returns `null` instead.
             */
            fun reportedTime(): Pair<String, Duration>? {
                val match = FINISHED.matchEntire(text) ?: return null
                val time = timeOf(match.groupValues[2]) ?: return null
                return match.groupValues[1] to time
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
