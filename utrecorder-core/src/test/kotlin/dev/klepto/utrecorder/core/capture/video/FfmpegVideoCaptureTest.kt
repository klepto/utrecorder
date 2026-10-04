package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.core.demo.DemoPlaybackConfig
import dev.klepto.utrecorder.core.demo.DemoPlaybackRenderDevice
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPackage
import dev.klepto.utrecorder.ut99.UtPlatform
import dev.klepto.utrecorder.ut99.UtProcessFiles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Duration
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

        val format = VideoFormat(width = 1600, height = 900, bitrate = 6_512_000, preset = "6", fitsBudget = true)

        fun capture(settings: VideoCaptureConfig = config) = FfmpegVideoCapture(settings, "96k", installation, directories)

        suspend fun launch(): Pair<UtProcessFiles, Executable> =
            workspace("video-capture", installation.systemDirectory, directories) { workspace ->
                val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)
                val process = capture().process(description, format, 100, hooks)
                val files = process.files(workspace)
                files to process.executable(files)
            }

        test("environment holds the port, the fps and the frame size of the format, and nothing else on Windows") {
            val environment = capture().environment(100, format)
            val expected =
                mapOf(
                    "UTRECORDER_PORT" to "100",
                    "UTRECORDER_FPS" to "120",
                    "UTRECORDER_WIDTH" to "1600",
                    "UTRECORDER_HEIGHT" to "900",
                )

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> environment shouldBe expected
                UtPlatform.LINUX -> environment.filterKeys { it in expected } shouldBe expected
            }
        }

        test("environment stops llvmpipe from presenting frames on Linux only, whichever render device is selected") {
            val opengl = VideoCaptureConfig(ConfigReader.EMPTY, renderDevice = DemoPlaybackRenderDevice.OPENGL)

            val presents = listOf(capture(), capture(opengl)).map { it.environment(100, format)["SWRAST_NO_PRESENT"] }

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> presents shouldBe listOf(null, null)
                UtPlatform.LINUX -> presents shouldBe listOf("1", "1")
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
                    .environment(100, format)
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
            val threads = capture().environment(100, format)["LP_NUM_THREADS"]

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> threads shouldBe null
                UtPlatform.LINUX -> threads shouldBe Runtime.getRuntime().availableProcessors().toString()
            }
        }

        test("process starts the game through the injector on Windows, and through nice with the hook library preloaded on Linux") {
            val (files, executable) = launch()

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    executable.executablePath shouldBe injector
                    executable.arguments[0] shouldBe hookLibrary.absolutePathString()
                    executable.arguments[1] shouldBe files.executable.absolutePathString()
                }

                UtPlatform.LINUX -> {
                    executable.executablePath shouldBe Path.of("nice")
                    executable.arguments.take(3) shouldBe listOf("-n", "10", files.executable.absolutePathString())
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

        fun hideHud(packageName: String): List<String> {
            val packages = listOf(UtPackage.named(packageName, ""))
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO, packages)
            val userIni = UtIni("")
            capture().process(description, format, 100, hooks).userIniEdits.forEach { userIni.it() }
            return userIni["Botpack.ChallengeHUD", "bHideHUD"]
        }

        test("process hides the HUD of a demo that loads a Bunny Track mod older than version 18") {
            hideHud("UTBT_BTMod_v9CE") shouldBe listOf("True")
        }

        test("process hides the HUD of a demo that loads no Bunny Track mod") {
            hideHud("Botpack") shouldBe listOf("True")
        }

        test("process leaves the HUD of a demo that loads Bunny Track mod version 18 alone") {
            hideHud("UTBT_BTMod_v18") shouldBe emptyList()
        }

        test("process leaves the HUD of a demo that loads a suffixed Bunny Track mod of version 18 or later alone") {
            hideHud("UTBT_BTMod_v19CE") shouldBe emptyList()
        }

        test("stopReason returns the reason of a line that reports the frame capture stopped") {
            val line = "uthooks: viewport 800x600 does not match the frame size 1280x720, capture stopped"

            capture().stopReason(line) shouldBe "viewport 800x600 does not match the frame size 1280x720, capture stopped"
        }

        test("stopReason returns null for a line that reports no stop") {
            capture().stopReason("uthooks: demo ended after 120 frames, kept 118") shouldBe null
        }

        test("stopReason returns null for the timing line") {
            val line =
                "uthooks: timing over 120 frames in ms, present mean 16.67 max 20.10, finish mean 9.80 max 70.25, " +
                    "read mean 0.52 max 1.30, send mean 0.01 max 0.20, sender mean 3.25 max 8.00, " +
                    "swap mean 0.40 max 0.90"

            capture().stopReason(line) shouldBe null
        }

        test("stopReason returns null for the context restore line") {
            val line = "uthooks: made the GL context current again after a window was destroyed"

            capture().stopReason(line) shouldBe null
        }

        test("execScript seeks to the run start, trims the demo to the configured fps, hides messages and smooths the recorder") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)

            capture(VideoCaptureConfig(ConfigReader.EMPTY, fps = 30)).execScript(description) shouldBe
                listOf("SeekStart", "DemoFrames 60.000000", "HideMessages", "SmoothRecorder")
        }

        test("execScript leaves the recorder unsmoothed when the config turns smoothing off") {
            val description = DemoPlaybackConfig.of("staged-demo", Duration.ZERO)

            capture(VideoCaptureConfig(ConfigReader.EMPTY, fps = 30, smoothRecorder = false)).execScript(description) shouldBe
                listOf("SeekStart", "DemoFrames 60.000000", "HideMessages")
        }

        test("execScript waits for the timer reset and the timer stop at the demo times the run starts and stops at") {
            val description =
                DemoPlaybackConfig.of("staged-demo", Duration.ZERO, runStart = 10.120962.seconds, runStop = 64.572962.seconds)

            capture(VideoCaptureConfig(ConfigReader.EMPTY, fps = 60, smoothRecorder = false)).execScript(description) shouldBe
                listOf("SeekStart", "DemoFrames 120.000000", "TimerReset 10.120962", "TimerStop 64.572962", "HideMessages")
        }
    })
