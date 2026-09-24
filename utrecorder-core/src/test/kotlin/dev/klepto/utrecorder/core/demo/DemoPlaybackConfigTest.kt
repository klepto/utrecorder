package dev.klepto.utrecorder.core.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DemoPlaybackConfigTest :
    FunSpec({
        test("of plays the demo through the recorder console and adds the run margin and the timeout grace to the demo duration") {
            DemoPlaybackConfig.of("ut-2026-09-13-1030-05", 10.minutes) shouldBe
                DemoPlaybackConfig(
                    demoFileName = "ut-2026-09-13-1030-05",
                    demoDuration = 10.minutes,
                    execLine = "demoplay ut-2026-09-13-1030-05",
                    console = "UTRecorder.UTRecorderConsole",
                    exitDelay = 5.seconds,
                    startupTimeout = 90.seconds,
                    maxRunTime = 720.seconds,
                    timeout = 780.seconds,
                )
        }

        test("recorderIni auto-exits, stops the demo first and truncates the run values to whole seconds") {
            val description =
                DemoPlaybackConfig(
                    demoFileName = "ut-2026-09-13-1030-05",
                    demoDuration = 10.minutes,
                    execLine = "demoplay ut-2026-09-13-1030-05",
                    console = "UTRecorder.UTRecorderConsole",
                    exitDelay = 7900.milliseconds,
                    startupTimeout = 11.seconds,
                    maxRunTime = 613.seconds,
                    timeout = 630.seconds,
                )

            description.recorderIni().toString() shouldBe
                """
                [UTRecorder.UTRecorderConsole]
                bAutoExit=True
                bStopDemoFirst=True
                ExitDelay=7
                StartupTimeout=11
                MaxRunSeconds=613

                """.trimIndent()
        }
    })
