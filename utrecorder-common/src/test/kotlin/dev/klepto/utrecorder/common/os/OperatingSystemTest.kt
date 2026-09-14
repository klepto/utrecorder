package dev.klepto.utrecorder.common.os

import dev.klepto.utrecorder.common.config.PropertySource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class OperatingSystemTest :
    FunSpec({
        fun source(
            properties: Map<String, String> = emptyMap(),
            environment: Map<String, String> = emptyMap(),
        ) = PropertySource(properties = properties::get, environment = environment::get)

        context("libraryPathVariable") {
            test("WINDOWS names none") {
                OperatingSystem.WINDOWS.libraryPathVariable.shouldBeNull()
            }

            test("LINUX names LD_LIBRARY_PATH") {
                OperatingSystem.LINUX.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }

            test("UNKNOWN names LD_LIBRARY_PATH") {
                OperatingSystem.UNKNOWN.libraryPathVariable shouldBe "LD_LIBRARY_PATH"
            }
        }

        context("executableSuffix") {
            test("WINDOWS is .exe") {
                OperatingSystem.WINDOWS.executableSuffix shouldBe ".exe"
            }

            test("LINUX is empty") {
                OperatingSystem.LINUX.executableSuffix shouldBe ""
            }

            test("UNKNOWN is empty, as LINUX") {
                OperatingSystem.UNKNOWN.executableSuffix shouldBe ""
            }
        }

        context("current") {
            test("reads Windows 11 as WINDOWS") {
                OperatingSystem.current(source(mapOf("os.name" to "Windows 11"))) shouldBe OperatingSystem.WINDOWS
            }

            test("reads Linux as LINUX") {
                OperatingSystem.current(source(mapOf("os.name" to "Linux"))) shouldBe OperatingSystem.LINUX
            }

            test("reads Mac OS X as UNKNOWN") {
                OperatingSystem.current(source(mapOf("os.name" to "Mac OS X"))) shouldBe OperatingSystem.UNKNOWN
            }
        }

        context("dataDirectory") {
            val home = Path.of("/home/probe").toAbsolutePath().normalize()
            val data = Path.of("/data/probe").toAbsolutePath().normalize()

            test("WINDOWS reads LOCALAPPDATA") {
                val source = source(mapOf("user.home" to "$home"), mapOf("LOCALAPPDATA" to "$data"))

                OperatingSystem.WINDOWS.dataDirectory(source) shouldBe data
            }

            test("WINDOWS falls back to AppData/Local under user.home") {
                OperatingSystem.WINDOWS.dataDirectory(source(mapOf("user.home" to "$home"))) shouldBe
                    home.resolve("AppData").resolve("Local")
            }

            test("LINUX reads XDG_DATA_HOME") {
                val source = source(mapOf("user.home" to "$home"), mapOf("XDG_DATA_HOME" to "$data"))

                OperatingSystem.LINUX.dataDirectory(source) shouldBe data
            }

            test("LINUX falls back to .local/share under user.home") {
                OperatingSystem.LINUX.dataDirectory(source(mapOf("user.home" to "$home"))) shouldBe
                    home.resolve(".local").resolve("share")
            }
        }
    })
