package dev.klepto.utrecorder.core.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException

class DemoUnavailableExceptionTest :
    FunSpec({
        test("names the URL and the status in its message, and keeps both with its cause") {
            val cause = IllegalStateException("404 Not Found")

            val exception = DemoUnavailableException("http://example.invalid/demos/run.dem", 404, cause)

            exception.message shouldBe "Demo http://example.invalid/demos/run.dem is unavailable: HTTP 404"
            exception.url shouldBe "http://example.invalid/demos/run.dem"
            exception.status shouldBe 404
            exception.cause shouldBeSameInstanceAs cause
            exception.shouldBeInstanceOf<IOException>()
        }
    })
