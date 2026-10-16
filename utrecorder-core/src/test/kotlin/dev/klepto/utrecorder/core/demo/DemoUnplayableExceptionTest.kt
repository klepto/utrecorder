package dev.klepto.utrecorder.core.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeoutException

class DemoUnplayableExceptionTest :
    FunSpec({
        test("reason reports a native class the game failed to load") {
            DemoUnplayableException.reason("Warning: Failed to load native class NPLoaderLL_v22.NPLLibraryLoader") shouldBe
                "Game failed to load native class NPLoaderLL_v22.NPLLibraryLoader"
        }

        test("reason reports a failed demo connection") {
            val line = "Browse: Index.unr?failed?Name=Player?Class=Botpack.TMale2?team=0"

            DemoUnplayableException.reason(line) shouldBe "Demo connection failed"
        }

        test("reason returns null for the browse that ends a demo normally") {
            DemoUnplayableException.reason("Warning: Browse: Index.unr?entry?Name=Player?Class=Botpack.TMale2").shouldBeNull()
        }

        test("reason returns null for a texture the game failed to load") {
            DemoUnplayableException.reason("""Warning: Failed to load "Texture SGirlSkins.3": Object not found""").shouldBeNull()
        }

        test("wrap names the reason and keeps the failure as its cause") {
            val failure = TimeoutException("Hook library did not connect within 1m 30s")

            val wrapped = DemoUnplayableException.wrap(failure, "Demo connection failed")

            wrapped.shouldBeInstanceOf<DemoUnplayableException>()
            wrapped.message shouldBe "Demo connection failed"
            wrapped.cause shouldBeSameInstanceAs failure
        }

        test("wrap returns the failure without a reason") {
            val failure = IllegalStateException("Run log holds no start mark")

            DemoUnplayableException.wrap(failure, null) shouldBeSameInstanceAs failure
        }

        test("wrap returns a cancellation as it is") {
            val failure = CancellationException("Cancelled")

            DemoUnplayableException.wrap(failure, "Demo connection failed") shouldBeSameInstanceAs failure
        }

        test("wrap returns a demo unplayable exception as it is") {
            val failure = DemoUnplayableException("Demo connection failed")

            DemoUnplayableException.wrap(failure, "Demo connection failed") shouldBeSameInstanceAs failure
        }
    })
