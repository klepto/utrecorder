package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.readTextOrEmpty
import dev.klepto.utrecorder.common.os.OS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [UtInstance] against a fake installation.
 *
 * The `instance` and `executable` contexts never start a process: they read the inert [UtInstance.executable] built
 * off the run's own paths, and the fake installation keeps a placeholder game executable, an empty regular file at
 * `System/<UT_PLATFORM.gameExecutable>`, only because [UtInstallation]'s init block checks that the path exists. It
 * is never executed there.
 *
 * The `start`, `await`, `drain` and `isRunning` contexts start a process for real, but never the game: every one of
 * them overrides the program a run starts to the [Shell] running [WINDOWS_SCRIPT] or [LINUX_SCRIPT], written once per
 * spec under [root] rather than under an installation's `System` directory, so the byte-identical-installation
 * assertions elsewhere keep working. The instance still appends the game executable copy path and the game
 * arguments after the shell's own wrapper arguments, so the script sees the copy path as its first argument and
 * ignores it. The game executable copy itself is never run: it is a placeholder the shell never touches.
 */
class UtInstanceTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstance-test")
        val shell =
            when (UT_PLATFORM) {
                UtPlatform.WINDOWS -> {
                    val scriptPath = root / "runner.cmd"
                    scriptPath.writeText(WINDOWS_SCRIPT)
                    Shell(
                        executable = Path.of(System.getenv("ComSpec") ?: """C:\Windows\System32\cmd.exe"""),
                        arguments = listOf("/c", scriptPath.toString()),
                        script = WINDOWS_SCRIPT,
                        sleepSeconds = WINDOWS_SLEEP_SECONDS,
                    )
                }
                UtPlatform.LINUX -> {
                    val scriptPath = root / "runner.sh"
                    scriptPath.writeText(LINUX_SCRIPT)
                    Shell(
                        executable = Path.of("/bin/sh"),
                        arguments = listOf(scriptPath.toString()),
                        script = LINUX_SCRIPT,
                        sleepSeconds = LINUX_SLEEP_SECONDS,
                    )
                }
            }

        afterSpec { root.toFile().deleteRecursively() }

        /**
         * Writes a fake installation under `<root>/<name>/System`, holding a placeholder game executable and a game
         * and user configuration. The game configuration begins with [exitCode] as its first line, which a fake run
         * reads back and exits with, followed by [gameIni]; the user configuration holds [userIni] as is.
         */
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

            (systemDirectory / UT_PLATFORM.gameExecutable).writeText("")
            (systemDirectory / "UnrealTournament.ini").writeText(gameIniContent)
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

        /**
         * Returns an unstarted run of [installation], overridden to start [shell] rather than the game.
         */
        fun scriptInstance(
            installation: UtInstallation,
            name: String,
            configure: UtInstanceBuilder.() -> Unit = {},
        ): UtInstance =
            installation.instance(name) {
                executable = shell.executable
                arguments += shell.arguments
                configure()
            }

        /**
         * Configures an instance to sleep for [Shell.sleepSeconds] once its fake run has written its log, using
         * [SLEEP_VAR], so a test can make a run outlive its timeout.
         */
        val sleepingConfig: UtInstanceBuilder.() -> Unit = { environment[SLEEP_VAR] = shell.sleepSeconds }

        /**
         * Returns the `arg=` lines a fake run wrote to its log, one per argument the shell received. The first is
         * always the game executable copy path, since the shell runs in front of the game rather than in its place.
         */
        fun argLines(result: UtInstanceResult): List<String> = result.log.filter { it.startsWith("arg=") }

        /**
         * Returns [argLines] with the leading game executable copy path dropped, so what remains lines up with
         * [UtInstance.executable]'s own argument list.
         */
        fun gameArgLines(result: UtInstanceResult): List<String> = argLines(result).drop(1)

        /**
         * Returns [gameArgLines] with the `arg=` prefix stripped off each line.
         */
        fun strippedGameArgLines(result: UtInstanceResult): List<String> =
            gameArgLines(result).map { it.removePrefix("arg=") }

        val installation = createFakeInstall("install")

        context("instance") {
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

            test("names the run's paths ut-<id> plus the platform suffix, .ini, .user.ini, .exec.txt and .log") {
                val instance = installation.instance("named-paths") { exec = listOf("line") }

                instance.executablePath.name shouldBe "ut-${instance.id}${UT_PLATFORM.executableSuffix}"
                instance.gameIniPath.name shouldBe "ut-${instance.id}.ini"
                instance.userIniPath.name shouldBe "ut-${instance.id}.user.ini"
                instance.execPath.name shouldBe "ut-${instance.id}.exec.txt"
                instance.logPath.name shouldBe "ut-${instance.id}.log"
            }

            test("gives two instances built back to back different ids") {
                val first = installation.instance("first-id")
                val second = installation.instance("second-id")

                first.id shouldNotBe second.id
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
                    ) + UT_PLATFORM.homeArguments
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
                    ) + UT_PLATFORM.homeArguments
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

                val variable = OS.libraryPathVariable
                if (variable != null) {
                    instance.executable.environment[variable] shouldBe "${installation.systemDirectory.toAbsolutePath()}"
                } else {
                    instance.executable.environment.containsKey("LD_LIBRARY_PATH") shouldBe false
                }
            }
        }

        context("start and await") {
            val okInstallation = createFakeInstall("ok-install", userIni = "user-config")
            val failInstallation = createFakeInstall("fail-install", exitCode = 9)

            test("creates the executable and ini copies named ut-<id> plus the platform suffix") {
                val instance = scriptInstance(okInstallation, "start-copies").start()

                instance.executablePath.exists() shouldBe true
                instance.gameIniPath.exists() shouldBe true
                instance.userIniPath.exists() shouldBe true
                instance.executablePath.name shouldBe "ut-${instance.id}${UT_PLATFORM.executableSuffix}"
                instance.gameIniPath.name shouldBe "ut-${instance.id}.ini"
                instance.userIniPath.name shouldBe "ut-${instance.id}.user.ini"

                instance.await()
            }

            test("throws when the instance is already started") {
                val instance = scriptInstance(okInstallation, "double-start").start()

                shouldThrow<IllegalStateException> { instance.start() }

                instance.await()
            }

            test("throws when awaited before start") {
                val instance = scriptInstance(okInstallation, "await-before-start")

                shouldThrow<IllegalStateException> { instance.await() }
            }

            test("starts the process in the system directory with the run's default game, ini and log arguments") {
                val instance = scriptInstance(okInstallation, "argv").start()

                val result = instance.await()

                val expectedArguments =
                    listOf(
                        "-nojoy",
                        "-fullscreen",
                        "-ini=${instance.gameIniPath.absolutePathString()}",
                        "-userini=${instance.userIniPath.absolutePathString()}",
                        "-log=${instance.logPath.name}",
                    ) + UT_PLATFORM.homeArguments

                val cwdLine = result.log.first()
                Path.of(cwdLine.removePrefix("cwd=")).toRealPath() shouldBe okInstallation.systemDirectory.toRealPath()
                gameArgLines(result) shouldBe expectedArguments.map { "arg=$it" }
            }

            test("puts the map first in the game arguments") {
                val instance = scriptInstance(okInstallation, "map-first") { map = "DM-Deck16" }.start()

                val result = instance.await()

                gameArgLines(result).first() shouldBe "arg=DM-Deck16"
            }

            test("adds -nosound when sound is disabled") {
                val instance = scriptInstance(okInstallation, "no-sound") { sound = false }.start()

                val result = instance.await()

                strippedGameArgLines(result).take(3) shouldBe listOf("-nosound", "-nojoy", "-fullscreen")
            }

            test("swaps -fullscreen for -windowed when windowed is enabled") {
                val instance = scriptInstance(okInstallation, "windowed") { windowed = true }.start()

                val result = instance.await()

                strippedGameArgLines(result).take(2) shouldBe listOf("-nojoy", "-windowed")
            }

            test("drops -nojoy when joystick is enabled") {
                val instance = scriptInstance(okInstallation, "joystick") { joystick = true }.start()

                val result = instance.await()

                strippedGameArgLines(result).take(1) shouldBe listOf("-fullscreen")
            }

            test("writes the exec lines as the run's own exec file, passes it as -EXEC and returns it in the result") {
                val instance =
                    scriptInstance(okInstallation, "exec-argument") {
                        exec = listOf("SETRES 1920x1080", "PLAYDEMO test.demo")
                    }.start()
                val execArgument = "-EXEC=${instance.execPath.absolutePathString()}"

                val result = instance.await()

                strippedGameArgLines(result) shouldContain execArgument
                result.exec shouldBe listOf("SETRES 1920x1080", "PLAYDEMO test.demo")
            }

            test("passes no -EXEC argument and returns an empty exec result when exec is empty") {
                val instance = scriptInstance(okInstallation, "no-exec").start()

                val result = instance.await()

                gameArgLines(result).none { it.contains("-EXEC=") } shouldBe true
                result.exec shouldBe emptyList()
            }

            test("deletes the exec file along with the run's other files after await") {
                val before = snapshot(okInstallation.systemDirectory)
                val instance = scriptInstance(okInstallation, "exec-cleanup") { exec = listOf("line") }.start()
                val execPath = instance.execPath

                instance.await()

                execPath.exists() shouldBe false
                snapshot(okInstallation.systemDirectory) shouldBe before
            }

            test("passes environment variables set on the builder to the process") {
                val instance =
                    scriptInstance(okInstallation, "environment") {
                        environment[TEST_ENV_VAR] = "hello-world"
                    }.start()

                val result = instance.await()

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
                val gameIniBefore = iniEditInstallation.gameIniPath.readTextOrEmpty(UtIni.CHARSET)
                val userIniBefore = iniEditInstallation.userIniPath.readTextOrEmpty(UtIni.CHARSET)

                val instance =
                    scriptInstance(iniEditInstallation, "ini-edits") {
                        gameIni { set("Engine.Engine", "BuilderKey", "BuilderValue") }
                        userIni { set("Engine.Engine", "BuilderUserKey", "BuilderUserValue") }
                    }.start()

                val result = instance.await()

                result.gameIni shouldContain "InstallerKey=InstallerValue"
                result.gameIni shouldContain "BuilderKey=BuilderValue"
                result.userIni shouldContain "InstallerUserKey=InstallerUserValue"
                result.userIni shouldContain "BuilderUserKey=BuilderUserValue"
                iniEditInstallation.gameIniPath.readTextOrEmpty(UtIni.CHARSET) shouldBe gameIniBefore
                iniEditInstallation.userIniPath.readTextOrEmpty(UtIni.CHARSET) shouldBe userIniBefore
            }

            test("sets the platform's library path variable to the System directory, unset when the platform has none") {
                val instance = scriptInstance(okInstallation, "library-path").start()

                val result = instance.await()

                val envLine = result.log.first { it.startsWith("env=") }
                if (OS.libraryPathVariable != null) {
                    Path.of(envLine.removePrefix("env=")).toRealPath() shouldBe okInstallation.systemDirectory.toRealPath()
                } else {
                    envLine shouldBe "env="
                }
            }

            test("returns the exit code, the ini contents and an empty exec script for a clean run") {
                val instance = scriptInstance(okInstallation, "clean-result").start()

                val result = instance.await()

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

                val instance = scriptInstance(encodingInstallation, "encoding-result").start()

                val result = instance.await()

                result.gameIni shouldBe gameIniContent
                result.userIni shouldBe userIniContent
            }

            test("returns the exit code of a failing run") {
                val instance = scriptInstance(failInstallation, "failing-result").start()

                val result = instance.await()

                result.exitCode shouldBe 9
            }

            test("removes every file carrying the run's id and leaves the installation otherwise unchanged") {
                val before = snapshot(okInstallation.systemDirectory)

                val instance = scriptInstance(okInstallation, "cleanup").start()
                instance.await()

                val after = snapshot(okInstallation.systemDirectory)
                after shouldBe before
                after.keys.none { it.contains(instance.id) } shouldBe true
            }

            test("does not touch the files of another instance started against the same installation") {
                val first = scriptInstance(okInstallation, "first").start()
                val second = scriptInstance(okInstallation, "second").start()

                first.await()

                second.executablePath.exists() shouldBe true
                second.gameIniPath.exists() shouldBe true
                second.userIniPath.exists() shouldBe true

                second.await()
            }

            test("returns the same result instance from a second await, without re-reading the deleted files") {
                val instance = scriptInstance(okInstallation, "second-await").start()

                val first = instance.await()
                val second = instance.await()

                (first === second) shouldBe true
            }

            test("returns a result rather than throwing when a run outlives its timeout, still carrying the inis") {
                val instance = scriptInstance(okInstallation, "timeout-result", sleepingConfig).start()

                val result = instance.await(SHORT_TIMEOUT)

                result.log.isNotEmpty() shouldBe true
                result.gameIni shouldBe "0"
                result.userIni shouldBe "user-config"
                instance.isRunning shouldBe false
            }

            test("leaves no file carrying the run's id behind after a timed-out run") {
                val before = snapshot(okInstallation.systemDirectory)
                val instance = scriptInstance(okInstallation, "timeout-cleanup", sleepingConfig).start()

                instance.await(SHORT_TIMEOUT)

                val after = snapshot(okInstallation.systemDirectory)
                after shouldBe before
                after.keys.none { it.contains(instance.id) } shouldBe true
            }
        }

        context("isRunning") {
            val okInstallation = createFakeInstall("running-install", userIni = "user-config")

            test("is false before start, true while the run is in flight and false once it ends") {
                val instance = scriptInstance(okInstallation, "is-running", sleepingConfig)

                instance.isRunning shouldBe false

                instance.start()
                instance.isRunning shouldBe true

                instance.await(LONG_TIMEOUT)
                instance.isRunning shouldBe false
            }
        }

        context("drain") {
            val okInstallation = createFakeInstall("drain-install", userIni = "user-config")

            test("receives output while the run is in progress") {
                val instance = scriptInstance(okInstallation, "drain-live", sleepingConfig).start()
                val received = mutableListOf<String>()

                instance.drain(received::add)
                instance.await(LONG_TIMEOUT)

                received.any { it.contains("start-marker") } shouldBe true
            }

            test("throws when drained before start") {
                val instance = scriptInstance(okInstallation, "drain-before-start")

                shouldThrow<IllegalStateException> { instance.drain {} }
            }
        }
    })

/**
 * The name of the environment variable a fake run echoes into its log, so a test can observe the environment the
 * process was started with.
 */
private const val TEST_ENV_VAR = "UTINSTANCE_TEST_VAR"

/**
 * The name of the environment variable that makes a fake run sleep after writing its log, so a test can make a run
 * outlive its timeout.
 */
private const val SLEEP_VAR = "UTINSTANCE_SLEEP"

/**
 * How long a run made to outlive its timeout sleeps for on Windows, expressed in pings (each roughly a second apart)
 * for `ping -n`, since Windows' shell has no plain sleep command.
 */
private const val WINDOWS_SLEEP_SECONDS = "3"

/**
 * How long a run made to outlive its timeout sleeps for on Linux, expressed in whole seconds for `sleep`.
 */
private const val LINUX_SLEEP_SECONDS = "2"

/**
 * The timeout a sleeping run is awaited with when the test wants it killed, short enough that the run is always
 * still going when it is reached. Not to be confused with [UtInstance]'s own `KILL_TIMEOUT`, the grace period a kill
 * is given to report its exit code.
 */
private val SHORT_TIMEOUT = 1.seconds

/**
 * The timeout a sleeping run is awaited with when the test wants it to finish on its own instead of being killed.
 */
private val LONG_TIMEOUT = 6.seconds

/**
 * The OS shell a script instance starts in place of the game.
 *
 * @property executable The program a script instance starts.
 * @property arguments The arguments the program takes ahead of the game executable copy path.
 * @property script The body of the script the shell runs, either [WINDOWS_SCRIPT] or [LINUX_SCRIPT].
 * @property sleepSeconds How long a run made to outlive its timeout sleeps for, in the unit the platform's sleep
 * command takes: whole seconds for `sleep` on a POSIX shell, and pings (each roughly a second apart) for `ping -n`
 * on Windows.
 */
private data class Shell(
    val executable: Path,
    val arguments: List<String>,
    val script: String,
    val sleepSeconds: String,
)

/**
 * The fake run for Windows, started through `cmd /c`. Batch splits `%1`-style parameters on `=` even when quoted, so
 * arguments are walked with a `for /f "delims= "` loop over `%*` instead, which leaves `=` inside a token alone.
 */
private val WINDOWS_SCRIPT =
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
        echo envtest=!$TEST_ENV_VAR!
      )
    )
    del "%ARGS_FILE%" >nul 2>&1
    if defined $SLEEP_VAR ping -n %$SLEEP_VAR% 127.0.0.1 >nul
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

/**
 * The fake run for a POSIX shell, started through `/bin/sh`.
 */
private val LINUX_SCRIPT =
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
        echo "envtest=${'$'}$TEST_ENV_VAR"
      } > "${'$'}CWD/${'$'}LOG_NAME"
    fi
    if [ -n "${'$'}$SLEEP_VAR" ]; then sleep "${'$'}$SLEEP_VAR"; fi
    EXIT_CODE=0
    if [ -n "${'$'}INI_PATH" ] && [ -f "${'$'}INI_PATH" ]; then
      EXIT_CODE=${'$'}(head -n 1 "${'$'}INI_PATH")
    fi
    exit "${'$'}{EXIT_CODE:-0}"
    """.trimIndent()
