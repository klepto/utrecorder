package dev.klepto.utrecorder.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
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

        test("uses the home of the source checkout that contains the user.dir the reader holds") {
            val checkout = root / "checkout"
            (checkout / "module").createDirectories()
            (checkout / "settings.gradle.kts").createFile()
            val properties = mapOf("user.dir" to "${checkout / "module"}")
            val reader = SystemConfigReader(properties = properties::get, environment = { null })

            DirectoriesConfig(reader).home shouldBe checkout / ".utrecorder"
        }

        test("uses the data directory the reader holds when no source checkout contains its user.dir") {
            val properties = mapOf("user.dir" to "${root / "outside"}")
            val environment = mapOf("LOCALAPPDATA" to "${root / "data"}", "XDG_DATA_HOME" to "${root / "data"}")
            val reader = SystemConfigReader(properties = properties::get, environment = environment::get)

            DirectoriesConfig(reader).home shouldBe root / "data" / "utrecorder"
        }

        test("throws IllegalStateException for a reader that holds no home, user.dir or user.home") {
            shouldThrow<IllegalStateException> { DirectoriesConfig(ConfigReader.EMPTY) }
        }
    })
