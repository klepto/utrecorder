package dev.klepto.utrecorder.common.os

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class OperatingSystemTest :
    FunSpec({
        context("libraryPathVariable") {
            test("WINDOWS names none") {
                OperatingSystem.WINDOWS.libraryPathVariable.shouldBeNull()
            }

            test("LINUX names LD_LIBRARY_PATH") {
                OperatingSystem.LINUX.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }

            test("UNKNOWN names LD_LIBRARY_PATH") {
                OperatingSystem.UNKNOWN.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }
        }

        context("executableSuffix") {
            test("WINDOWS is .exe") {
                OperatingSystem.WINDOWS.executableSuffix shouldBe ".exe"
            }

            test("LINUX is empty") {
                OperatingSystem.LINUX.executableSuffix shouldBe ""
            }

            test("UNKNOWN is empty, as LINUX") {
                OperatingSystem.UNKNOWN.executableSuffix shouldBe ""
            }
        }
    })
