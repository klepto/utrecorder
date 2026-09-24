package dev.klepto.utrecorder.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

class DirectoriesConfigTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-directories-test").toRealPath()

        afterSpec { root.toFile().deleteRecursively() }

        test("defaults every directory to a subdirectory of home named after it") {
            val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root)

            directories.cache shouldBe root / "cache"
            directories.installs shouldBe root / "installs"
            directories.workspace shouldBe root / "workspace"
            directories.packages shouldBe root / "packages"
        }

        test("uses the home the reader configures") {
            val properties = mapOf("utrecorder.home" to "${root / "configured"}")
            val reader = SystemConfigReader(properties = properties::get, environment = { null })

            DirectoriesConfig(reader).home shouldBe root / "configured"
        }

        test("uses the directories the reader configures") {
            val properties =
                mapOf(
                    "utrecorder.cache" to "${root / "custom-cache"}",
                    "utrecorder.installs" to "${root / "custom-installs"}",
                    "utrecorder.workspace" to "${root / "custom-workspace"}",
                    "utrecorder.packages" to "${root / "custom-packages"}",
                )
            val reader = SystemConfigReader(properties = properties::get, environment = { null })

            val directories = DirectoriesConfig(reader, home = root)

            directories.cache shouldBe root / "custom-cache"
            directories.installs shouldBe root / "custom-installs"
            directories.workspace shouldBe root / "custom-workspace"
            directories.packages shouldBe root / "custom-packages"
        }
    })
