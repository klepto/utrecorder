package dev.klepto.utrecorder.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.NoSuchFileException
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes

/**
 * Tests for [UtRecorder.playDemo] that run without a game: the demo is read before anything is launched.
 */
class UtRecorderTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-playdemo-test")
        val installation = home / "install"

        beforeSpec { installation.createDirectories() }
        afterSpec { home.toFile().deleteRecursively() }

        test("a missing demo file fails before anything is launched") {
            val recorder = UtRecorder(RecorderConfig(installationDirectory = installation))

            shouldThrow<NoSuchFileException> { recorder.playDemo(home / "missing.dem") }
        }

        test("an unreadable demo fails before anything is launched") {
            val demo = home / "broken.dem"
            demo.writeBytes(byteArrayOf(0x1, 0x2, 0x3, 0x4))
            val recorder = UtRecorder(RecorderConfig(installationDirectory = installation))

            val failure = shouldThrow<IllegalStateException> { recorder.playDemo(demo) }

            failure.message shouldContain "Demo names no map"
        }
    })
