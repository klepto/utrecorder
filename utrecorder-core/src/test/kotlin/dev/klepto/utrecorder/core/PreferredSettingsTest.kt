package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.ut99.UtIni
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PreferredSettingsTest :
    FunSpec({
        context("applyGameIni") {
            test("sets FirstRun to the patch number, keeping safe mode from showing on launch") {
                val ini = UtIni("[FirstRun]\nFirstRun=436\n")

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")

                ini["FirstRun", "FirstRun"] shouldBe listOf("469")
            }

            test("adds the FirstRun patch number when the copy holds no FirstRun section") {
                val ini = UtIni("[Engine.Engine]\nConsole=Engine.Console\n")

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")

                ini["FirstRun", "FirstRun"] shouldBe listOf("469")
            }

            test("names the given client the engine's viewport manager over the copy's own") {
                val ini = UtIni("[Engine.Engine]\nViewportManager=SDLDrv.SDLClient\n")

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")

                ini["Engine.Engine", "ViewportManager"] shouldBe listOf("WinDrv.WindowsClient")
            }

            test("collapses the copy's repeated key entries into the preferred settings' full list") {
                val ini =
                    UtIni(
                        """
                        [Core.System]
                        Paths=../System/*.u
                        Paths=../Dump/*.u
                        Paths=../Maps/*.unr
                        """.trimIndent(),
                    )

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")

                ini["Core.System", "Paths"] shouldBe
                    listOf("../System/*.u", "../Textures/*.utx", "../Maps/*.unr", "../Sounds/*.uax", "../Music/*.umx")
            }

            test("appends a section the copy holds none of, leaving the copy's other sections alone") {
                val before =
                    UtIni(
                        """
                        [Kept]
                        Key=1

                        [Engine.Engine]
                        """.trimIndent(),
                    )
                val ini = UtIni(before.toString())

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")

                ini["Kept", "Key"] shouldBe listOf("1")
                ini["Engine.Engine", "GameRenderDevice"] shouldBe listOf("OpenGLDrv.OpenGLRenderDevice")
            }

            test("leaves a key the preferred settings do not name as the copy holds it") {
                val ini =
                    UtIni(
                        """
                        [WinDrv.WindowsClient]
                        UnnamedKey=Kept
                        """.trimIndent() + "\n" +
                            "[IpDrv.TcpNetDriver]\n" +
                            "UnnamedNetKey=Kept\n",
                    )

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")

                ini["WinDrv.WindowsClient", "UnnamedKey"] shouldBe listOf("Kept")
                ini["IpDrv.TcpNetDriver", "UnnamedNetKey"] shouldBe listOf("Kept")
            }

            test("writes the frame pass's unbounded viewport values over the preferred ones") {
                val ini =
                    UtIni(
                        "[WinDrv.WindowsClient]\n" +
                            "WindowedViewportX=1280\n" +
                            "WindowedViewportY=720\n" +
                            "WindowedColorBits=32\n" +
                            "FullscreenViewportX=1280\n" +
                            "FullscreenViewportY=720\n" +
                            "FullscreenColorBits=32\n" +
                            "Brightness=0.900000\n",
                    )

                PreferredSettings.applyGameIni(ini, "WinDrv.WindowsClient")
                ini["WinDrv.WindowsClient", "WindowedViewportX"] = "64"
                ini["WinDrv.WindowsClient", "WindowedViewportY"] = "64"

                ini["WinDrv.WindowsClient", "WindowedViewportX"] shouldBe listOf("64")
                ini["WinDrv.WindowsClient", "WindowedViewportY"] shouldBe listOf("64")
                ini["WinDrv.WindowsClient", "Brightness"] shouldBe listOf("0.900000")
            }
        }

        context("applyUserIni") {
            test("replaces the user copy's player settings with the preferred ones") {
                val ini = UtIni("[DefaultPlayer]\nName=Loser\nClass=Botpack.TMale1\n")

                PreferredSettings.applyUserIni(ini)

                ini["DefaultPlayer", "Name"] shouldBe listOf("Player")
                ini["DefaultPlayer", "Class"] shouldBe listOf("Botpack.TMale2")
                ini["DefaultPlayer", "team"] shouldBe listOf("255")
            }

            test("carries the preferred no-HUD, no-crosshair, mouse-look-without-smoothing values") {
                val ini = UtIni("[Botpack.ChallengeHUD]\nbHideHUD=False\n")

                PreferredSettings.applyUserIni(ini)

                ini["Botpack.ChallengeHUD", "bHideHUD"] shouldBe listOf("True")
                ini["Botpack.ChallengeHUD", "bHideAllWeapons"] shouldBe listOf("True")
                ini["Engine.PlayerPawn", "bNoMouseSmoothing"] shouldBe listOf("True")
                ini["Engine.PlayerPawn", "bAlwaysMouseLook"] shouldBe listOf("True")
                ini["Engine.PlayerPawn", "Bob"] shouldBe listOf("0.000000")
            }

            test("appends every preferred user section a fresh copy holds none of") {
                val ini = UtIni("[DefaultPlayer]\nName=Player\n")

                PreferredSettings.applyUserIni(ini)

                ini["Engine.Input", "Fire"] shouldBe emptyList()
                ini["Engine.Input", "LeftMouse"] shouldBe listOf("Fire")
                ini["Engine.PlayerPawn", "WeaponPriority[0]"] shouldBe listOf("Translocator")
            }
        }
    })
