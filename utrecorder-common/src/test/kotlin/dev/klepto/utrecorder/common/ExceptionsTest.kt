package dev.klepto.utrecorder.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.result.shouldBeFailure
import io.kotest.matchers.result.shouldBeSuccess
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class ExceptionsTest :
    FunSpec({
        context("suspendCatching") {
            test("returns the block's value as a success") {
                suspendCatching { 42 }.shouldBeSuccess(42)
            }

            test("holds an ordinary exception as a failure") {
                val cause = IllegalStateException("Boom")
                suspendCatching<Int> { throw cause }.shouldBeFailure(cause)
            }

            test("lets a CancellationException propagate") {
                shouldThrow<CancellationException> {
                    suspendCatching<Int> { throw CancellationException("Cancelled") }
                }
            }
        }

        context("suspendCatchingIo") {
            test("holds an IOException as a failure") {
                val cause = IOException("Boom")
                suspendCatchingIo<Int> { throw cause }.shouldBeFailure(cause)
            }

            test("lets a non-IOException propagate") {
                shouldThrow<IllegalStateException> {
                    suspendCatchingIo<Int> { throw IllegalStateException("Boom") }
                }
            }
        }
    })
