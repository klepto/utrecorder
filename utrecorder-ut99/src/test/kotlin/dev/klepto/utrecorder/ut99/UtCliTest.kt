package dev.klepto.utrecorder.ut99

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists

class UtCliTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utcli-test")

        afterSpec { root.toFile().deleteRecursively() }

        context("run") {
            test("returns 1 for an unknown command") {
                UtCli(listOf("compile", "--install=${root / "unknown"}")).run() shouldBe 1
            }

            test("returns 1 when the arguments name no command") {
                UtCli(listOf("--install=${root / "missing"}")).run() shouldBe 1
            }

            test("returns 1 for make with no --sources, without provisioning an installation") {
                val install = root / "no-sources"
                val arguments = listOf("make", "--output=${root / "out" / "Package.u"}", "--install=$install")

                UtCli(arguments).run() shouldBe 1

                install.exists() shouldBe false
            }
        }
    })
