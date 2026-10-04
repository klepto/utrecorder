package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import dev.klepto.utrecorder.common.io.workspace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name

class DemoPlaybackHooksTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-hooks-test")

        afterSpec {
            home.toFile().deleteRecursively()
        }

        test("extract writes the hooks into the System directory under the names of the workspace") {
            val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)
            val systemDirectory = (home / "System").createDirectories()

            workspace("hooks", systemDirectory, directories) { workspace ->
                val paths = DemoPlaybackHooks.extract(workspace)

                paths.forEach { path ->
                    path.exists() shouldBe true
                    path.parent shouldBe systemDirectory
                    path.name shouldStartWith "hooks-"
                }
                if (OperatingSystem.current() == WINDOWS) {
                    paths shouldBe listOf(workspace["uthooks.dll"], workspace["utinject.exe"])
                }
            }
        }

        test("message returns a line the hook library printed, trimmed") {
            DemoPlaybackHooks.message("  uthooks: demo ended after 120 frames, kept 118\r") shouldBe
                "uthooks: demo ended after 120 frames, kept 118"
        }

        test("message returns a line that reports the frame capture stopped") {
            DemoPlaybackHooks.message("uthooks: a frame send to the frame socket failed, capture stopped") shouldBe
                "uthooks: a frame send to the frame socket failed, capture stopped"
        }

        test("message returns the timing line") {
            val line =
                "uthooks: timing over 120 frames in ms, present mean 16.67 max 20.10, finish mean 9.80 max 70.25, " +
                    "read mean 0.52 max 1.30, send mean 0.01 max 0.20, sender mean 3.25 max 8.00, " +
                    "swap mean 0.40 max 0.90"

            DemoPlaybackHooks.message(line) shouldBe line
        }

        test("message returns the context restore line") {
            DemoPlaybackHooks.message("uthooks: made the GL context current again after a window was destroyed") shouldBe
                "uthooks: made the GL context current again after a window was destroyed"
        }

        test("message returns null for a line the game printed") {
            DemoPlaybackHooks.message("Log: Demo playback started, uthooks: is named here") shouldBe null
        }
    })
