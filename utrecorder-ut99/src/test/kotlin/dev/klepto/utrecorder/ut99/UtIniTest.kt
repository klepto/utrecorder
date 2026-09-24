package dev.klepto.utrecorder.ut99

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class UtIniTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utini-test")

        afterSpec { root.toFile().deleteRecursively() }

        context("get") {
            test("returns every value of a repeated key in file order") {
                val ini =
                    UtIni(
                        """
                        [Engine.Engine]
                        EditPackages=Core
                        EditPackages=Engine
                        EditPackages=Editor
                        """.trimIndent(),
                    )

                ini["Engine.Engine", "EditPackages"] shouldBe listOf("Core", "Engine", "Editor")
            }

            test("splits a value containing '=' on the first '=' only") {
                val ini =
                    UtIni(
                        "[UWeb.WebServer]\n" +
                            "ServerActors=IpServer.UdpServerUplink MasterServerAddress=master.oldunreal.com " +
                            "MasterServerPort=27900",
                    )

                ini["UWeb.WebServer", "ServerActors"] shouldBe
                    listOf(
                        "IpServer.UdpServerUplink MasterServerAddress=master.oldunreal.com MasterServerPort=27900",
                    )
            }

            test("skips a commented-out entry") {
                val ini =
                    UtIni(
                        """
                        [Section]
                        ; Suppress=DevNet
                        Suppress=DevNet
                        """.trimIndent(),
                    )

                ini["Section", "Suppress"] shouldBe listOf("DevNet")
            }

            test("matches a bracketed key as plain text, not as an array index") {
                val ini =
                    UtIni(
                        """
                        [Section]
                        ListFactories[0]=First
                        ListFactories[1]=Second
                        """.trimIndent(),
                    )

                ini["Section", "ListFactories[0]"] shouldBe listOf("First")
                ini["Section", "ListFactories[1]"] shouldBe listOf("Second")
            }

            test("returns an empty list for a missing section") {
                val ini = UtIni("[Section]\nKey=1")

                ini["Missing", "Key"] shouldBe emptyList()
            }

            test("returns an empty list for a missing key") {
                val ini = UtIni("[Section]\nKey=1")

                ini["Section", "Missing"] shouldBe emptyList()
            }

            test("matches section and key names case-insensitively") {
                val ini = UtIni("[WinDrv.WindowsClient]\nStartupFullscreen=True")

                ini["windrv.windowsclient", "STARTUPFULLSCREEN"] shouldBe listOf("True")
            }
        }

        context("set") {
            test("replaces every entry of a repeated key with a single entry") {
                val ini =
                    UtIni(
                        """
                        [Engine.Engine]
                        Paths=../Textures/*.utx
                        Paths=../Sounds/*.uax
                        """.trimIndent(),
                    )

                ini["Engine.Engine", "Paths"] = "../Music/*.umx"

                ini["Engine.Engine", "Paths"] shouldBe listOf("../Music/*.umx")
            }

            test("appends a missing key to an existing section") {
                val ini = UtIni("[Section]\nKey=1")

                ini["Section", "New"] = "2"

                ini["Section", "New"] shouldBe listOf("2")
                ini["Section", "Key"] shouldBe listOf("1")
            }

            test("appends a missing section at the end of the file") {
                val ini = UtIni("[Existing]\nKey=1")

                ini["Engine.Engine", "EditPackages"] = "Core"

                ini.toString() shouldBe "[Existing]\nKey=1\n\n[Engine.Engine]\nEditPackages=Core"
            }
        }

        context("add") {
            test("appends another entry, keeping the existing ones") {
                val ini = UtIni("[Section]\nEditPackages=Core")

                ini.add("Section", "EditPackages", "Engine")

                ini["Section", "EditPackages"] shouldBe listOf("Core", "Engine")
            }

            test("inserts after the last non-blank line of the section") {
                val ini = UtIni("[Section]\nKey=1\nKey=2\n\n[NextSection]\nOther=1")

                ini.add("Section", "Key", "3")

                ini.toString() shouldBe "[Section]\nKey=1\nKey=2\nKey=3\n\n[NextSection]\nOther=1"
            }

            test("inserts directly after the header when the section holds no lines") {
                val ini = UtIni("[Section]\n[NextSection]\nOther=1")

                ini.add("Section", "Key", "1")

                ini.toString() shouldBe "[Section]\nKey=1\n[NextSection]\nOther=1"
            }

            test("appends a missing section at the end of the file") {
                val ini = UtIni("[Existing]\nKey=1")

                ini.add("Engine.Engine", "ServerPackages", "Botpack")

                ini.toString() shouldBe "[Existing]\nKey=1\n\n[Engine.Engine]\nServerPackages=Botpack"
            }
        }

        context("replace") {
            test("replaces one entry of a repeated key, leaving the others where they are") {
                val ini =
                    UtIni(
                        """
                        [Section]
                        Key=1
                        Key=2
                        Key=3
                        """.trimIndent(),
                    )

                ini.replace("Section", "Key", from = "2", to = "9")

                ini["Section", "Key"] shouldBe listOf("1", "9", "3")
            }

            test("throws when the section holds no entry of the key at from") {
                val ini = UtIni("[Section]\nKey=1\nKey=2")

                shouldThrow<IllegalArgumentException> { ini.replace("Section", "Key", from = "3", to = "9") }
            }

            test("throws when the section is missing") {
                val ini = UtIni("[Other]\nKey=1")

                shouldThrow<IllegalArgumentException> { ini.replace("Missing", "Key", from = "1", to = "2") }
            }
        }

        context("remove") {
            test("removes every entry of the key") {
                val ini =
                    UtIni(
                        """
                        [Section]
                        Suppress=DevNet
                        Keep=1
                        Suppress=DevNetTraffic
                        """.trimIndent(),
                    )

                ini.remove("Section", "Suppress")

                ini["Section", "Suppress"] shouldBe emptyList()
                ini["Section", "Keep"] shouldBe listOf("1")
            }

            test("removes the one entry of a repeated key holding the value") {
                val ini =
                    UtIni(
                        """
                        [Section]
                        Suppress=DevNet
                        Keep=1
                        Suppress=DevNetTraffic
                        """.trimIndent(),
                    )

                ini.remove("Section", "Suppress", value = "DevNet")

                ini["Section", "Suppress"] shouldBe listOf("DevNetTraffic")
                ini["Section", "Keep"] shouldBe listOf("1")
            }

            test("throws when the section holds no entry of the key at the value") {
                val ini = UtIni("[Section]\nKey=1")

                shouldThrow<IllegalArgumentException> { ini.remove("Section", "Key", value = "2") }
            }
        }

        context("toString") {
            test("returns the text unchanged with LF line endings") {
                val text = "[Section]\nKey=1\n; comment\n\nKey=2"

                UtIni(text).toString() shouldBe text
            }

            test("returns the text unchanged with CRLF line endings") {
                val text = "[Section]\r\nKey=1\r\n; comment\r\n\r\nKey=2"

                UtIni(text).toString() shouldBe text
            }
        }

        context("read/write") {
            test("round trips through a file, keeping non-ASCII bytes exact") {
                val text = "[Section]\nName=Café"
                val path = root / "UT.ini"

                UtIni(text).write(path)

                path.readBytes() shouldBe text.toByteArray(Charsets.ISO_8859_1)
                UtIni.read(path).toString() shouldBe text
                path.readText(UtPlatform.CHARSET) shouldBe text
            }
        }
    })
