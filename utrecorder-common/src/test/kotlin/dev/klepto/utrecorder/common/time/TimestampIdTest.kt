package dev.klepto.utrecorder.common.time

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TimestampIdTest :
    FunSpec({
        val utc = ZoneId.of("UTC")

        fun clockReading(vararg seconds: Long): Clock {
            val instants = seconds.map(Instant::ofEpochSecond).iterator()
            return object : Clock() {
                override fun instant(): Instant = instants.next()

                override fun getZone(): ZoneId = utc

                override fun withZone(zone: ZoneId?): Clock = this
            }
        }

        test("reads an id equal to the fixed clock's instant, rendered in its zone") {
            val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneId.of("America/New_York"))

            TimestampId(clock).next() shouldBe "2023-11-14-1713-20"
        }

        test("two ids from one source under a frozen clock differ, the second one second later") {
            val id = TimestampId(Clock.fixed(Instant.ofEpochSecond(1_700_000_000), utc))

            id.next() shouldBe "2023-11-14-2213-20"
            id.next() shouldBe "2023-11-14-2213-21"
        }

        test("never goes backwards when the clock jumps backwards") {
            val id = TimestampId(clockReading(1_000, 500))

            id.next() shouldBe "1970-01-01-0016-40"
            id.next() shouldBe "1970-01-01-0016-41"
        }

        test("follows the clock forward once the clock advances past the last id") {
            val id = TimestampId(clockReading(1_000, 2_000))

            id.next() shouldBe "1970-01-01-0016-40"
            id.next() shouldBe "1970-01-01-0033-20"
        }

        test("hands out no duplicates to concurrent readers") {
            val id = TimestampId()
            val executor = Executors.newFixedThreadPool(16)

            val results =
                (1..16)
                    .map { executor.submit<List<String>> { (1..500).map { id.next() } } }
                    .map { it.get() }

            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)

            val all = results.flatten()
            all.toSet().size shouldBe 8_000
            all.size shouldBe 8_000
        }
    })
