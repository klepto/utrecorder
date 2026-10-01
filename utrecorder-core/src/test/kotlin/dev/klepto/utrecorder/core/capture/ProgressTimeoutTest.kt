package dev.klepto.utrecorder.core.capture

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

class ProgressTimeoutTest :
    FunSpec({
        test("runs a block that keeps making progress past the idle window") {
            val progress = MutableStateFlow(0L)

            val result =
                withProgressTimeout(progress, 300.milliseconds) {
                    repeat(12) {
                        delay(100.milliseconds)
                        progress.value = it + 1L
                    }
                    "finished"
                }

            result shouldBe "finished"
        }

        test("cancels a block that makes no progress for the idle window") {
            val progress = MutableStateFlow(0L)
            var cancelled = false

            shouldThrow<TimeoutException> {
                withProgressTimeout(progress, 300.milliseconds) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }

            cancelled shouldBe true
        }

        test("cancels a block whose progress stops after it started") {
            val progress = MutableStateFlow(0L)

            shouldThrow<TimeoutException> {
                withProgressTimeout(progress, 300.milliseconds) {
                    repeat(5) {
                        delay(100.milliseconds)
                        progress.value = it + 1L
                    }
                    awaitCancellation()
                }
            }
        }

        test("does not count a repeated value as progress") {
            val progress = MutableStateFlow(5L)

            shouldThrow<TimeoutException> {
                withProgressTimeout(progress, 300.milliseconds) {
                    repeat(12) {
                        delay(100.milliseconds)
                        progress.value = 5L
                    }
                }
            }
        }
    })
