package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.OperatingSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UtPlatformTest :
    FunSpec({
        context("current") {
            test("returns WINDOWS, the platform that runs on Windows") {
                UtPlatform.current(OperatingSystem.WINDOWS) shouldBe UtPlatform.WINDOWS
            }

            test("returns LINUX, the platform that runs on Linux") {
                UtPlatform.current(OperatingSystem.LINUX) shouldBe UtPlatform.LINUX
            }
        }
    })
