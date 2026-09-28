package dev.klepto.utrecorder.server.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DemoTest :
    FunSpec({
        fun demo(capTime: Duration): Demo =
            Demo(
                capId = "42",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = capTime,
                timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                demoUrl = "http://api.utbt.net/demos/42",
            )

        test("formats as the cap id, map, cap time and player") {
            demo(1.minutes + 5.seconds + 42.milliseconds).toString() shouldBe "42: CTF-BT-Maverick - 01:05.042 by klepto"
        }

        test("formats a cap time under a minute without minutes") {
            demo(5.seconds + 7.milliseconds).toString() shouldBe "42: CTF-BT-Maverick - 05.007 by klepto"
        }
    })
