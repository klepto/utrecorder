package dev.klepto.utrecorder.common.process

import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

class ExecutableTest :
    FunSpec({
        val executablePath = Path.of("fake-executable")
        val workingDirectory = Path.of("/work/probe").toAbsolutePath().normalize()

        fun source(osName: String = "Linux") =
            PropertySource(
                properties = mapOf("user.dir" to "$workingDirectory", "os.name" to osName)::get,
                environment = { null },
            )

        /**
         * The path and arguments of a shell able to run [script] on the current operating system.
         */
        fun shellCommand(script: String): Pair<Path, List<String>> =
            when (OperatingSystem.current()) {
                OperatingSystem.WINDOWS -> {
                    Path.of(System.getenv("ComSpec") ?: """C:\Windows\System32\cmd.exe""") to listOf("/c", script)
                }

                else -> {
                    Path.of("/bin/sh") to listOf("-c", script)
                }
            }

        /**
         * A script that echoes `line1` through `line[count]`.
         */
        fun linesScript(count: Int): String =
            when (OperatingSystem.current()) {
                OperatingSystem.WINDOWS -> (1..count).joinToString("&") { "echo line$it" }
                else -> (1..count).joinToString("; ") { "echo line$it" }
            }

        /**
         * An [Executable] running a shell that echoes `line1` through `line[count]`.
         */
        fun linesExecutable(
            count: Int,
            configure: ExecutableBuilder.() -> Unit = {},
        ): Executable {
            val (path, shellArguments) = shellCommand(linesScript(count))
            return Executable.build(path) {
                arguments += shellArguments
                configure()
            }
        }

        /**
         * An [Executable] that runs for [seconds] without a wrapping shell, so closing it closes its output stream
         * directly instead of leaving it held open by a wrapper's own child process.
         */
        fun sleepExecutable(
            seconds: Int,
            configure: ExecutableBuilder.() -> Unit = {},
        ): Executable =
            when (OperatingSystem.current()) {
                OperatingSystem.WINDOWS -> {
                    Executable.build(Path.of(System.getenv("SystemRoot") ?: """C:\Windows""", "System32", "PING.EXE")) {
                        arguments += listOf("-n", "${seconds + 1}", "127.0.0.1")
                        configure()
                    }
                }

                else -> {
                    Executable.build(Path.of("/bin/sleep")) {
                        arguments += listOf("$seconds")
                        configure()
                    }
                }
            }

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

        context("execute") {
            test("a timed-out block throws TimeoutException, not a CancellationException") {
                val executable = sleepExecutable(seconds = 5) { closeTimeout = 100.milliseconds }

                shouldThrow<TimeoutException> {
                    executable.execute { await(100.milliseconds) }
                }
            }

            test("a collector that starts after lines were already written still sees them: the replay guarantee") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    await()

                    drain().toList() shouldBe listOf("line1", "line2", "line3")
                }
            }

            test("drain().toList() completes when the stream of the process closes") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    drain().toList() shouldBe listOf("line1", "line2", "line3")
                }
            }

            test("the output is capped at maxOutputLines, oldest lines dropped first") {
                val executable = linesExecutable(count = 5) { maxOutputLines = 2 }

                val result = executable.startAndAwait()

                result.output shouldBe listOf("line4", "line5")
            }

            test("a collector that suspends between lines still sees the whole output") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    await()

                    val collected = mutableListOf<String>()
                    drain().collect {
                        delay(1.milliseconds)
                        collected += it
                    }

                    collected shouldBe listOf("line1", "line2", "line3")
                }
            }

            test("a collector that throws fails neither the process nor the other collectors") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    val failing = runCatching { drain().collect { error("boom") } }

                    failing.isFailure shouldBe true
                    drain().toList() shouldBe listOf("line1", "line2", "line3")
                    await().isSuccess shouldBe true
                }
            }
        }
    })
