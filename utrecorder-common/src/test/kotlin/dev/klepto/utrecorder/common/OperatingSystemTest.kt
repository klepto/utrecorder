package dev.klepto.utrecorder.common

import dev.klepto.utrecorder.common.OperatingSystem.LINUX
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class OperatingSystemTest :
    FunSpec({
        context("libraryPathVariable") {
            test("is null on WINDOWS") {
                WINDOWS.libraryPathVariable.shouldBeNull()
            }

            test("is LD_LIBRARY_PATH on LINUX") {
                LINUX.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }
        }

        context("executableSuffix") {
            test("is .exe on WINDOWS") {
                WINDOWS.executableSuffix shouldBe ".exe"
            }

            test("is empty on LINUX") {
                LINUX.executableSuffix shouldBe ""
            }
        }
    })
