package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.NoSuchFileException
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class RecorderTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-recorder-test")
        val installation = home / "install"
        val config = RecorderConfig(ConfigReader.EMPTY, UtConfig(ConfigReader.EMPTY, gameDirectory = installation))
        val recorder = Recorder(config, DirectoriesConfig(ConfigReader.EMPTY, home = home / "home"))

        beforeSpec {
            (installation / "System").createDirectories()
            (installation / "System" / UtPlatform.current().gameExecutable).writeText("")
        }
        afterSpec { home.toFile().deleteRecursively() }

        test("playDemo fails before launching the game when no file exists at the demo path") {
            shouldThrow<NoSuchFileException> { recorder.playDemo(home / "missing.dem") }
        }

        test("playDemo fails before launching the game when the demo does not parse") {
            val demo = home / "broken.dem"
            demo.writeBytes(byteArrayOf(0x1, 0x2, 0x3, 0x4))

            shouldThrow<IllegalStateException> { recorder.playDemo(demo) }
        }

        test("record fails before downloading when the demo URL names no file") {
            val url = "http://example.invalid/?name=run.dem#top"

            shouldThrow<IllegalArgumentException> { recorder.record(url) }.message shouldBe
                "Demo URL http://example.invalid/?name=run.dem#top names no file"
        }

        test("record fails before downloading when the decoded demo file name leaves the workspace") {
            listOf(
                "http://example.invalid/demos/..%2Frun.dem",
                "http://example.invalid/demos/..%5Crun.dem",
                "http://example.invalid/demos/%2E%2E",
                "http://example.invalid/demos/C%3Arun.dem",
                "http://example.invalid/demos/run%00.dem",
            ).forEach { url ->
                shouldThrow<IllegalArgumentException> { recorder.record(url) }.message shouldBe
                    "Demo URL $url names no file"
            }
        }
    })
