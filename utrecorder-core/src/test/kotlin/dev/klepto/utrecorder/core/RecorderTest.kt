package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.ut99.UtConfig
import dev.klepto.utrecorder.ut99.UtPlatform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
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
        val recorder = Recorder(RecorderConfig(ConfigReader.EMPTY, UtConfig(ConfigReader.EMPTY, gameDirectory = installation)))

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
    })
