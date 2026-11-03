package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.ut99.UtDirectory.System
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Tests [UtProcess] against a stub installation.
 *
 * A run that writes a log starts a fake game in front of the executable copy: a PowerShell script on Windows and a
 * shell script on Linux. The script copies the file that `UTRECORDER_TEST_LOG` names to the log the run passes in
 * `-log=`, and writes no log when the variable is not set.
 */
class UtProcessTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utprocess-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun installationWithoutIni(name: String): UtInstallation {
            val directory = root / name
            (directory / System.name / UtPlatform.current().gameExecutable).createParentDirectories().writeText("")
            val directoriesConfig = DirectoriesConfig(ConfigReader.EMPTY, home = root / "home")
            return UtInstallation(directory, UtConfig(ConfigReader.EMPTY, directoriesConfig))
        }

        val fakeGame =
            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    val script =
                        (root / "fake-game.ps1").apply {
                            writeText(
                                """
                                if (-not ${'$'}env:UTRECORDER_TEST_LOG) { exit 0 }
                                foreach (${'$'}argument in ${'$'}args) {
                                    if (${'$'}argument -like "-log=*") {
                                        Copy-Item -LiteralPath ${'$'}env:UTRECORDER_TEST_LOG -Destination ${'$'}argument.Substring(5)
                                    }
                                }
                                """.trimIndent(),
                            )
                        }
                    val windows = Path.of(java.lang.System.getenv("SystemRoot") ?: """C:\Windows""")
                    val powershell = windows / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
                    powershell to
                        listOf("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePathString())
                }

                UtPlatform.LINUX -> {
                    val script =
                        (root / "fake-game.sh").apply {
                            writeText(
                                """
                                [ -n "${'$'}UTRECORDER_TEST_LOG" ] || exit 0
                                for ARGUMENT in "${'$'}@"; do
                                    case "${'$'}ARGUMENT" in
                                        -log=*) cp "${'$'}UTRECORDER_TEST_LOG" "${'$'}{ARGUMENT#-log=}" ;;
                                    esac
                                done
                                """.trimIndent(),
                            )
                        }
                    Path.of("/bin/sh") to listOf(script.absolutePathString())
                }
            }

        fun UtProcessBuilder.writeLog(lines: List<String>?) {
            program = fakeGame.first
            programArguments += fakeGame.second
            baseGameIni = ""
            baseUserIni = ""
            if (lines == null) return
            val source = (root / "$name-source.log").apply { writeText(lines.joinToString("\n"), UtPlatform.CHARSET) }
            environment["UTRECORDER_TEST_LOG"] = source.absolutePathString()
        }

        val log =
            listOf(
                "Log: Log file open",
                "ScriptLog: UTRecorder: mark start",
                "ScriptLog: junk",
                "ScriptLog: UTRecorder: mark end",
                "Exit: Preparing to exit.",
            )

        test("a run that sets no log filter keeps no line of the log the game wrote") {
            val process = installationWithoutIni("default-filter").process("default-filter") { writeLog(log) }

            val result = process.launch(60.seconds)

            result.exitCode shouldBe 0
            result.log shouldBe emptyList()
        }

        test("a run keeps exactly the lines of its log that its log filter accepts, in log order") {
            val process =
                installationWithoutIni("mark-filter").process("mark-filter") {
                    writeLog(log)
                    logFilter = { "UTRecorder: mark " in it }
                }

            val result = process.launch(60.seconds)

            result.exitCode shouldBe 0
            result.log shouldBe listOf("ScriptLog: UTRecorder: mark start", "ScriptLog: UTRecorder: mark end")
        }

        test("a run whose game wrote no log returns an empty log") {
            val process =
                installationWithoutIni("missing-log").process("missing-log") {
                    writeLog(null)
                    logFilter = { true }
                }

            val result = process.launch(60.seconds)

            result.exitCode shouldBe 0
            result.log shouldBe emptyList()
        }

        test("a process exposes the log filter it is built with") {
            val filter = { line: String -> line.isEmpty() }

            val process = UtProcess.build(installationWithoutIni("exposed-filter"), "exposed") { logFilter = filter }

            process.logFilter shouldBe filter
        }

        test("drain fails with the failure of a launch that never started the process") {
            val process = installationWithoutIni("failed-launch").process("failed")

            val launchFailure = shouldThrow<IllegalStateException> { process.launch() }

            val drainFailure = shouldThrow<IllegalStateException> { withTimeout(10.seconds) { process.drain().toList() } }
            drainFailure.message shouldBe launchFailure.message
        }

        test("a second concurrent launch fails as already launched") {
            val process = installationWithoutIni("concurrent-launch").process("concurrent")

            val messages =
                coroutineScope {
                    List(2) { async { runCatching { process.launch() }.exceptionOrNull()?.message } }.awaitAll()
                }

            messages.count { it == "Process concurrent has already been launched" } shouldBe 1
        }
    })
