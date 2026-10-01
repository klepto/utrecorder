package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

class ServerConfigTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-server-config-test").toRealPath()

        afterSpec { root.toFile().deleteRecursively() }

        test("reads the recorder configuration from its own reader") {
            val keys = mapOf("record.width" to "640", "record.audio_bitrate" to "96k", "patch_version" to "469d")

            val recorder = ServerConfig(ConfigReader(keys::get), DirectoriesConfig(ConfigReader.EMPTY, home = root)).recorder

            recorder.video.width shouldBe 640
            recorder.audio.bitrate shouldBe "96k"
            recorder.ut.patchVersion shouldBe "469d"
        }

        test("installs the game of the recorder under its own directories") {
            val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root)

            val config = ServerConfig(ConfigReader.EMPTY, directories = directories)

            config.recorder.ut.gameDirectory shouldBe root / "installs" / "ut99"
        }
    })
