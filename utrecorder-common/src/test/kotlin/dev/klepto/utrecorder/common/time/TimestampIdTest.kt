package dev.klepto.utrecorder.common.time

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss")

private fun literal(
    second: Long,
    zone: ZoneId,
): String = FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(second), zone))

private class MutableClock(
    var seconds: Long,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = Instant.ofEpochSecond(seconds)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId?): Clock = this
}

class TimestampIdTest :
    FunSpec({
        test("reads an id equal to the fixed clock's instant, rendered in its zone") {
            val zone = ZoneId.of("America/New_York")
            val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), zone)

            TimestampId(clock).next() shouldBe "2023-11-14-1713-20"
        }

        test("two ids from one source under a frozen clock differ, the second one second later") {
            val zone = ZoneId.of("UTC")
            val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), zone)
            val id = TimestampId(clock)

            val first = id.next()
            val second = id.next()

            first shouldBe literal(1_700_000_000, zone)
            second shouldBe literal(1_700_000_001, zone)
        }

        test("never goes backwards when the clock jumps backwards") {
            val zone = ZoneId.of("UTC")
            val clock = MutableClock(1_000, zone)
            val id = TimestampId(clock)

            val first = id.next()
            clock.seconds = 500
            val second = id.next()

            first shouldBe literal(1_000, zone)
            second shouldBe literal(1_001, zone)
        }

        test("follows the clock forward once the clock advances past the last id") {
            val zone = ZoneId.of("UTC")
            val clock = MutableClock(1_000, zone)
            val id = TimestampId(clock)

            id.next()
            clock.seconds = 2_000
            val next = id.next()

            next shouldBe literal(2_000, zone)
        }

        test("hands out no duplicates to concurrent readers") {
            val id = TimestampId()
            val threads = 16
            val perThread = 500
            val executor = Executors.newFixedThreadPool(threads)

            val results =
                (1..threads)
                    .map { executor.submit<List<String>> { (1..perThread).map { id.next() } } }
                    .map { it.get() }

            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)

            val all = results.flatten()
            all.toSet().size shouldBe all.size
            all.size shouldBe threads * perThread
        }
    })
