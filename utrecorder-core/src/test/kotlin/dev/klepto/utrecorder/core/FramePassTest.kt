package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import dev.klepto.utrecorder.ut99.UtProcessFiles
import io.kotest.core.spec.style.FunSpec
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

class FramePassTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-framepass-test")
        val installationDirectory = home / "install"
        val gameExecutable = UtPlatform.current().gameExecutable
        installationDirectory.resolve("System").createDirectories()
        installationDirectory.resolve("System/$gameExecutable").writeText("")
        val installation = UtInstallation(installationDirectory, UtConfig(DirectoriesConfig(home)))
        val config = RecorderConfig(installationDirectory = installationDirectory)
        val hooks =
            listOf(
                installationDirectory.resolve("System/uthooks.dll"),
                installationDirectory.resolve("System/utinject.exe"),
            )

        afterSpec {
            home.toFile().deleteRecursively()
        }

        fun pass(settings: RecorderConfig = config) = FramePass(settings, installation, DirectoriesConfig(home))

        suspend fun run(settings: RecorderConfig = config): Pair<UtProcessFiles, Executable> =
            workspace("frame-pass", installation.systemDirectory, DirectoriesConfig(home)) { workspace ->
                val description = PlaybackDescription.of("staged-demo", Duration.ZERO, settings)
                val process = pass(settings).process(description, 100, hooks)
                val files = process.files(workspace)
                files to process.executable(files)
            }

        test("hook extraction writes the shim and the launcher into the installation's System directory under the pass's names") {
            workspace("frame-pass", installation.systemDirectory, DirectoriesConfig(home)) { workspace ->
                val paths = pass().hooks(workspace)

                paths.forEach { path ->
                    path.exists() shouldBe true
                    path.parent shouldBe installation.systemDirectory
                    path.name shouldStartWith "frame-pass-"
                }
                if (UtPlatform.current() == UtPlatform.WINDOWS) {
                    paths shouldBe listOf(workspace["uthooks.dll"], workspace["utinject.exe"])
                }
            }
        }

        test("the environment carries the shim port and fps off the config, and no preload on Windows") {
            pass().environment(100, hooks) shouldBe mapOf("UTRECORDER_PORT" to "100", "UTRECORDER_FPS" to "60")
        }

        test("the environment carries none of the llvmpipe tuning on Windows and it on Linux") {
            val tunedConfig =
                config.copy(
                    renderThreads = 3,
                    renderGlthread = true,
                    renderNoError = true,
                    renderVectorWidth = "16",
                )

            val tuning =
                pass(tunedConfig)
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

        test("the environment falls back to the processor count for llvmpipe threads when the config carries none") {
            val threads = pass().environment(100, hooks)["LP_NUM_THREADS"]?.toIntOrNull()

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> threads shouldBe null
                UtPlatform.LINUX -> threads shouldBe Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            }
        }

        test("the frame pass launches through the injector with the hook library beside the game executable copy") {
            val (files, executable) = run()

            when (UtPlatform.current()) {
                UtPlatform.WINDOWS -> {
                    executable.executablePath shouldBe hooks.single { it.name == "utinject.exe" }
                    executable.arguments.first() shouldBe
                        hooks
                            .single { it.name.startsWith("uthooks") }
                            .absolutePathString()
                    executable.arguments.drop(1).first() shouldBe files.executable.absolutePathString()
                }

                UtPlatform.LINUX -> {
                    executable.executablePath shouldBe files.executable
                    executable.environment["LD_PRELOAD"] shouldBe
                        hooks.single { it.name.startsWith("uthooks") }.absolutePathString()
                }
            }
        }

        test("the exec script's -EXEC path names the run's exec file in the instance workspace") {
            val (files, executable) = run(config.copy(fps = 30))

            val execArgument = executable.arguments.first { it.startsWith("-EXEC=") }

            execArgument shouldBe "-EXEC=${files.exec.absolutePathString()}"
        }

        test("the exec script holds the staged demo, the stepped clock, the configured frame rate and hidden messages") {
            val tunedConfig = config.copy(fps = 30)
            val description = PlaybackDescription.of("staged-demo", Duration.ZERO, tunedConfig)

            pass(tunedConfig).execScript(description) shouldBe
                listOf(
                    "demoplay staged-demo?timebased",
                    "DemoFrames 30.000000",
                    "HideMessages",
                )
        }

        test("the frame pass is soundless and windowed") {
            val (_, executable) = run()

            executable.arguments.any { it == "-nosound" } shouldBe true
            executable.arguments.any { it == "-windowed" } shouldBe true
        }
    })
