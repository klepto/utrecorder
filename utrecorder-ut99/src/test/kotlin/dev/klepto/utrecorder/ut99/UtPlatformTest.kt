package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UtPlatformTest :
    FunSpec({
        context("current") {
            test("is WINDOWS for Windows") {
                UtPlatform.current(OperatingSystem.WINDOWS) shouldBe UtPlatform.WINDOWS
            }

            test("is LINUX for Linux") {
                UtPlatform.current(OperatingSystem.LINUX) shouldBe UtPlatform.LINUX
            }

            test("throws for an operating system with no platform") {
                shouldThrow<IllegalStateException> { UtPlatform.current(OperatingSystem.UNKNOWN) }
            }
        }
    })
