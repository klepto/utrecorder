package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class UtInstanceTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstance-test")
        val testEnvVar = "UTINSTANCE_TEST_VAR"
        val sleepVar = "UTINSTANCE_SLEEP"
        val shortTimeout = 1.seconds
        val longTimeout = 6.seconds

        val windowsScript =
            """
            @echo off
            setlocal enabledelayedexpansion
            echo start-marker
            set "CWD=%CD%"
            set "LOG_NAME="
            set "INI_PATH="
            set "ARGS_FILE=%TEMP%\utinstance-args-%RANDOM%-%RANDOM%.tmp"
            type nul > "%ARGS_FILE%"
            set "REMAINING=%*"
            :collect
            if not defined REMAINING goto :afterCollect
            set "TOKEN="
            set "NEWREM="
            for /f "tokens=1,* delims= " %%A in ("!REMAINING!") do (
              set "TOKEN=%%A"
              set "NEWREM=%%B"
            )
            echo(!TOKEN!>>"%ARGS_FILE%"
            if "!TOKEN:~0,5!"=="-log=" set "LOG_NAME=!TOKEN:~5!"
            if "!TOKEN:~0,5!"=="-ini=" set "INI_PATH=!TOKEN:~5!"
            set "REMAINING=%NEWREM%"
            goto :collect
            :afterCollect
            if defined LOG_NAME (
              > "%CWD%\%LOG_NAME%" (
                echo cwd=%CWD%
                for /f "usebackq delims=" %%L in ("%ARGS_FILE%") do echo arg=%%L
                echo env=!LD_LIBRARY_PATH!
                echo envtest=!$testEnvVar!
              )
            )
            del "%ARGS_FILE%" >nul 2>&1
            if defined $sleepVar ping -n %$sleepVar% 127.0.0.1 >nul
            set "EXIT_CODE=0"
            set "GOT_CODE="
            if defined INI_PATH (
              for /f "usebackq delims=" %%L in ("%INI_PATH%") do (
                if not defined GOT_CODE (
                  set "EXIT_CODE=%%L"
                  set "GOT_CODE=1"
                )
              )
            )
            exit /b %EXIT_CODE%
            """.trimIndent()

        val linuxScript =
            """
            #!/bin/sh
            echo start-marker
            CWD=${'$'}(pwd)
            LOG_NAME=""
            INI_PATH=""
            for arg in "${'$'}@"; do
              case "${'$'}arg" in
                -log=*) LOG_NAME="${'$'}{arg#-log=}" ;;
                -ini=*) INI_PATH="${'$'}{arg#-ini=}" ;;
              esac
            done
            if [ -n "${'$'}LOG_NAME" ]; then
              {
                echo "cwd=${'$'}CWD"
                for arg in "${'$'}@"; do echo "arg=${'$'}arg"; done
                echo "env=${'$'}LD_LIBRARY_PATH"
                echo "envtest=${'$'}$testEnvVar"
              } > "${'$'}CWD/${'$'}LOG_NAME"
            fi
            if [ -n "${'$'}$sleepVar" ]; then sleep "${'$'}$sleepVar"; fi
            EXIT_CODE=0
            if [ -n "${'$'}INI_PATH" ] && [ -f "${'$'}INI_PATH" ]; then
              EXIT_CODE=${'$'}(head -n 1 "${'$'}INI_PATH")
            fi
            exit "${'$'}{EXIT_CODE:-0}"
            """.trimIndent()

        val windowsHeartbeatScript =
            """
            @echo off
            setlocal enabledelayedexpansion
            set "HEARTBEAT=%~1"
            for /l %%i in (1,1,50) do (
              echo %%i>>"%HEARTBEAT%"
              ping -n 2 127.0.0.1 >nul
            )
            """.trimIndent()

        val linuxHeartbeatScript =
            """
            #!/bin/sh
            HEARTBEAT="${'$'}1"
            i=0
            while [ ${'$'}i -lt 50 ]; do
              i=${'$'}((i + 1))
              echo "${'$'}i" >> "${'$'}HEARTBEAT"
              sleep 1
            done
            """.trimIndent()

        data class Shell(
            val executable: Path,
            val arguments: List<String>,
            val sleepSeconds: String,
        )

        fun writeScript(
            name: String,
            script: String,
        ): String = (root / name).also { it.writeText(script) }.toString()

        fun writeShell(): Shell =
            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    Shell(
                        executable = Path.of(System.getenv("ComSpec") ?: """C:\Windows\System32\cmd.exe"""),
                        arguments = listOf("/c", writeScript("runner.cmd", windowsScript)),
                        sleepSeconds = "3",
                    )
                }

                UtPlatform.LINUX -> {
                    Shell(
                        executable = Path.of("/bin/sh"),
                        arguments = listOf(writeScript("runner.sh", linuxScript)),
                        sleepSeconds = "2",
                    )
                }
            }

        fun heartbeatShell(heartbeat: Path): Shell =
            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    Shell(
                        executable = Path.of(System.getenv("ComSpec") ?: """C:\Windows\System32\cmd.exe"""),
                        arguments = listOf("/c", writeScript("heartbeat.cmd", windowsHeartbeatScript), "$heartbeat"),
                        sleepSeconds = "",
                    )
                }

                UtPlatform.LINUX -> {
                    Shell(
                        executable = Path.of("/bin/sh"),
                        arguments = listOf(writeScript("heartbeat.sh", linuxHeartbeatScript), "$heartbeat"),
                        sleepSeconds = "",
                    )
                }
            }

        val shell = writeShell()

        afterSpec { root.toFile().deleteRecursively() }

        suspend fun waitUntil(
            timeout: Duration = 2.seconds,
            condition: () -> Boolean,
        ) {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (!condition()) {
                check(deadline.hasNotPassedNow()) { "Timed out waiting for condition" }
                delay(20.milliseconds)
            }
        }

        fun createFakeInstall(
            name: String,
            exitCode: Int = 0,
            gameIni: String = "",
            userIni: String = "",
        ): UtInstallation {
            val installRoot = root / name
            val systemDirectory = installRoot / "System"
            systemDirectory.createDirectories()

            val gameIniContent =
                when {
                    gameIni.isEmpty() -> "$exitCode"
                    else -> "$exitCode\n$gameIni"
                }

            (systemDirectory / UtPlatform.current().gameExecutable).writeText("")
            (systemDirectory / "UnrealTournament.ini").writeText(gameIniContent)
            (systemDirectory / "User.ini").writeText(userIni)

            return UtInstallation(installRoot, Directories(root / "home"))
        }

        fun snapshot(directory: Path): Map<String, ByteArray> =
            directory
                .toFile()
                .walkTopDown()
                .filter { it.isFile }
                .associate { directory.relativize(it.toPath()).toString() to it.readBytes() }

        fun keptDirectory(name: String): Path =
            (root / "home" / "workspace")
                .listDirectoryEntries()
                .single { it.name.matches(Regex("""${Regex.escape(name)}-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}""")) }

        suspend fun scriptInstance(
            installation: UtInstallation,
            name: String,
            configure: UtInstance.Builder.() -> Unit = {},
        ): UtInstance =
            installation.instance(name) {
                executable = shell.executable
                arguments += shell.arguments
                configure()
            }

        val sleepingConfig: UtInstance.Builder.() -> Unit = { environment[sleepVar] = shell.sleepSeconds }

        fun gameArgLines(result: UtInstanceResult): List<String> = result.log.filter { it.startsWith("arg=") }.drop(1)

        fun strippedGameArgLines(result: UtInstanceResult): List<String> = gameArgLines(result).map { it.removePrefix("arg=") }

        val installation = createFakeInstall("install")

        context("instance") {
            test("throws for a name outside [A-Za-z0-9._-]+ and creates nothing under the workspace directory") {
                val workspaceDirectory = root / "home" / "workspace"
                val before = workspaceDirectory.takeIf { it.exists() }?.listDirectoryEntries().orEmpty()

                shouldThrow<IllegalArgumentException> { installation.instance("bad/name") }
                shouldThrow<IllegalArgumentException> { installation.instance("") }

                workspaceDirectory.takeIf { it.exists() }?.listDirectoryEntries().orEmpty() shouldBe before
            }

            test("writes nothing to the installation") {
                val before = snapshot(installation.systemDirectory)

                installation.instance("build-only")

                snapshot(installation.systemDirectory) shouldBe before
            }

            test("throws when arguments are set without an executable override") {
                shouldThrow<IllegalArgumentException> {
                    installation.instance("no-executable-for-arguments") { arguments += "-something" }
                }
            }

            test("names the run's paths in System as named-paths-<id>- followed by each file name") {
                val instance = installation.instance("named-paths") { exec = listOf("line") }
                val systemDirectory = installation.systemDirectory.toAbsolutePath()
                val prefix = """named-paths-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-"""
                val paths =
                    listOf(
                        instance.executablePath to UtPlatform.current().gameExecutable,
                        instance.gameIniPath to "UnrealTournament.ini",
                        instance.userIniPath to "User.ini",
                        instance.execPath to "exec.txt",
                        instance.logPath to "UnrealTournament.log",
                    )

                paths.forEach { (path, fileName) ->
                    path.parent shouldBe systemDirectory
                    path.name shouldMatch Regex(prefix + Regex.escape(fileName))
                }
            }

            test("gives two instances of the same name built back to back different paths") {
                val first = installation.instance("same-name")
                val second = installation.instance("same-name")

                first.executablePath shouldNotBe second.executablePath
                first.gameIniPath shouldNotBe second.gameIniPath
                first.userIniPath shouldNotBe second.userIniPath
                first.execPath shouldNotBe second.execPath
                first.logPath shouldNotBe second.logPath
            }
        }

        context("executable") {
            test("runs the executable copy directly with no wrapper arguments when not overridden") {
                val instance = installation.instance("default-command-shape")

                instance.executable.executablePath shouldBe instance.executablePath
                instance.executable.arguments.take(4) shouldBe
                    listOf(
                        "-nojoy",
                        "-fullscreen",
                        "-ini=${instance.gameIniPath.absolutePathString()}",
                        "-userini=${instance.userIniPath.absolutePathString()}",
                    )
            }

            test(
                "runs the override with the caller's arguments then the executable copy path then the game arguments",
            ) {
                val overridePath = root / "wrapper" / "wrapper.exe"
                val instance =
                    installation.instance("override-command-shape") {
                        executable = overridePath
                        arguments += listOf("--attach", "-v")
                        map = "DM-Deck16"
                    }

                instance.executable.executablePath shouldBe overridePath
                instance.executable.arguments.take(6) shouldBe
                    listOf(
                        "--attach",
                        "-v",
                        instance.executablePath.absolutePathString(),
                        "DM-Deck16",
                        "-nojoy",
                        "-fullscreen",
                    )
            }

            test("puts the map first in the game arguments") {
                val instance = installation.instance("map-first") { map = "DM-Deck16" }

                instance.executable.arguments.first() shouldBe "DM-Deck16"
            }

            test("adds -nosound when sound is disabled") {
                val instance = installation.instance("no-sound") { sound = false }

                instance.executable.arguments.take(3) shouldBe listOf("-nosound", "-nojoy", "-fullscreen")
            }

            test("adds -nojoy when joystick is disabled") {
                val instance = installation.instance("no-joystick")

                instance.executable.arguments.take(1) shouldBe listOf("-nojoy")
            }

            test("drops -nojoy when joystick is enabled") {
                val instance = installation.instance("joystick") { joystick = true }

                instance.executable.arguments.take(1) shouldBe listOf("-fullscreen")
            }

            test("uses -windowed instead of -fullscreen when windowed is enabled") {
                val instance = installation.instance("windowed") { windowed = true }

                instance.executable.arguments.take(2) shouldBe listOf("-nojoy", "-windowed")
            }

            test("appends -EXEC only when exec is non-empty") {
                val withExec =
                    installation.instance("with-exec") {
                        exec = listOf("SETRES 1920x1080", "PLAYDEMO test.demo")
                    }
                val withoutExec = installation.instance("without-exec")

                withExec.executable.arguments shouldBe
                    listOf(
                        "-nojoy",
                        "-fullscreen",
                        "-ini=${withExec.gameIniPath.absolutePathString()}",
                        "-userini=${withExec.userIniPath.absolutePathString()}",
                        "-EXEC=${withExec.execPath.absolutePathString()}",
                        "-log=${withExec.logPath.name}",
                    ) + UtPlatform.current().homeArguments
                withoutExec.executable.arguments.none { it.startsWith("-EXEC=") } shouldBe true
            }

            test("assembles the default game arguments in full, including the platform's home arguments") {
                val instance = installation.instance("full-argv")

                instance.executable.arguments shouldBe
                    listOf(
                        "-nojoy",
                        "-fullscreen",
                        "-ini=${instance.gameIniPath.absolutePathString()}",
                        "-userini=${instance.userIniPath.absolutePathString()}",
                        "-log=${instance.logPath.name}",
                    ) + UtPlatform.current().homeArguments
            }

            test("runs in the installation's system directory") {
                val instance = installation.instance("working-directory")

                instance.executable.workingDirectory shouldBe installation.systemDirectory.toAbsolutePath()
            }

            test("passes the caller's environment variables through") {
                val instance =
                    installation.instance("environment") {
                        environment["UTINSTANCE_TEST_VAR"] = "hello-world"
                    }

                instance.executable.environment["UTINSTANCE_TEST_VAR"] shouldBe "hello-world"
            }

            test("sets the platform's library path variable to the system directory when the platform has one") {
                val instance = installation.instance("library-path")

                val variable = OperatingSystem.current().libraryPathVariable
                if (variable != null) {
                    instance.executable.environment[variable] shouldBe "${installation.systemDirectory.toAbsolutePath()}"
                } else {
                    instance.executable.environment.containsKey("LD_LIBRARY_PATH") shouldBe false
                }
            }
        }

        context("play") {
            val okInstallation = createFakeInstall("ok-install", userIni = "user-config")
            val failInstallation = createFakeInstall("fail-install", exitCode = 9)

            test("starts the process in the system directory with the run's default game, ini and log arguments") {
                val instance = scriptInstance(okInstallation, "argv")

                val result = instance.play { }

                val expectedArguments =
                    listOf(
                        "-nojoy",
                        "-fullscreen",
                        "-ini=${instance.gameIniPath.absolutePathString()}",
                        "-userini=${instance.userIniPath.absolutePathString()}",
                        "-log=${instance.logPath.name}",
                    ) + UtPlatform.current().homeArguments

                val cwdLine = result.log.first()
                Path.of(cwdLine.removePrefix("cwd=")).toRealPath() shouldBe okInstallation.systemDirectory.toRealPath()
                gameArgLines(result) shouldBe expectedArguments.map { "arg=$it" }
            }

            test("writes the exec lines as the run's own exec file, passes it as -EXEC and returns it in the result") {
                val instance =
                    scriptInstance(okInstallation, "exec-argument") {
                        exec = listOf("SETRES 1920x1080", "PLAYDEMO test.demo")
                    }
                val execArgument = "-EXEC=${instance.execPath.absolutePathString()}"

                val result = instance.play { }

                strippedGameArgLines(result) shouldContain execArgument
                result.exec shouldBe listOf("SETRES 1920x1080", "PLAYDEMO test.demo")
            }

            test("passes no -EXEC argument and returns an empty exec result when exec is empty") {
                val instance = scriptInstance(okInstallation, "no-exec")

                val result = instance.play { }

                gameArgLines(result).none { it.contains("-EXEC=") } shouldBe true
                result.exec shouldBe emptyList()
            }

            test("deletes the exec file along with the run's other files after play returns") {
                val before = snapshot(okInstallation.systemDirectory)
                val instance = scriptInstance(okInstallation, "exec-cleanup") { exec = listOf("line") }
                val execPath = instance.execPath

                instance.play { }

                execPath.exists() shouldBe false
                snapshot(okInstallation.systemDirectory) shouldBe before
            }

            test("passes environment variables set on the builder to the process") {
                val instance =
                    scriptInstance(okInstallation, "environment") {
                        environment[testEnvVar] = "hello-world"
                    }

                val result = instance.play { }

                result.log.first { it.startsWith("envtest=") } shouldBe "envtest=hello-world"
            }

            test(
                "seeds the ini copies from the installation, applies the builder's edits and leaves the installation's own ini unchanged",
            ) {
                val iniEditInstallation =
                    createFakeInstall(
                        "ini-edit-install",
                        gameIni = "[Engine.Engine]\nInstallerKey=InstallerValue\n",
                        userIni = "[Engine.Engine]\nInstallerUserKey=InstallerUserValue\n",
                    )
                val gameIniBefore = iniEditInstallation.gameIniPath.readTextOrEmpty(UtPlatform.CHARSET)
                val userIniBefore = iniEditInstallation.userIniPath.readTextOrEmpty(UtPlatform.CHARSET)

                val instance =
                    scriptInstance(iniEditInstallation, "ini-edits") {
                        gameIni { set("Engine.Engine", "BuilderKey", "BuilderValue") }
                        userIni { set("Engine.Engine", "BuilderUserKey", "BuilderUserValue") }
                    }

                val result = instance.play { }

                result.gameIni shouldContain "InstallerKey=InstallerValue"
                result.gameIni shouldContain "BuilderKey=BuilderValue"
                result.userIni shouldContain "InstallerUserKey=InstallerUserValue"
                result.userIni shouldContain "BuilderUserKey=BuilderUserValue"
                iniEditInstallation.gameIniPath.readTextOrEmpty(UtPlatform.CHARSET) shouldBe gameIniBefore
                iniEditInstallation.userIniPath.readTextOrEmpty(UtPlatform.CHARSET) shouldBe userIniBefore
            }

            test("sets the platform's library path variable to the System directory, unset when the platform has none") {
                val instance = scriptInstance(okInstallation, "library-path")

                val result = instance.play { }

                val envLine = result.log.first { it.startsWith("env=") }
                if (OperatingSystem.current().libraryPathVariable != null) {
                    Path.of(envLine.removePrefix("env=")).toRealPath() shouldBe okInstallation.systemDirectory.toRealPath()
                } else {
                    envLine shouldBe "env="
                }
            }

            test("returns the exit code, the ini contents and an empty exec script for a clean run") {
                val instance = scriptInstance(okInstallation, "clean-result")

                val result = instance.play { }

                result.exitCode shouldBe 0
                result.gameIni shouldBe "0"
                result.userIni shouldBe "user-config"
                result.exec shouldBe emptyList()
            }

            test("reads back the ini contents as ISO-8859-1 without truncation") {
                val gameIniContent = "0\nCafé résumé\nÿ ÿ ÿ\n20°C 30°C\n"
                val userIniContent = "Configuration é\nÀ votre santé ÿ\n° symbol °\n"
                val encodingInstallation = createFakeInstall("encoding-install")
                encodingInstallation.gameIniPath.writeText(gameIniContent, Charsets.ISO_8859_1)
                encodingInstallation.userIniPath.writeText(userIniContent, Charsets.ISO_8859_1)

                val instance = scriptInstance(encodingInstallation, "encoding-result")

                val result = instance.play { }

                result.gameIni shouldBe gameIniContent
                result.userIni shouldBe userIniContent
            }

            test("returns the exit code of a failing run") {
                val instance = scriptInstance(failInstallation, "failing-result")

                val result = instance.play { }

                result.exitCode shouldBe 9
            }

            test("leaves System as it was and no workspace directory for the run after a clean run is played") {
                val before = snapshot(okInstallation.systemDirectory)

                scriptInstance(okInstallation, "clean-cleanup").play { }

                snapshot(okInstallation.systemDirectory) shouldBe before
                (root / "home" / "workspace")
                    .listDirectoryEntries()
                    .none { it.name.startsWith("clean-cleanup-") } shouldBe true
            }

            test("does not touch the files of another instance played concurrently against the same installation") {
                val second = scriptInstance(okInstallation, "second", sleepingConfig)

                coroutineScope {
                    val secondJob = launch { second.play(longTimeout) { } }
                    waitUntil { second.executablePath.exists() }

                    scriptInstance(okInstallation, "first").play { }

                    second.executablePath.exists() shouldBe true
                    second.gameIniPath.exists() shouldBe true
                    second.userIniPath.exists() shouldBe true

                    secondJob.join()
                }
            }

            test("throws the failure of deleting an executable copy held open")
                .config(enabledIf = { OperatingSystem.current() == OperatingSystem.WINDOWS }) {
                    val instance = scriptInstance(okInstallation, "held-executable")
                    lateinit var stream: FileOutputStream

                    try {
                        shouldThrow<IOException> {
                            instance.play { stream = FileOutputStream(instance.executablePath.toFile(), true) }
                        }
                    } finally {
                        stream.close()
                        instance.executablePath.deleteIfExists()
                    }
                }

            test("returns a result rather than throwing when a run outlives its timeout, still carrying the inis") {
                val instance = scriptInstance(okInstallation, "timeout-result", sleepingConfig)

                val result = instance.play(shortTimeout) { }

                result.log.isNotEmpty() shouldBe true
                result.gameIni shouldBe "0"
                result.userIni shouldBe "user-config"
                result.exitCode shouldBe -1
            }

            test("leaves System as it was after a run that outlives its timeout is played") {
                val before = snapshot(okInstallation.systemDirectory)

                scriptInstance(okInstallation, "timeout-cleanup", sleepingConfig).play(shortTimeout) { }

                snapshot(okInstallation.systemDirectory) shouldBe before
            }

            test("leaves System as it was after a run exiting with a non-zero code is played") {
                val before = snapshot(failInstallation.systemDirectory)

                scriptInstance(failInstallation, "non-zero-cleanup").play { }

                snapshot(failInstallation.systemDirectory) shouldBe before
            }

            test("removes the inis and log of a run whose held executable copy fails to delete")
                .config(enabledIf = { OperatingSystem.current() == OperatingSystem.WINDOWS }) {
                    val instance = scriptInstance(okInstallation, "held-executable-removal")
                    lateinit var stream: FileOutputStream

                    try {
                        shouldThrow<IOException> {
                            instance.play { stream = FileOutputStream(instance.executablePath.toFile(), true) }
                        }

                        instance.gameIniPath.exists() shouldBe false
                        instance.userIniPath.exists() shouldBe false
                        instance.logPath.exists() shouldBe false
                    } finally {
                        stream.close()
                        instance.executablePath.deleteIfExists()
                    }
                }

            test("rethrows a failure to read the run's files back and keeps its inis and log in the workspace directory") {
                val before = snapshot(okInstallation.systemDirectory)
                val instance = scriptInstance(okInstallation, "read-failure")
                instance.logPath.createDirectories()

                shouldThrow<IOException> { instance.play { } }

                snapshot(okInstallation.systemDirectory) shouldBe before
                val kept = keptDirectory("read-failure")
                (kept / "UnrealTournament.ini").exists() shouldBe true
                (kept / "User.ini").exists() shouldBe true
                (kept / "UnrealTournament.log").exists() shouldBe true
                (kept / UtPlatform.current().gameExecutable).exists() shouldBe false
            }

            test("rethrows a failure to start a program that does not exist and keeps the inis but no executable copy") {
                val before = snapshot(okInstallation.systemDirectory)
                val instance =
                    okInstallation.instance("launch-failure") {
                        executable = root / "missing-program" / "program.exe"
                    }

                shouldThrow<IOException> { instance.play { } }

                snapshot(okInstallation.systemDirectory) shouldBe before
                val kept = keptDirectory("launch-failure")
                (kept / "UnrealTournament.ini").exists() shouldBe true
                (kept / "User.ini").exists() shouldBe true
                (kept / UtPlatform.current().gameExecutable).exists() shouldBe false
            }

            test("rethrows a failure to write the run's files when User.ini is missing and keeps the game ini") {
                val missingUserIniInstallation = createFakeInstall("missing-user-ini-install")
                missingUserIniInstallation.userIniPath.deleteIfExists()
                val before = snapshot(missingUserIniInstallation.systemDirectory)
                val instance = scriptInstance(missingUserIniInstallation, "write-failure")

                shouldThrow<IllegalStateException> { instance.play { } }.message shouldContain "User.ini"

                snapshot(missingUserIniInstallation.systemDirectory) shouldBe before
                (keptDirectory("write-failure") / "UnrealTournament.ini").exists() shouldBe true
            }
        }

        context("cancellation") {
            test("cancelling the coroutine running play { } leaves no process alive and removes the run's files") {
                val heartbeat = root / "cancel-heartbeat.txt"
                heartbeat.deleteIfExists()
                val shellCancel = heartbeatShell(heartbeat)
                val instance =
                    installation.instance("cancel-play") {
                        executable = shellCancel.executable
                        arguments += shellCancel.arguments
                    }

                coroutineScope {
                    val job = launch { instance.play { } }
                    waitUntil { heartbeat.exists() }
                    job.cancelAndJoin()
                }

                val beatsAtCancel = heartbeat.readLines().size
                delay(1500.milliseconds)
                heartbeat.readLines().size shouldBe beatsAtCancel

                instance.executablePath.exists() shouldBe false
                instance.gameIniPath.exists() shouldBe false
                instance.userIniPath.exists() shouldBe false
            }
        }

        context("drain") {
            val okInstallation = createFakeInstall("drain-install", userIni = "user-config")

            test("receives output while the run is in progress") {
                val instance = scriptInstance(okInstallation, "drain-live", sleepingConfig)
                val received = mutableListOf<String>()

                instance.play(longTimeout) { drain().collect { received += it } }

                received.any { it.contains("start-marker") } shouldBe true
            }

            test(
                "a collector that starts inside the block after lines were already written still sees them, through the replay guarantee",
            ) {
                val instance = scriptInstance(okInstallation, "drain-replay")
                val received = mutableListOf<String>()

                instance.play {
                    delay(200.milliseconds)
                    received += drain().toList()
                }

                received.any { it.contains("start-marker") } shouldBe true
            }
        }
    })
