package dev.klepto.utrecorder.common.process

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path

class ExecutableTest :
    FunSpec({
        val executablePath = Path.of("fake-executable")
        val workingDirectory = Path.of("/work/probe").toAbsolutePath().normalize()

        fun source(osName: String = "Linux") =
            PropertySource(
                properties = mapOf("user.dir" to "$workingDirectory", "os.name" to osName)::get,
                environment = { null },
            )

        context("build") {
            test("applies the builder block and holds the arguments, working directory and environment it set") {
                val otherDirectory = Path.of("/work/other").toAbsolutePath().normalize()

                val executable =
                    Executable.build(executablePath, source()) {
                        arguments += listOf("-first", "-second")
                        this.workingDirectory = otherDirectory
                        environment["PROBE"] = "value"
                    }

                executable.executablePath shouldBe executablePath
                executable.arguments shouldBe listOf("-first", "-second")
                executable.workingDirectory shouldBe otherDirectory
                executable.environment shouldBe mapOf("PROBE" to "value")
            }

            test("with no block holds no arguments, no environment and the user.dir of its source") {
                val executable = Executable.build(executablePath, source())

                executable.arguments shouldBe emptyList()
                executable.environment shouldBe emptyMap()
                executable.workingDirectory shouldBe workingDirectory
            }
        }

        context("prependLibraryPath") {
            test("with no operating system named uses Linux from the source and sets LD_LIBRARY_PATH") {
                val path = Path.of("install", "System")

                val executable = Executable.build(executablePath, source("Linux")) { prependLibraryPath(path) }

                executable.environment shouldBe mapOf("LD_LIBRARY_PATH" to "$path")
            }

            test("with no operating system named uses Windows 11 from the source and sets nothing") {
                val executable =
                    Executable.build(executablePath, source("Windows 11")) { prependLibraryPath(Path.of("install", "System")) }

                executable.environment shouldBe emptyMap()
            }

            test("puts the path on the variable named by an operating system with one") {
                val path = Path.of("install", "System")

                val executable =
                    Executable.build(executablePath, source()) { prependLibraryPath(path, OperatingSystem.LINUX) }

                executable.environment shouldBe mapOf("LD_LIBRARY_PATH" to "$path")
            }

            test("leaves the environment untouched for an operating system with no variable") {
                val executable =
                    Executable.build(executablePath, source()) {
                        prependLibraryPath(Path.of("install", "System"), OperatingSystem.WINDOWS)
                    }

                executable.environment shouldBe emptyMap()
            }

            test("prepends in front of an existing value, separated by the platform path separator") {
                val path = Path.of("install", "System")

                val executable =
                    Executable.build(executablePath, source()) {
                        environment["LD_LIBRARY_PATH"] = "/existing/path"
                        prependLibraryPath(path, OperatingSystem.LINUX)
                    }

                executable.environment shouldBe mapOf("LD_LIBRARY_PATH" to "$path${File.pathSeparator}/existing/path")
            }
        }
    })
