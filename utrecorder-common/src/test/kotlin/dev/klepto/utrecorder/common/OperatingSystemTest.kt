package dev.klepto.utrecorder.common

import dev.klepto.utrecorder.common.OperatingSystem.LINUX
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

class OperatingSystemTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-operating-system-test").toRealPath()

        afterSpec { root.toFile().deleteRecursively() }

        context("libraryPathVariable") {
            test("is null on WINDOWS") {
                WINDOWS.libraryPathVariable.shouldBeNull()
            }

            test("is LD_LIBRARY_PATH on LINUX") {
                LINUX.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }
        }

        context("executableSuffix") {
            test("is .exe on WINDOWS") {
                WINDOWS.executableSuffix shouldBe ".exe"
            }

            test("is empty on LINUX") {
                LINUX.executableSuffix shouldBe ""
            }
        }

        context("dataDirectory") {
            test("uses LOCALAPPDATA on WINDOWS") {
                val environment = mapOf("LOCALAPPDATA" to "${root / "local"}")
                val reader = SystemConfigReader(properties = { null }, environment = environment::get)

                WINDOWS.dataDirectory(reader) shouldBe root / "local"
            }

            test("falls back to AppData/Local under the home directory when LOCALAPPDATA is empty on WINDOWS") {
                val properties = mapOf("user.home" to "$root")
                val environment = mapOf("LOCALAPPDATA" to "")
                val reader = SystemConfigReader(properties = properties::get, environment = environment::get)

                WINDOWS.dataDirectory(reader) shouldBe root / "AppData" / "Local"
            }

            test("uses XDG_DATA_HOME on LINUX") {
                val environment = mapOf("XDG_DATA_HOME" to "${root / "data"}")
                val reader = SystemConfigReader(properties = { null }, environment = environment::get)

                LINUX.dataDirectory(reader) shouldBe root / "data"
            }

            test("falls back to .local/share under the home directory when XDG_DATA_HOME is empty on LINUX") {
                val properties = mapOf("user.home" to "$root")
                val environment = mapOf("XDG_DATA_HOME" to "")
                val reader = SystemConfigReader(properties = properties::get, environment = environment::get)

                LINUX.dataDirectory(reader) shouldBe root / ".local" / "share"
            }
        }
    })
