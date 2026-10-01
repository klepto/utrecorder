package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

class UtCliTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utcli-test")
        val homeReader = ConfigReader { mapOf("home" to "${root / "home"}")[it] }

        afterSpec { root.toFile().deleteRecursively() }

        context("run") {
            test("returns 1 for an unknown command") {
                UtCli(listOf("compile", "--install=${root / "unknown"}"), homeReader).run() shouldBe 1
            }

            test("returns 1 when the arguments name no command") {
                UtCli(listOf("--install=${root / "missing"}"), homeReader).run() shouldBe 1
            }

            test("returns 1 for make with no --sources, without provisioning an installation") {
                val install = root / "no-sources"
                val arguments = listOf("make", "--output=${root / "out" / "Package.u"}", "--install=$install")

                UtCli(arguments, homeReader).run() shouldBe 1

                install.exists() shouldBe false
            }

            test("compiles in the workspace directory of the configuration it was given") {
                val platform = UtPlatform.current()
                val install = root / "configured"
                val systemDirectory = install / "System"
                systemDirectory.createDirectories()
                (install / ".utrecorder.version").writeText(platform.patchFileName("469e"))
                (systemDirectory / platform.gameExecutable).writeText("")
                (systemDirectory / platform.compilerExecutable).writeText("")
                (systemDirectory / "Default.ini").writeText("[Editor.EditorEngine]\nEditPackages=Core")
                val sources = root / "Configured"
                (sources / "Classes").createDirectories()
                val home = root / "configured-home"
                val reader = ConfigReader { mapOf("home" to "$home")[it] }
                val arguments = listOf("make", "--sources=$sources", "--output=${root / "Configured.u"}", "--install=$install")

                UtCli(arguments, reader).run() shouldBe 1

                (home / "workspace").listDirectoryEntries("ucc-*") shouldHaveSize 1
            }
        }
    })
