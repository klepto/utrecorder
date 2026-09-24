package dev.klepto.utrecorder.common

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * A source of unique ids written as a date and time, such as `2026-09-08-1450-49`.
 *
 * Hold one per set of ids that has to stay apart, and read from it with [next]. Ids are unique within one source, not
 * across sources. Two ids taken within one second are one second apart, so an id can read a moment ahead of the clock
 * it came from.
 *
 * @property clock The clock the ids are read from, and the zone they are written in.
 */
class TimestampId(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /**
     * The Unix time in seconds the id handed out last reads as.
     */
    private val lastSecond = AtomicLong()

    /**
     * Returns the next id, written as the date and time it reads as.
     */
    fun next(): String = format(nextSecond())

    /**
     * Returns the Unix time in seconds of the next id. That is the current second of the clock, or one second past the id
     * handed out last if that is later.
     */
    private fun nextSecond(): Long = lastSecond.updateAndGet { previous -> maxOf(previous + 1, clock.instant().epochSecond) }

    /**
     * Returns the id the given Unix time in [second] reads as, in the zone of the clock.
     */
    private fun format(second: Long): String = FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(second), clock.zone))

    companion object {
        /**
         * The pattern an id is written in.
         */
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss")
    }
}
