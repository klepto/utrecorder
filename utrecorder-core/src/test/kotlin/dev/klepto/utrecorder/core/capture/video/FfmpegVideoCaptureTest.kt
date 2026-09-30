package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPackage
import dev.klepto.utrecorder.ut99.UtPlatform
import dev.klepto.utrecorder.ut99.UtProcessFiles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FfmpegVideoCaptureTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-video-capture-test")
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)
        val installationDirectory = home / "install"
        val systemDirectory = installationDirectory / "System"
        systemDirectory.createDirectories()
        (systemDirectory / UtPlatform.current().gameExecutable).writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(ConfigReader.EMPTY, directories))
        val config = VideoCaptureConfig(ConfigReader.EMPTY)
        val hookLibrary = systemDirectory / "uthooks.dll"
        val injector = systemDirectory / "utinject.exe"
        val hooks = listOf(hookLibrary, injector)

        afterSpec {
            home.toFile().deleteRecursively()
        }

        fun capture(settings: VideoCaptureConfig = config) = FfmpegVideoCapture(settings, installation, directories)

        suspend fun launch(): Pair<UtProcessFiles, Executable> =
            workspace("video-capture", installation.systemDirectory, directories) { workspace ->
                val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)
                val process = capture().process(description, 100, hooks)
                val files = process.files(workspace)
                files to process.executable(files)
            }

        test("extractHooks writes the hooks into the System directory under the names of the workspace") {
            workspace("video-capture", installation.systemDirectory, directories) { workspace ->
                val paths = capture().extractHooks(workspace)

                paths.forEach { path ->
                    path.exists() shouldBe true
                    path.parent shouldBe installation.systemDirectory
                    path.name shouldStartWith "video-capture-"
                }
                if (UtPlatform.current() == UtPlatform.WINDOWS) {
                    paths shouldBe listOf(workspace["uthooks.dll"], workspace["utinject.exe"])
                }
            }
        }

        test("environment holds the port and the fps, and nothing else on Windows") {
            val environment = capture().environment(100, hooks)
            val expected = mapOf("UTRECORDER_PORT" to "100", "UTRECORDER_FPS" to "60")

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> environment shouldBe expected
                UtPlatform.LINUX -> environment.filterKeys { it in expected } shouldBe expected
            }
        }

        test("environment holds the configured llvmpipe tuning on Linux only") {
            val tunedConfig =
                VideoCaptureConfig(
                    ConfigReader.EMPTY,
                    linuxMesa =
                        LinuxMesaConfig(
                            ConfigReader.EMPTY,
                            threads = 3,
                            glthread = true,
                            noError = true,
                            vectorWidth = "16",
                        ),
                )

            val tuning =
                capture(tunedConfig)
                    .environment(100, hooks)
                    .filterKeys { it in setOf("LP_NUM_THREADS", "mesa_glthread", "MESA_NO_ERROR", "LP_NATIVE_VECTOR_WIDTH") }

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    tuning shouldBe emptyMap()
                }

                UtPlatform.LINUX -> {
                    tuning shouldBe
                        mapOf(
                            "LP_NUM_THREADS" to "3",
                            "mesa_glthread" to "true",
                            "MESA_NO_ERROR" to "1",
                            "LP_NATIVE_VECTOR_WIDTH" to "16",
                        )
                }
            }
        }

        test("environment uses the processor count as the llvmpipe thread count when none is configured") {
            val threads = capture().environment(100, hooks)["LP_NUM_THREADS"]

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> threads shouldBe null
                UtPlatform.LINUX -> threads shouldBe Runtime.getRuntime().availableProcessors().toString()
            }
        }

        test("process starts the game executable copy through the injector on Windows and preloads the hook library on Linux") {
            val (files, executable) = launch()

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    executable.executablePath shouldBe injector
                    executable.arguments[0] shouldBe hookLibrary.absolutePathString()
                    executable.arguments[1] shouldBe files.executable.absolutePathString()
                }

                UtPlatform.LINUX -> {
                    executable.executablePath shouldBe files.executable
                    executable.environment["LD_PRELOAD"] shouldBe hookLibrary.absolutePathString()
                }
            }
        }

        test("process passes the exec file of the run with -EXEC") {
            val (files, executable) = launch()

            executable.arguments shouldContain "-EXEC=${files.exec.absolutePathString()}"
        }

        test("process runs the game without sound and windowed") {
            val (_, executable) = launch()

            executable.arguments shouldContain "-nosound"
            executable.arguments shouldContain "-windowed"
        }

        test("process flushes the log of the run after every line") {
            val (_, executable) = launch()

            executable.arguments shouldContain "-forcelogflush"
        }

        fun hideHud(btMod: String): List<String> {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO, listOf(UtPackage.named(btMod, "")))
            val userIni = UtIni("")
            capture().process(description, 100, hooks).userIniEdits.forEach { userIni.it() }
            return userIni["Botpack.ChallengeHUD", "bHideHUD"]
        }

        test("process hides the HUD of a demo that loads a Bunny Track mod older than version 18") {
            hideHud("UTBT_BTMod_v13") shouldBe listOf("True")
        }

        test("process leaves the HUD of a demo that loads Bunny Track mod version 18 alone") {
            hideHud("UTBT_BTMod_v18") shouldBe emptyList()
        }

        test("execScript trims the demo to the configured fps, hides messages and smooths the recorder at fixed delays") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO, demoFrameInterval = 2.998.milliseconds)

            capture(VideoCaptureConfig(ConfigReader.EMPTY, fps = 30)).execScript(description) shouldBe
                listOf("DemoFrames 30.000000", "HideMessages", "SmoothRecorder 0.002998 0.030 0.015")
        }

        test("execScript leaves the recorder unsmoothed when the config turns smoothing off") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO, demoFrameInterval = 2.998.milliseconds)

            capture(VideoCaptureConfig(ConfigReader.EMPTY, fps = 30, smoothRecorder = false)).execScript(description) shouldBe
                listOf("DemoFrames 30.000000", "HideMessages")
        }

        test("execScript waits for the timer reset and the timer stop at the demo times the run starts and stops at") {
            val description =
                DemoPlaybackConfig.of("staged-demo", Duration.ZERO, runStart = 10.120962.seconds, runStop = 64.572962.seconds)

            capture(VideoCaptureConfig(ConfigReader.EMPTY, fps = 60, smoothRecorder = false)).execScript(description) shouldBe
                listOf("DemoFrames 60.000000", "TimerReset 10.120962", "TimerStop 64.572962", "HideMessages")
        }
    })
