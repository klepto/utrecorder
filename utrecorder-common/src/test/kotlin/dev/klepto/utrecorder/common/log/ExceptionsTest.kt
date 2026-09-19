package dev.klepto.utrecorder.common.log

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class ExceptionsTest :
    FunSpec({
        context("catching") {
            test("returns the block's value as a success") {
                catching { 42 }.shouldBeSuccess(42)
            }

            test("holds an ordinary exception as a failure") {
                val cause = IllegalStateException("boom")
                catching<Int> { throw cause }.shouldBeFailure(cause)
            }

            test("rethrows a CancellationException instead of holding it") {
                shouldThrow<CancellationException> {
                    catching<Int> { throw CancellationException("cancelled") }
                }
            }
        }

        context("catchingIo") {
            test("holds an IOException as a failure") {
                val cause = IOException("boom")
                catchingIo<Int> { throw cause }.shouldBeFailure(cause)
            }

            test("lets a non-IOException propagate") {
                shouldThrow<IllegalStateException> {
                    catchingIo<Int> { throw IllegalStateException("boom") }
                }
            }
        }
    })
