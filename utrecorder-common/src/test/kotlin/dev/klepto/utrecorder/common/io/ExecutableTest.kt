package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.LINUX
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
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

        /**
         * Returns an [Executable] running a shell that echoes `line1` through `line[count]`.
         */
        fun linesExecutable(
            count: Int,
            configure: ExecutableBuilder.() -> Unit = {},
        ): Executable {
            val echoes = (1..count).map { "echo line$it" }
            val (shell, shellArguments) =
                when (OperatingSystem.current()) {
                    WINDOWS -> {
                        Path.of(System.getenv("ComSpec") ?: """C:\Windows\System32\cmd.exe""") to
                            listOf("/c", echoes.joinToString("&"))
                    }

                    else -> {
                        Path.of("/bin/sh") to listOf("-c", echoes.joinToString("; "))
                    }
                }
            return Executable.build(shell) {
                arguments += shellArguments
                configure()
            }
        }

        /**
         * Returns an [Executable] that runs for [seconds] without a wrapping shell, so closing it closes its output
         * stream directly. A wrapping shell would leave the stream open through its own child process.
         */
        fun sleepExecutable(
            seconds: Int,
            configure: ExecutableBuilder.() -> Unit = {},
        ): Executable {
            val (program, programArguments) =
                when (OperatingSystem.current()) {
                    WINDOWS -> {
                        Path.of(System.getenv("SystemRoot") ?: """C:\Windows""", "System32", "PING.EXE") to
                            listOf("-n", "${seconds + 1}", "127.0.0.1")
                    }

                    else -> {
                        Path.of("/bin/sleep") to listOf("$seconds")
                    }
                }
            return Executable.build(program) {
                arguments += programArguments
                configure()
            }
        }

        context("build") {
            test("holds the arguments, working directory and environment the block sets") {
                val otherDirectory = Path.of("/work/other").toAbsolutePath().normalize()

                val executable =
                    Executable.build(executablePath) {
                        arguments += listOf("-first", "-second")
                        workingDirectory = otherDirectory
                        environment["PROBE"] = "value"
                    }

                executable.executablePath shouldBe executablePath
                executable.arguments shouldBe listOf("-first", "-second")
                executable.workingDirectory shouldBe otherDirectory
                executable.environment shouldBe mapOf("PROBE" to "value")
            }

            test("holds no arguments and no environment without a block") {
                val executable = Executable.build(executablePath)

                executable.arguments shouldBe emptyList()
                executable.environment shouldBe emptyMap()
            }
        }

        context("prependLibraryPath") {
            test("leaves the environment alone on an operating system with no library path variable") {
                val executable =
                    Executable.build(executablePath) {
                        prependLibraryPath(Path.of("install", "System"), WINDOWS)
                    }

                executable.environment shouldBe emptyMap()
            }

            test("puts the path in front of the value the builder holds, separated by the platform path separator") {
                val path = Path.of("install", "System")

                val executable =
                    Executable.build(executablePath) {
                        environment["LD_LIBRARY_PATH"] = "/existing/path"
                        prependLibraryPath(path, LINUX)
                    }

                executable.environment shouldBe mapOf("LD_LIBRARY_PATH" to "$path${File.pathSeparator}/existing/path")
            }
        }

        context("execute") {
            test("throws TimeoutException, not a CancellationException, when await times out") {
                val executable = sleepExecutable(seconds = 5) { closeTimeout = 100.milliseconds }

                shouldThrow<TimeoutException> {
                    executable.execute { await(100.milliseconds) }
                }
            }

            test("replays the lines written before a collector starts") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    await()

                    drain().toList() shouldBe listOf("line1", "line2", "line3")
                }
            }

            test("completes drain when the output stream closes") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    drain().toList() shouldBe listOf("line1", "line2", "line3")
                }
            }

            test("keeps only the last maxOutputLines lines of the output") {
                val executable = linesExecutable(count = 5) { maxOutputLines = 2 }

                val result = executable.startAndAwait()

                result.output shouldBe listOf("line4", "line5")
            }

            test("gives a collector that suspends between lines the whole output") {
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

            test("fails neither the process nor the other collectors when a collector throws") {
                val executable = linesExecutable(count = 3)

                executable.execute {
                    val failing = runCatching { drain().collect { error("Boom") } }

                    failing.isFailure shouldBe true
                    drain().toList() shouldBe listOf("line1", "line2", "line3")
                    await().isSuccess shouldBe true
                }
            }
        }
    })
