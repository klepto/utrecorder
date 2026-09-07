package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Tests for [UtInstance] against a fake game executable, since starting real Unreal Tournament is out of scope for a
 * unit test.
 *
 * On Windows, the fake cannot be a batch script: [UtInstance.start] copies the installation executable verbatim to a
 * name ending in the platform suffix and starts that copy directly, and Windows refuses to start a file with that
 * extension unless its bytes are a real executable. The fixture instead compiles a tiny C# program with the compiler
 * every Windows install ships, once per spec, and copies the compiled binary into each fake installation. On other
 * platforms the fake is a shell script, matching the fixture style of [UtCompilerTest].
 *
 * Either fake echoes its working directory and its arguments into the file named by the `-log=` argument it receives,
 * and exits with the code found on the first line of the file named by the `-ini=` argument, so a fixture chooses the
 * exit code of a run through the content of the installation's game configuration.
 */
class UtInstanceTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstance-test")
        val windows = UT_PLATFORM == UtPlatform.WINDOWS
        val fakeExecutableTemplate = if (windows) compileFakeExecutable(root) else null

        afterSpec { root.toFile().deleteRecursively() }

        /**
         * Writes a fake installation under `<root>/<name>/System`, whose game configuration holds [gameIni] and whose
         * user configuration holds [userIni]. The first line of [gameIni] is the exit code the fake game executable
         * returns.
         */
        fun createFakeInstall(
            name: String,
            gameIni: String,
            userIni: String = "user-config",
        ): UtInstallation {
            val installRoot = root / name
            val systemDirectory = installRoot / "System"
            systemDirectory.createDirectories()

            val executablePath = systemDirectory / UT_PLATFORM.gameExecutable
            if (windows) {
                fakeExecutableTemplate!!.copyTo(executablePath)
            } else {
                executablePath.writeText(FAKE_EXECUTABLE_SHELL_SCRIPT)
                executablePath.toFile().setExecutable(true)
            }

            (systemDirectory / "UnrealTournament.ini").writeText(gameIni)
            (systemDirectory / "User.ini").writeText(userIni)

            return UtInstallation(installRoot, Workspace(directory = root / "tasks"))
        }

        /**
         * Returns every regular file under [directory], keyed by its path relative to it and mapped to its bytes.
         */
        fun snapshot(directory: Path): Map<String, ByteArray> =
            directory
                .toFile()
                .walkTopDown()
                .filter { it.isFile }
                .associate { directory.relativize(it.toPath()).toString() to it.readBytes() }

        val okInstallation = createFakeInstall("ok-install", gameIni = "0")
        val failInstallation = createFakeInstall("fail-install", gameIni = "9")

        context("instance") {
            test("writes nothing to the installation") {
                val before = snapshot(okInstallation.systemDirectory)

                okInstallation.instance("build-only")

                snapshot(okInstallation.systemDirectory) shouldBe before
            }
        }

        context("start") {
            test("creates the executable and ini copies named ut-<id> plus the platform suffix") {
                val instance = okInstallation.instance("start-copies").start()

                instance.executablePath.exists() shouldBe true
                instance.gameIniPath.exists() shouldBe true
                instance.userIniPath.exists() shouldBe true
                instance.executablePath.name shouldBe "ut-${instance.id}${UT_PLATFORM.executableSuffix}"
                instance.gameIniPath.name shouldBe "ut-${instance.id}.ini"
                instance.userIniPath.name shouldBe "ut-${instance.id}.user.ini"

                instance.await()
            }

            test("throws when the instance is already started") {
                val instance = okInstallation.instance("double-start").start()

                shouldThrow<IllegalStateException> { instance.start() }

                instance.await()
            }
        }

        context("await") {
            test("throws when the instance is not started") {
                val instance = okInstallation.instance("await-before-start")

                shouldThrow<IllegalStateException> { instance.await() }
            }

            test("starts the process in the system directory with the run's own ini and log arguments") {
                val instance = okInstallation.instance("argv").start()

                val result = instance.await()

                val expectedArguments =
                    listOf(
                        "-ini=${instance.gameIniPath.absolutePathString()}",
                        "-userini=${instance.userIniPath.absolutePathString()}",
                        "-log=${instance.logPath.name}",
                    ) + UT_PLATFORM.homeArguments

                val cwdLine = result.log.first()
                Path.of(cwdLine.removePrefix("cwd=")).toRealPath() shouldBe okInstallation.systemDirectory.toRealPath()
                result.log.drop(1).dropLast(1) shouldBe expectedArguments.map { "arg=$it" }
            }

            test("starts the process with LD_LIBRARY_PATH set to the installation's System directory") {
                val instance = okInstallation.instance("library-path").start()

                val result = instance.await()

                val envLine = result.log.last()
                Path.of(envLine.removePrefix("env=")).toRealPath() shouldBe okInstallation.systemDirectory.toRealPath()
            }

            test("returns the exit code, the ini contents and an empty exec script for a clean run") {
                val instance = okInstallation.instance("clean-result").start()

                val result = instance.await()

                result.exitCode shouldBe 0
                result.gameIni shouldBe "0"
                result.userIni shouldBe "user-config"
                result.exec shouldBe emptyList()
            }

            test("reads back the ini contents as ISO-8859-1 without truncation") {
                val gameIniContent = "0\nCafé résumé\nÿ ÿ ÿ\n20°C 30°C\n"
                val userIniContent = "Configuration é\nÀ votre santé ÿ\n° symbol °\n"
                val encodingInstallation = createFakeInstall("encoding-install", gameIni = "0")
                encodingInstallation.gameIniPath.writeText(gameIniContent, Charsets.ISO_8859_1)
                encodingInstallation.userIniPath.writeText(userIniContent, Charsets.ISO_8859_1)

                val instance = encodingInstallation.instance("encoding-result").start()

                val result = instance.await()

                result.gameIni shouldBe gameIniContent
                result.userIni shouldBe userIniContent
            }

            test("returns the exit code of a failing run") {
                val instance = failInstallation.instance("failing-result").start()

                val result = instance.await()

                result.exitCode shouldBe 9
            }

            test("removes every file carrying the run's id and leaves the installation otherwise unchanged") {
                val before = snapshot(okInstallation.systemDirectory)

                val instance = okInstallation.instance("cleanup").start()
                instance.await()

                val after = snapshot(okInstallation.systemDirectory)
                after shouldBe before
                after.keys.none { it.contains(instance.id.toString()) } shouldBe true
            }

            test("does not touch the files of another instance started against the same installation") {
                val first = okInstallation.instance("first").start()
                val second = okInstallation.instance("second").start()

                first.await()

                second.executablePath.exists() shouldBe true
                second.gameIniPath.exists() shouldBe true
                second.userIniPath.exists() shouldBe true

                second.await()
            }
        }
    })

/**
 * The fake game executable for platforms without an interpreted shell, echoing its working directory and its
 * arguments into the file named by `-log=`, and exiting with the first line of the file named by `-ini=`.
 */
private const val FAKE_EXECUTABLE_SOURCE =
    """
    using System;
    using System.Collections.Generic;
    using System.IO;
    using System.Text;

    class FakeGame {
        static int Main(string[] args) {
            string logName = null;
            string iniPath = null;
            foreach (var a in args) {
                if (a.StartsWith("-log=")) logName = a.Substring(5);
                if (a.StartsWith("-ini=")) iniPath = a.Substring(5);
            }

            string cwd = Directory.GetCurrentDirectory();
            var lines = new List<string>();
            lines.Add("cwd=" + cwd);
            foreach (var a in args) lines.Add("arg=" + a);
            lines.Add("env=" + (Environment.GetEnvironmentVariable("LD_LIBRARY_PATH") ?? ""));

            var latin1 = Encoding.GetEncoding("ISO-8859-1");
            if (logName != null) {
                File.WriteAllLines(Path.Combine(cwd, logName), lines.ToArray(), latin1);
            }

            int exitCode = 0;
            if (iniPath != null && File.Exists(iniPath)) {
                using (var reader = new StreamReader(iniPath, latin1)) {
                    int.TryParse(reader.ReadLine(), out exitCode);
                }
            }
            return exitCode;
        }
    }
    """

/**
 * The fake game executable for platforms with a POSIX shell, matching [FAKE_EXECUTABLE_SOURCE].
 */
private val FAKE_EXECUTABLE_SHELL_SCRIPT =
    """
    #!/bin/sh
    LOG_NAME=""
    INI_PATH=""
    for arg in "${'$'}@"; do
      case "${'$'}arg" in
        -log=*) LOG_NAME="${'$'}{arg#-log=}" ;;
        -ini=*) INI_PATH="${'$'}{arg#-ini=}" ;;
      esac
    done
    CWD=${'$'}(pwd)
    if [ -n "${'$'}LOG_NAME" ]; then
      {
        echo "cwd=${'$'}CWD"
        for arg in "${'$'}@"; do echo "arg=${'$'}arg"; done
        echo "env=${'$'}LD_LIBRARY_PATH"
      } > "${'$'}CWD/${'$'}LOG_NAME"
    fi
    EXIT_CODE=0
    if [ -n "${'$'}INI_PATH" ] && [ -f "${'$'}INI_PATH" ]; then
      EXIT_CODE=${'$'}(head -n 1 "${'$'}INI_PATH")
    fi
    exit "${'$'}{EXIT_CODE:-0}"
    """.trimIndent()

/**
 * Compiles [FAKE_EXECUTABLE_SOURCE] into `<root>/fakegame.exe` using the C# compiler shipped with every Windows
 * install since .NET Framework 4, and returns its path.
 */
private fun compileFakeExecutable(root: Path): Path {
    val sourcePath = root / "FakeGame.cs"
    val outputPath = root / "fakegame.exe"
    sourcePath.writeText(FAKE_EXECUTABLE_SOURCE.trimIndent())

    val process =
        ProcessBuilder(
            findCsc().toString(),
            "/nologo",
            "/target:exe",
            "/out:$outputPath",
            "$sourcePath",
        ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Failed to compile the fake game executable: $output" }
    return outputPath
}

/**
 * Returns the path of the C# compiler shipped with the .NET Framework, present on every supported Windows install.
 */
private fun findCsc(): Path {
    val windir = System.getenv("WINDIR") ?: """C:\Windows"""
    val candidates =
        listOf(
            """$windir\Microsoft.NET\Framework64\v4.0.30319\csc.exe""",
            """$windir\Microsoft.NET\Framework\v4.0.30319\csc.exe""",
        )
    return candidates.map(Path::of).firstOrNull { it.exists() }
        ?: error("No csc.exe found under $windir\\Microsoft.NET")
}
