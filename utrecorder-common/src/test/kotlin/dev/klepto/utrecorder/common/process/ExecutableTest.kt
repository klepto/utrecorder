package dev.klepto.utrecorder.common.process

import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path

/**
 * Tests for [ExecutableBuilder], scoped to [ExecutableBuilder.prependLibraryPath].
 */
class ExecutableTest :
    FunSpec({
        val executablePath = Path.of("fake-executable")

        context("prependLibraryPath") {
            test("puts the path on the variable named by an operating system with one") {
                val builder = ExecutableBuilder(executablePath)
                val path = Path.of("install", "System")

                builder.prependLibraryPath(path, OperatingSystem.LINUX)

                builder.environment shouldBe mapOf("LD_LIBRARY_PATH" to "$path")
            }

            test("leaves the environment untouched for an operating system with no variable") {
                val builder = ExecutableBuilder(executablePath)

                builder.prependLibraryPath(Path.of("install", "System"), OperatingSystem.WINDOWS)

                builder.environment shouldBe emptyMap()
            }

            test("prepends in front of an existing value, separated by the platform path separator") {
                val builder = ExecutableBuilder(executablePath)
                builder.environment["LD_LIBRARY_PATH"] = "/existing/path"
                val path = Path.of("install", "System")

                builder.prependLibraryPath(path, OperatingSystem.LINUX)

                builder.environment shouldBe mapOf("LD_LIBRARY_PATH" to "$path${File.pathSeparator}/existing/path")
            }
        }
    })
