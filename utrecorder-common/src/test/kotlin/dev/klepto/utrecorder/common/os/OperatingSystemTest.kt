package dev.klepto.utrecorder.common.os

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [OperatingSystem], scoped to [OperatingSystem.libraryPathVariable].
 */
class OperatingSystemTest :
    FunSpec({
        context("libraryPathVariable") {
            test("WINDOWS names none") {
                OperatingSystem.WINDOWS.libraryPathVariable.shouldBeNull()
            }

            test("LINUX names LD_LIBRARY_PATH") {
                OperatingSystem.LINUX.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }

            test("UNKNOWN matches LINUX") {
                OperatingSystem.UNKNOWN.libraryPathVariable shouldBe OperatingSystem.LINUX.libraryPathVariable
            }
        }
    })
